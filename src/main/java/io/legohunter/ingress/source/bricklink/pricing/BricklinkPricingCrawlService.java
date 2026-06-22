package io.legohunter.ingress.source.bricklink.pricing;

import com.bricklink.api.ajax.BricklinkAjaxClient;
import com.bricklink.api.ajax.exception.BricklinkAjaxClientException;
import com.bricklink.api.ajax.model.v1.Item;
import com.bricklink.api.ajax.model.v1.ItemForSale;
import com.bricklink.api.ajax.support.CatalogItemsForSaleResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dao.PricingSnapshotDao;
import io.legohunter.data.dao.PricingSnapshotListingDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingCrawlWorkItem;
import io.legohunter.data.dto.PricingSnapshot;
import io.legohunter.data.dto.PricingSnapshotListing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.crawl", name = "enabled", havingValue = "true")
public class BricklinkPricingCrawlService {
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_SKIPPED_MISSING_ITEM_NUMBER = "SKIPPED_MISSING_ITEM_NUMBER";
    static final String STATUS_SKIPPED_MISSING_CONDITION = "SKIPPED_MISSING_CONDITION";
    static final String STATUS_FAILED_ITEM_ID_LOOKUP_NO_MATCH = "FAILED_ITEM_ID_LOOKUP_NO_MATCH";
    static final String STATUS_FAILED_ITEM_ID_LOOKUP_AMBIGUOUS = "FAILED_ITEM_ID_LOOKUP_AMBIGUOUS";
    static final String STATUS_FAILED_ITEM_ID_LOOKUP_HTTP_ERROR = "FAILED_ITEM_ID_LOOKUP_HTTP_ERROR";
    static final String STATUS_FAILED_PRICING_HTTP_ERROR = "FAILED_PRICING_HTTP_ERROR";
    static final String STATUS_FAILED_PRICING_PARSE_ERROR = "FAILED_PRICING_PARSE_ERROR";

    private static final String WORK_STATUS_STARTED = "STARTED";
    private static final String CATALOG_ITEMS_FOR_SALE_PATH = "/ajax/clone/catalogifs.ajax";

    private final BricklinkAjaxClient bricklinkAjaxClient;
    private final BricklinkPricingCrawlProperties properties;
    private final MarketplaceListingDao marketplaceListingDao;
    private final ExternalCatalogItemDao externalCatalogItemDao;
    private final ItemInventoryDao itemInventoryDao;
    private final PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private final PricingSnapshotDao pricingSnapshotDao;
    private final PricingSnapshotListingDao pricingSnapshotListingDao;
    private final ObjectMapper objectMapper;

    public BricklinkPricingCrawlResult runOnce() {
        long start = System.currentTimeMillis();
        Set<MarketplaceListing> listings = marketplaceListingDao.findByListingExternalServiceIdAndListingStatusCode(
                properties.getBricklinkExternalServiceId(),
                properties.effectiveActiveListingStatusCode(),
                properties.effectiveBatchSize()
        );

        if (listings.isEmpty()) {
            return BricklinkPricingCrawlResult.noWork(elapsedMillis(start));
        }

        CrawlCounters counters = new CrawlCounters(listings.size());
        for (MarketplaceListing listing : listings) {
            processListing(listing, counters);
        }

        String outcome = counters.failedListings > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
        return new BricklinkPricingCrawlResult(
                outcome,
                counters.listingsSelected,
                counters.snapshotsWritten,
                counters.snapshotListingsWritten,
                counters.hydratedCatalogItems,
                counters.skippedListings,
                counters.failedListings,
                elapsedMillis(start)
        );
    }

