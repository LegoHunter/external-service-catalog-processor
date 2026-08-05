package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.BricklinkMarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.MarketplaceListingSyncRequestDao;
import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.BricklinkMarketplaceListing;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.MarketplaceListingSyncRequest;
import io.legohunter.data.dto.PricingApplyReadinessReview;
import io.legohunter.data.dto.PricingDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyServiceTest {
    private final BricklinkPricingApplyProperties properties = new BricklinkPricingApplyProperties();
    private final PricingApplyReadinessDao pricingApplyReadinessDao = mock(PricingApplyReadinessDao.class);
    private final PricingDecisionDao pricingDecisionDao = mock(PricingDecisionDao.class);
    private final MarketplaceListingDao marketplaceListingDao = mock(MarketplaceListingDao.class);
    private final BricklinkMarketplaceListingDao bricklinkMarketplaceListingDao = mock(BricklinkMarketplaceListingDao.class);
    private final MarketplaceListingSyncRequestDao marketplaceListingSyncRequestDao = mock(MarketplaceListingSyncRequestDao.class);
    private final BricklinkMarketplaceSyncProperties marketplaceSyncProperties = new BricklinkMarketplaceSyncProperties();
    private final BricklinkPricingApplyService service = new BricklinkPricingApplyService(
            properties,
            pricingApplyReadinessDao,
            pricingDecisionDao,
            marketplaceListingDao,
            bricklinkMarketplaceListingDao,
            marketplaceListingSyncRequestDao,
            marketplaceSyncProperties
    );

    @Test
    void dryRunSelectsReadyRowsWithoutMutatingAnything() {
        properties.setMode("DRY_RUN");
        arrangeReadyCandidate();

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkPricingApplyMode.DRY_RUN);
        assertThat(result.dryRunSelections()).isOne();
        assertThat(result.localPricesUpdated()).isZero();
        verify(marketplaceListingDao, never()).updateUnitPrice(any(), any(), any());
        verify(pricingDecisionDao, never()).markApplied(any(), any());
        verify(marketplaceListingSyncRequestDao, never()).upsert(any());
    }

    @Test
    void applyLocalAndEnqueueSyncUpdatesLocalGoldCopyAndCreatesSyncRequest() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        properties.setEnvironmentCode("sandbox");
        arrangeReadyCandidate();
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkListing()));
        when(marketplaceListingDao.updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(listing()));
        when(pricingDecisionDao.markApplied(eq(500L), any(ZonedDateTime.class))).thenReturn(Optional.of(decision()));
        when(marketplaceListingSyncRequestDao.upsert(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.mode()).isEqualTo(BricklinkPricingApplyMode.APPLY_LOCAL_AND_ENQUEUE_SYNC);
        assertThat(result.localPricesUpdated()).isOne();
        assertThat(result.syncRequestsEnqueued()).isOne();
        verify(marketplaceListingDao).updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class));
        verify(pricingDecisionDao).markApplied(eq(500L), any(ZonedDateTime.class));
        verify(marketplaceListingSyncRequestDao).upsert(any(MarketplaceListingSyncRequest.class));
    }

    @Test
    void applyLocalAndEnqueueSyncUpdatesLocalDraftWithoutRemoteSyncWhenBricklinkMappingIsMissing() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        arrangeReadyCandidate(listing("ACTIVE"));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.empty());
        when(marketplaceListingDao.updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(listing()));
        when(pricingDecisionDao.markApplied(eq(500L), any(ZonedDateTime.class))).thenReturn(Optional.of(decision()));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.failedRows()).isZero();
        assertThat(result.localPricesUpdated()).isOne();
        assertThat(result.syncRequestsEnqueued()).isZero();
        verify(marketplaceListingDao).updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class));
        verify(pricingDecisionDao).markApplied(eq(500L), any(ZonedDateTime.class));
        verify(marketplaceListingSyncRequestDao, never()).upsert(any());
    }

    @Test
    void applyLocalAndEnqueueSyncCreatesListingCreateRequestForPricedLocalDraft() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        marketplaceSyncProperties.setEnvironmentCode("sandbox");
        marketplaceSyncProperties.setProduction(false);
        marketplaceSyncProperties.setNonProdStockRoomId("C");
        arrangeReadyCandidate(listing("DRAFT"));
        when(bricklinkMarketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(bricklinkDraftMapping()));
        when(marketplaceListingDao.updateUnitPrice(eq(100), eq(new BigDecimal("219.00")), any(ZonedDateTime.class)))
                .thenReturn(Optional.of(listing("DRAFT")));
        when(pricingDecisionDao.markApplied(eq(500L), any(ZonedDateTime.class))).thenReturn(Optional.of(decision()));
        when(marketplaceListingSyncRequestDao.upsert(any(MarketplaceListingSyncRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.localPricesUpdated()).isOne();
        assertThat(result.syncRequestsEnqueued()).isOne();
        verify(marketplaceListingSyncRequestDao).upsert(any(MarketplaceListingSyncRequest.class));
    }

    @Test
    void runOnceSkipsAlreadyAppliedPricingDecisionWithoutFailingRow() {
        properties.setMode("APPLY_LOCAL_AND_ENQUEUE_SYNC");
        when(pricingApplyReadinessDao.findLatestReadyToApplyReviews(25)).thenReturn(Set.of(review()));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(listing()));
        when(pricingDecisionDao.findByPricingDecisionId(500L)).thenReturn(Optional.of(appliedDecision()));

        BricklinkPricingApplyResult result = service.runOnce();

        assertThat(result.skippedRows()).isOne();
        assertThat(result.failedRows()).isZero();
        assertThat(result.localPricesUpdated()).isZero();
        assertThat(result.syncRequestsEnqueued()).isZero();
        verify(marketplaceListingDao, never()).updateUnitPrice(any(), any(), any());
        verify(pricingDecisionDao, never()).markApplied(any(), any());
        verify(marketplaceListingSyncRequestDao, never()).upsert(any());
    }

    private void arrangeReadyCandidate() {
        arrangeReadyCandidate(listing());
    }

    private void arrangeReadyCandidate(MarketplaceListing listing) {
        when(pricingApplyReadinessDao.findLatestReadyToApplyReviews(25)).thenReturn(Set.of(review()));
        when(marketplaceListingDao.findByMarketplaceListingId(100)).thenReturn(Optional.of(listing));
        when(pricingDecisionDao.findByPricingDecisionId(500L)).thenReturn(Optional.of(decision()));
    }

    private PricingApplyReadinessReview review() {
        return PricingApplyReadinessReview.builder()
                .pricingApplyReadinessId(700L)
                .marketplaceListingId(100)
                .pricingDecisionId(500L)
                .readinessStatusCode("READY_TO_APPLY")
                .decisionReasonCode("MATCHED_LOWEST_COMPETITOR")
                .currentPrice(new BigDecimal("225.00"))
                .proposedPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .fixedPrice(false)
                .build();
    }

    private MarketplaceListing listing() {
        return listing("ACTIVE");
    }

    private MarketplaceListing listing(String statusCode) {
        return MarketplaceListing.builder()
                .marketplaceListingId(100)
                .itemInventoryId(200)
                .listingExternalServiceId(2)
                .listingStatusCode(statusCode)
                .unitPrice(new BigDecimal("225.00"))
                .currencyCode("USD")
                .fixedPrice(false)
                .build();
    }

    private PricingDecision decision() {
        return PricingDecision.builder()
                .pricingDecisionId(500L)
                .marketplaceListingId(100)
                .decisionStatusCode("PROPOSED")
                .finalPrice(new BigDecimal("219.00"))
                .currencyCode("USD")
                .build();
    }

    private PricingDecision appliedDecision() {
        PricingDecision decision = decision();
        decision.setAppliedAt(ZonedDateTime.parse("2026-07-29T12:00:00Z"));
        return decision;
    }

    private BricklinkMarketplaceListing bricklinkListing() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .bricklinkInventoryId(12345)
                .isStockRoom(true)
                .stockRoomId("A")
                .build();
    }

    private BricklinkMarketplaceListing bricklinkDraftMapping() {
        return BricklinkMarketplaceListing.builder()
                .marketplaceListingId(100)
                .isStockRoom(true)
                .stockRoomId("C")
                .build();
    }
}
