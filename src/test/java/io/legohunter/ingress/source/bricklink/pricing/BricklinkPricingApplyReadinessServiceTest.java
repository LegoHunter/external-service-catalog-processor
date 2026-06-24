package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.PricingDecisionReview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyReadinessServiceTest {
    private PricingDecisionDao pricingDecisionDao;
    private BricklinkPricingApplyReadinessProperties properties;
    private BricklinkPricingApplyReadinessService service;

    @BeforeEach
    void setUp() {
        pricingDecisionDao = mock(PricingDecisionDao.class);
        properties = new BricklinkPricingApplyReadinessProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        service = new BricklinkPricingApplyReadinessService(properties, pricingDecisionDao);
    }

    @Test
    void runOnceReturnsNoWorkWhenNoLatestProposedDecisionsExist() {
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                2, "ACTIVE", "PROPOSED", 10
        )).thenReturn(Set.of());

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        assertThat(result.decisionsSelected()).isZero();
        assertThat(result.readyToApply()).isZero();
        verify(pricingDecisionDao).findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                2, "ACTIVE", "PROPOSED", 10
        );
    }

    @Test
    void runOnceCountsReadyDecisionWhenPriceDeltaMeetsThreshold() {
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                2, "ACTIVE", "PROPOSED", 10
        )).thenReturn(Set.of(review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MEAN_PLUS_STDDEV)));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.decisionsSelected()).isOne();
        assertThat(result.readyToApply()).isOne();
        assertThat(result.skippedFixedPrice()).isZero();
        assertThat(result.skippedMissingPrice()).isZero();
        assertThat(result.skippedCurrencyMismatch()).isZero();
        assertThat(result.skippedIneligibleReason()).isZero();
        assertThat(result.skippedBelowMinimumDelta()).isZero();
    }

    @Test
    void runOnceSeparatesAllSkipBuckets() {
        PricingDecisionReview fixedPrice = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MEAN_PLUS_STDDEV);
        fixedPrice.setFixedPrice(true);
        PricingDecisionReview missingPrice = review("100.00", null, BricklinkPricingDecisionService.REASON_MEAN_PLUS_STDDEV);
        PricingDecisionReview currencyMismatch = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MEAN_PLUS_STDDEV);
        currencyMismatch.setDecisionCurrencyCode("CAD");
        PricingDecisionReview ineligibleReason = review("100.00", "95.00", BricklinkPricingDecisionService.REASON_OUTLIER_SPREAD_TOO_HIGH);
        PricingDecisionReview belowMinimumDelta = review("100.00", "100.00", BricklinkPricingDecisionService.REASON_MEAN_PLUS_STDDEV);

        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                2, "ACTIVE", "PROPOSED", 10
        )).thenReturn(Set.of(fixedPrice, missingPrice, currencyMismatch, ineligibleReason, belowMinimumDelta));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_READY_DECISIONS");
        assertThat(result.decisionsSelected()).isEqualTo(5);
        assertThat(result.readyToApply()).isZero();
        assertThat(result.skippedFixedPrice()).isOne();
        assertThat(result.skippedMissingPrice()).isOne();
        assertThat(result.skippedCurrencyMismatch()).isOne();
        assertThat(result.skippedIneligibleReason()).isOne();
        assertThat(result.skippedBelowMinimumDelta()).isOne();
    }

    @Test
    void runOnceUsesConfiguredCandidateSelectionAndMinimumDelta() {
        properties.setBricklinkExternalServiceId(7);
        properties.setActiveListingStatusCode("active");
        properties.setProposedDecisionStatusCode("proposed");
        properties.setBatchSize(0);
        properties.setMinimumPriceDelta(new BigDecimal("10.00"));
        when(pricingDecisionDao.findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                7, "ACTIVE", "PROPOSED", 1
        )).thenReturn(Set.of(review("100.00", "95.00", BricklinkPricingDecisionService.REASON_MEAN_PLUS_STDDEV)));

        BricklinkPricingApplyReadinessResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_READY_DECISIONS");
        assertThat(result.skippedBelowMinimumDelta()).isOne();
        verify(pricingDecisionDao).findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                7, "ACTIVE", "PROPOSED", 1
        );
    }

    private PricingDecisionReview review(String currentPrice, String finalPrice, String reasonCode) {
        return PricingDecisionReview.builder()
                .marketplaceListingId(10)
                .externalListingId("BL-INV-10")
                .listingStatusCode("ACTIVE")
                .currentUnitPrice(currentPrice == null ? null : new BigDecimal(currentPrice))
                .currentCurrencyCode("USD")
                .fixedPrice(false)
                .pricingDecisionId(900L)
                .decisionStatusCode("PROPOSED")
                .reasonCode(reasonCode)
                .finalPrice(finalPrice == null ? null : new BigDecimal(finalPrice))
                .currencyCode("USD")
                .decisionCurrencyCode("USD")
                .algorithmVersion("bricklink-competitive-v1")
                .build();
    }
}