    private void processListing(MarketplaceListing listing, CrawlCounters counters) {
        ExternalCatalogItem catalogItem = listing.getExternalCatalogItem();
        if (catalogItem == null) {
            counters.skippedListings++;
            log.warn("bricklink.pricing.crawl.skipped_missing_catalog marketplaceListingId={}", listing.getMarketplaceListingId());
            return;
        }

        PricingCrawlWorkItem workItem = startWorkItem(listing, catalogItem);
        ItemInventory inventory = itemInventoryDao.findByItemInventoryId(listing.getItemInventoryId()).orElse(null);
        String requestedCondition = bricklinkCondition(inventory);
        if (requestedCondition == null) {
            completeWorkItem(workItem, STATUS_SKIPPED_MISSING_CONDITION, "Missing item_inventory.new_or_used");
            counters.skippedListings++;
            return;
        }

        String itemNumber = clean(catalogItem.getExternalItemKey());
        if (itemNumber == null) {
            completeWorkItem(workItem, STATUS_SKIPPED_MISSING_ITEM_NUMBER, "Missing external_catalog_item.external_item_key");
            counters.skippedListings++;
            return;
        }

        Optional<Integer> resolvedItemId = resolveBricklinkInternalItemId(catalogItem, itemNumber, workItem, counters);
        if (resolvedItemId.isEmpty()) {
            counters.failedListings++;
            return;
        }

        crawlPricing(listing, catalogItem, inventory, workItem, requestedCondition, resolvedItemId.get(), counters);
    }

