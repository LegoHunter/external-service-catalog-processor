package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Inventory;
import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.marketplace-sync", name = "enabled", havingValue = "true")
public class BricklinkMarketplaceSyncService {
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_CLAIMED = "CLAIMED";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_BLOCKED = "BLOCKED";
    private static final String TYPE_PRICE_UPDATE = "PRICE_UPDATE";

    private final BricklinkMarketplaceSyncProperties properties;
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao;
    private final ItemInventoryDao itemInventoryDao;
    private final BricklinkRestClient bricklinkRestClient;
    private final BricklinkRemoteInventorySafetyService safetyService;

    public BricklinkMarketplaceSyncResult runOnce() {
        long start = System.currentTimeMillis();
        BricklinkMarketplaceSyncMode mode = properties.effectiveMode();
        Set<MarketplaceListingSyncRequest> requests = marketplaceListingSyncRequestDao.findClaimableByStatusCodeAndSyncRequestTypeCodes(
                STATUS_PENDING,
                Set.of(TYPE_PRICE_UPDATE),
                now(),
                properties.effectiveBatchSize()
        );
        if (requests.isEmpty()) {
            return BricklinkMarketplaceSyncResult.noWork(mode, elapsedMillis(start));
        }

        Counters counters = new Counters(requests.size());
        for (MarketplaceListingSyncRequest request : requests) {
            MarketplaceListingSyncRequest effectiveRequest = request;
            try {
                if (!TYPE_PRICE_UPDATE.equals(normalize(request.getSyncRequestTypeCode()))) {
                    counters.blocked++;
                    markBlocked(request, "UNSUPPORTED_SYNC_TYPE", "Unsupported sync request type: " + request.getSyncRequestTypeCode());
                    continue;
                }
                if (mode == BricklinkMarketplaceSyncMode.APPLY) {
                    effectiveRequest = marketplaceListingSyncRequestDao.claim(
                            request.getMarketplaceListingSyncRequestId(),
                            STATUS_PENDING,
                            STATUS_CLAIMED,
                            now()
                    ).orElse(null);
                    if (effectiveRequest == null) {
                        continue;
                    }
                    counters.requestsClaimed++;
                }

                SyncContext context = context(effectiveRequest);
                Inventory remoteInventory = data(bricklinkRestClient.getInventories(context.bricklinkInventoryId().longValue()));
                BricklinkRemoteInventorySafetyResult safety = safetyService.verify(
                        properties,
                        context.listing(),
                        context.bricklinkListing(),
                        context.itemInventory(),
                        remoteInventory
                );
                if (!safety.allowed()) {
                    counters.blocked++;
                    if (mode == BricklinkMarketplaceSyncMode.APPLY) {
                        markBlocked(effectiveRequest, safety.statusCode(), safety.message());
                        updateSafetyMetadata(context.bricklinkListing(), safety, remoteInventory);
                    }
                    log.warn(
                            "bricklink.marketplace_sync.blocked marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} statusCode={} message={}",
                            effectiveRequest.getMarketplaceListingSyncRequestId(),
                            effectiveRequest.getMarketplaceListingId(),
                            context.bricklinkInventoryId(),
                            safety.statusCode(),
                            safety.message()
                    );
                    continue;
                }

                if (mode == BricklinkMarketplaceSyncMode.DRY_RUN) {
                    counters.dryRunVerified++;
                    log.info(
                            "bricklink.marketplace_sync.dry_run_verified marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} requestedPrice={} currencyCode={}",
                            effectiveRequest.getMarketplaceListingSyncRequestId(),
                            effectiveRequest.getMarketplaceListingId(),
                            context.bricklinkInventoryId(),
                            price(effectiveRequest.getRequestedUnitPrice()),
                            effectiveRequest.getCurrencyCode()
                    );
                    continue;
                }

                updateRemoteInventory(context, effectiveRequest, safety);
                updateSafetyMetadata(context.bricklinkListing(), safety, remoteInventory);
                markSucceeded(effectiveRequest, safety);
                counters.remoteVerified++;
                counters.remoteUpdated++;
            } catch (RuntimeException e) {
                counters.failed++;
                if (mode == BricklinkMarketplaceSyncMode.APPLY) {
                    markRetryOrFailed(effectiveRequest, e.getMessage());
                }
                log.warn(
                        "bricklink.marketplace_sync.failed marketplaceListingSyncRequestId={} marketplaceListingId={} message={}",
                        request.getMarketplaceListingSyncRequestId(),
                        request.getMarketplaceListingId(),
                        e.getMessage(),
                        e
                );
            }
        }

        return counters.result(mode, elapsedMillis(start));
    }

    private SyncContext context(MarketplaceListingSyncRequest request) {
        MarketplaceListing listing = marketplaceListingDao.findByMarketplaceListingId(request.getMarketplaceListingId())
                .orElseThrow(() -> new IllegalStateException("Marketplace listing not found"));
        if (!properties.getBricklinkExternalServiceId().equals(listing.getListingExternalServiceId())) {
            throw new IllegalStateException("Marketplace listing does not belong to configured BrickLink external service");
        }
        BricklinkMarketplaceListing bricklinkListing = bricklinkMarketplaceListingDao.findByMarketplaceListingId(request.getMarketplaceListingId())
                .orElseThrow(() -> new IllegalStateException("BrickLink listing mapping not found"));
        ItemInventory itemInventory = itemInventoryDao.findByItemInventoryId(listing.getItemInventoryId())
                .orElseThrow(() -> new IllegalStateException("Item inventory not found"));
        Integer bricklinkInventoryId = bricklinkListing.getBricklinkInventoryId();
        if (bricklinkInventoryId == null) {
            throw new IllegalStateException("BrickLink inventory id is missing");
        }
        if (request.getRemoteInventoryId() != null && !request.getRemoteInventoryId().equals(bricklinkInventoryId.toString())) {
            throw new IllegalStateException("Sync request remote inventory id does not match BrickLink listing mapping");
        }
        return new SyncContext(listing, bricklinkListing, itemInventory, bricklinkInventoryId);
    }