    private Optional<Integer> resolveBricklinkInternalItemId(
            ExternalCatalogItem catalogItem,
            String itemNumber,
            PricingCrawlWorkItem workItem,
            CrawlCounters counters
    ) {
        Optional<Integer> existingItemId = parseInternalItemId(catalogItem.getExternalUniqueKey());
        if (existingItemId.isPresent()) {
            return existingItemId;
        }

        try {
            Optional<Item> foundItem = bricklinkAjaxClient.findCatalogItem(itemNumber, properties.effectiveCatalogItemType());
            if (foundItem.isEmpty()) {
                completeWorkItem(workItem, STATUS_FAILED_ITEM_ID_LOOKUP_NO_MATCH, "No exact BrickLink catalog item match for " + itemNumber);
                return Optional.empty();
            }

            Integer itemId = foundItem.get().getIdItem();
            catalogItem.setExternalUniqueKey(String.valueOf(itemId));
            externalCatalogItemDao.update(catalogItem);
            counters.hydratedCatalogItems++;
            return Optional.of(itemId);
        } catch (BricklinkAjaxClientException e) {
            String status = e.getMessage() != null && e.getMessage().contains("Ambiguous")
                    ? STATUS_FAILED_ITEM_ID_LOOKUP_AMBIGUOUS
                    : STATUS_FAILED_ITEM_ID_LOOKUP_HTTP_ERROR;
            completeWorkItem(workItem, status, e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            completeWorkItem(workItem, STATUS_FAILED_ITEM_ID_LOOKUP_HTTP_ERROR, e.getMessage());
            return Optional.empty();
        }
    }

    private void crawlPricing(
            MarketplaceListing listing,
            ExternalCatalogItem catalogItem,
            ItemInventory inventory,
            PricingCrawlWorkItem workItem,
            String requestedCondition,
            Integer itemId,
            CrawlCounters counters
    ) {
        Map<String, Object> requestParameters = Map.of(
                "itemid", itemId,
                "cond", requestedCondition,
                "rpp", properties.effectiveResultsPerPage(),
                "iconly", 0
        );
        workItem.setSourceRequestUrl(CATALOG_ITEMS_FOR_SALE_PATH);
        workItem.setSourceRequestParameters(writeJson(requestParameters));
        pricingCrawlWorkItemDao.update(workItem);

        try {
            CatalogItemsForSaleResult result = bricklinkAjaxClient.catalogItemsForSaleByInternalItemId(
                    itemId,
                    requestedCondition,
                    properties.effectiveResultsPerPage()
            );
            String rawPayload = writeJson(result);
            PricingSnapshot snapshot = pricingSnapshotDao.insert(PricingSnapshot.builder()
                    .pricingCrawlWorkItemId(workItem.getPricingCrawlWorkItemId())
                    .marketplaceListingId(listing.getMarketplaceListingId())
                    .externalCatalogItemId(catalogItem.getExternalCatalogItemId())
                    .sourceExternalServiceId(properties.getBricklinkExternalServiceId())
                    .sourceItemKey(catalogItem.getExternalItemKey())
                    .sourceUniqueKey(String.valueOf(itemId))
                    .itemConditionCode(requestedCondition)
                    .completenessCode(inventory == null ? null : inventory.getCompleteness())
                    .sourceRequestUrl(CATALOG_ITEMS_FOR_SALE_PATH)
                    .sourceRequestParameters(writeJson(requestParameters))
                    .rawPayloadHash(sha256(rawPayload))
                    .comparableCount(result.getList().size())
                    .capturedAt(now())
                    .build());
            counters.snapshotsWritten++;

            for (ItemForSale itemForSale : result.getList()) {
                pricingSnapshotListingDao.insert(snapshotListing(snapshot, itemForSale));
                counters.snapshotListingsWritten++;
            }

            completeWorkItem(workItem, STATUS_SUCCESS, null);
        } catch (JsonProcessingException e) {
            completeWorkItem(workItem, STATUS_FAILED_PRICING_PARSE_ERROR, e.getMessage());
            counters.failedListings++;
        } catch (RuntimeException e) {
            completeWorkItem(workItem, STATUS_FAILED_PRICING_HTTP_ERROR, e.getMessage());
            counters.failedListings++;
        }
    }

    private PricingSnapshotListing snapshotListing(PricingSnapshot snapshot, ItemForSale itemForSale) throws JsonProcessingException {
        return PricingSnapshotListing.builder()
                .pricingSnapshotId(snapshot.getPricingSnapshotId())
                .externalListingId(itemForSale.getIdInv() == null ? null : String.valueOf(itemForSale.getIdInv()))
                .sellerName(itemForSale.getStrSellerUsername())
                .sellerCountryCode(itemForSale.getStrSellerCountryCode())
                .itemConditionCode(itemForSale.getCodeNew())
                .completenessCode(itemForSale.getCodeComplete())
                .quantityAvailable(itemForSale.getN4Qty())
                .unitPrice(BigDecimal.valueOf(itemForSale.getSalePrice()))
                .currencyCode(currencyCode(itemForSale.getMDisplaySalePrice()))
                .description(itemForSale.getStrDesc())
                .extendedDescription(null)
                .sourceListingPayload(objectMapper.writeValueAsString(itemForSale))
                .build();
    }

    private PricingCrawlWorkItem startWorkItem(MarketplaceListing listing, ExternalCatalogItem catalogItem) {
        ZonedDateTime startedAt = now();
        return pricingCrawlWorkItemDao.insert(PricingCrawlWorkItem.builder()
                .marketplaceListingId(listing.getMarketplaceListingId())
                .externalCatalogItemId(catalogItem.getExternalCatalogItemId())
                .sourceExternalServiceId(properties.getBricklinkExternalServiceId())
                .workStatusCode(WORK_STATUS_STARTED)
                .attemptCount(1)
                .maxAttempts(properties.effectiveMaxAttempts())
                .nextAttemptAt(startedAt)
                .claimedAt(startedAt)
                .build());
    }

    private void completeWorkItem(PricingCrawlWorkItem workItem, String statusCode, String errorMessage) {
        workItem.setWorkStatusCode(statusCode);
        workItem.setCompletedAt(now());
        workItem.setLastErrorMessage(errorMessage);
        pricingCrawlWorkItemDao.update(workItem);
    }

    private String bricklinkCondition(ItemInventory inventory) {
        if (inventory == null || inventory.getNewOrUsed() == null) {
            return null;
        }
        return switch (inventory.getNewOrUsed().trim().toUpperCase()) {
            case "NEW", "N" -> "N";
            case "USED", "U" -> "U";
            default -> null;
        };
    }

    private Optional<Integer> parseInternalItemId(String externalUniqueKey) {
        try {
            return Optional.ofNullable(clean(externalUniqueKey)).map(Integer::valueOf);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String currencyCode(String displayPrice) {
        if (displayPrice == null || displayPrice.isBlank()) {
            return null;
        }
        String normalized = displayPrice.trim().toUpperCase();
        if (normalized.startsWith("US ")) {
            return "USD";
        }
        return normalized.substring(0, Math.min(3, normalized.length())).replaceAll("[^A-Z]", "");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize pricing crawl value", e);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private static final class CrawlCounters {
        private final int listingsSelected;
        private int snapshotsWritten;
        private int snapshotListingsWritten;
        private int hydratedCatalogItems;
        private int skippedListings;
        private int failedListings;

        private CrawlCounters(int listingsSelected) {
            this.listingsSelected = listingsSelected;
        }
    }
}