    private void updateRemoteInventory(SyncContext context, MarketplaceListingSyncRequest request, BricklinkRemoteInventorySafetyResult safety) {
        Inventory update = new Inventory();
        update.setUnit_price(price(request.getRequestedUnitPrice()));
        update.setRemarks(safety.desiredRemarks());
        if (properties.nonProdEnvironment()) {
            update.setIs_stock_room(true);
            update.setStock_room_id(properties.effectiveNonProdStockRoomId());
        }
        bricklinkRestClient.updateInventory(context.bricklinkInventoryId().longValue(), update);
        log.info(
                "bricklink.marketplace_sync.remote_updated marketplaceListingSyncRequestId={} marketplaceListingId={} bricklinkInventoryId={} requestedPrice={}",
                request.getMarketplaceListingSyncRequestId(),
                request.getMarketplaceListingId(),
                context.bricklinkInventoryId(),
                price(request.getRequestedUnitPrice())
        );
    }

    private void updateSafetyMetadata(
            BricklinkMarketplaceListing bricklinkListing,
            BricklinkRemoteInventorySafetyResult safety,
            Inventory remoteInventory
    ) {
        bricklinkListing.setEnvironmentCode(properties.effectiveEnvironmentCode());
        bricklinkListing.setSystemRemarksHash(safety.desiredRemarksHash());
        bricklinkListing.setLastRemoteVerifiedAt(now());
        bricklinkListing.setLastRemoteSafetyStatusCode(safety.statusCode());
        bricklinkListing.setLastRemoteSafetyMessage(safety.message());
        if (remoteInventory != null) {
            bricklinkListing.setIsStockRoom(remoteInventory.getIs_stock_room());
            bricklinkListing.setStockRoomId(remoteInventory.getStock_room_id());
            bricklinkListing.setRemarks(safety.desiredRemarks() == null ? remoteInventory.getRemarks() : safety.desiredRemarks());
        }
        bricklinkMarketplaceListingDao.update(bricklinkListing);
    }

    private void markSucceeded(MarketplaceListingSyncRequest request, BricklinkRemoteInventorySafetyResult safety) {
        request.setSyncRequestStatusCode(STATUS_SUCCEEDED);
        request.setLastErrorMessage(null);
        request.setClaimedAt(null);
        request.setCompletedAt(now());
        request.setRemoteVisibilityScopeCode(properties.nonProdEnvironment() ? BricklinkPricingApplyService.REMOTE_SCOPE_STOCKROOM : request.getRemoteVisibilityScopeCode());
        request.setRemoteVisibilityContainerId(properties.nonProdEnvironment() ? properties.effectiveNonProdStockRoomId() : request.getRemoteVisibilityContainerId());
        request.setRemoteIsPubliclyAvailable(!properties.nonProdEnvironment());
        marketplaceListingSyncRequestDao.update(request);
    }

    private void markBlocked(MarketplaceListingSyncRequest request, String statusCode, String message) {
        request.setSyncRequestStatusCode(STATUS_BLOCKED);
        request.setLastErrorMessage(shortMessage(statusCode + ": " + message));
        request.setClaimedAt(null);
        request.setCompletedAt(now());
        marketplaceListingSyncRequestDao.update(request);
    }

    private void markRetryOrFailed(MarketplaceListingSyncRequest request, String message) {
        request.setClaimedAt(null);
        request.setLastErrorMessage(shortMessage(message));
        if (request.getAttemptCount() != null && request.getMaxAttempts() != null && request.getAttemptCount() >= request.getMaxAttempts()) {
            request.setSyncRequestStatusCode(STATUS_FAILED);
            request.setCompletedAt(now());
        } else {
            request.setSyncRequestStatusCode(STATUS_PENDING);
            request.setNextAttemptAt(now().plus(properties.effectiveRetryBackoff()));
        }
        marketplaceListingSyncRequestDao.update(request);
    }

    private String shortMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private Double price(BigDecimal price) {
        return price == null ? null : price.doubleValue();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private record SyncContext(
            MarketplaceListing listing,
            BricklinkMarketplaceListing bricklinkListing,
            ItemInventory itemInventory,
            Integer bricklinkInventoryId
    ) {
    }

    private static final class Counters {
        private final int selected;
        private int requestsClaimed;
        private int remoteVerified;
        private int remoteUpdated;
        private int dryRunVerified;
        private int blocked;
        private int failed;

        private Counters(int selected) {
            this.selected = selected;
        }

        private BricklinkMarketplaceSyncResult result(BricklinkMarketplaceSyncMode mode, long elapsedMillis) {
            String outcome;
            if (failed > 0 && remoteUpdated == 0 && dryRunVerified == 0) {
                outcome = "FAILED";
            } else if (failed > 0 || blocked > 0) {
                outcome = "PARTIAL_SUCCESS";
            } else if (remoteUpdated == 0 && dryRunVerified == 0) {
                outcome = "NO_SYNCABLE_REQUESTS";
            } else {
                outcome = "SUCCESS";
            }
            return new BricklinkMarketplaceSyncResult(
                    outcome,
                    mode,
                    selected,
                    requestsClaimed,
                    remoteVerified,
                    remoteUpdated,
                    dryRunVerified,
                    blocked,
                    failed,
                    elapsedMillis
            );
        }
    }
}
