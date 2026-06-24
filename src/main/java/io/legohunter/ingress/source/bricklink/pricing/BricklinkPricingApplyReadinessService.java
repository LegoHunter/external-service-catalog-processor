package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.PricingDecisionReview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.apply-readiness", name = "enabled", havingValue = "true")
public class BricklinkPricingApplyReadinessService {
    private final BricklinkPricingApplyReadinessProperties properties;
    private final PricingDecisionDao pricingDecisionDao;

    public BricklinkPricingApplyReadinessResult runOnce() {
        long start = System.currentTimeMillis();
        Set<PricingDecisionReview> reviews = pricingDecisionDao
                .findLatestUnappliedDecisionReviewsByListingExternalServiceIdAndListingStatusCodeAndDecisionStatusCode(
                        properties.getBricklinkExternalServiceId(),
                        properties.effectiveActiveListingStatusCode(),
                        properties.effectiveProposedDecisionStatusCode(),
                        properties.effectiveBatchSize()
                );

        if (reviews.isEmpty()) {
            return BricklinkPricingApplyReadinessResult.noWork(elapsedMillis(start));
        }

        ApplyReadinessCounters counters = new ApplyReadinessCounters(reviews.size());
        Set<String> eligibleReasonCodes = properties.effectiveApplyEligibleReasonCodes();
        BigDecimal minimumPriceDelta = properties.effectiveMinimumPriceDelta();
        for (PricingDecisionReview review : reviews) {
            ApplyReadinessStatus status = status(review, eligibleReasonCodes, minimumPriceDelta);
            counters.record(status);
            if (status == ApplyReadinessStatus.READY_TO_APPLY) {
                log.info(
                        "bricklink.pricing.apply_readiness.ready marketplaceListingId={} pricingDecisionId={} externalListingId={} currentPrice={} proposedPrice={} delta={} currencyCode={} reasonCode={} algorithmVersion={}",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId(),
                        review.getExternalListingId(),
                        money(review.getCurrentUnitPrice()),
                        money(review.getFinalPrice()),
                        money(delta(review)),
                        decisionCurrencyCode(review),
                        review.getReasonCode(),
                        review.getAlgorithmVersion()
                );
            } else {
                log.debug(
                        "bricklink.pricing.apply_readiness.skipped marketplaceListingId={} pricingDecisionId={} status={} reasonCode={}",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId(),
                        status,
                        review.getReasonCode()
                );
            }
        }

        return counters.result(elapsedMillis(start));
    }

    private ApplyReadinessStatus status(
            PricingDecisionReview review,
            Set<String> eligibleReasonCodes,
            BigDecimal minimumPriceDelta
    ) {
        if (Boolean.TRUE.equals(review.getFixedPrice())) {
            return ApplyReadinessStatus.SKIPPED_FIXED_PRICE;
        }
        if (review.getCurrentUnitPrice() == null || review.getFinalPrice() == null) {
            return ApplyReadinessStatus.SKIPPED_MISSING_PRICE;
        }
        if (!sameCurrency(review)) {
            return ApplyReadinessStatus.SKIPPED_CURRENCY_MISMATCH;
        }
        if (!eligibleReasonCodes.contains(cleanReasonCode(review.getReasonCode()))) {
            return ApplyReadinessStatus.SKIPPED_INELIGIBLE_REASON;
        }
        if (delta(review).compareTo(minimumPriceDelta) < 0) {
            return ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_DELTA;
        }
        return ApplyReadinessStatus.READY_TO_APPLY;
    }

    private boolean sameCurrency(PricingDecisionReview review) {
        return currentCurrencyCode(review).equals(decisionCurrencyCode(review));
    }

    private String currentCurrencyCode(PricingDecisionReview review) {
        return currencyCode(review.getCurrentCurrencyCode());
    }

    private String decisionCurrencyCode(PricingDecisionReview review) {
        return currencyCode(review.getDecisionCurrencyCode() == null ? review.getCurrencyCode() : review.getDecisionCurrencyCode());
    }

    private String currencyCode(String value) {
        if (value == null || value.isBlank()) {
            return "USD";
        }
        return value.trim().toUpperCase();
    }

    private String cleanReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "";
        }
        return reasonCode.trim().toUpperCase();
    }

    private BigDecimal delta(PricingDecisionReview review) {
        return review.getFinalPrice().subtract(review.getCurrentUnitPrice()).abs();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private enum ApplyReadinessStatus {
        READY_TO_APPLY,
        SKIPPED_FIXED_PRICE,
        SKIPPED_MISSING_PRICE,
        SKIPPED_CURRENCY_MISMATCH,
        SKIPPED_INELIGIBLE_REASON,
        SKIPPED_BELOW_MINIMUM_DELTA
    }

    private static final class ApplyReadinessCounters {
        private final int decisionsSelected;
        private int readyToApply;
        private int skippedFixedPrice;
        private int skippedMissingPrice;
        private int skippedCurrencyMismatch;
        private int skippedIneligibleReason;
        private int skippedBelowMinimumDelta;

        private ApplyReadinessCounters(int decisionsSelected) {
            this.decisionsSelected = decisionsSelected;
        }

        private void record(ApplyReadinessStatus status) {
            switch (status) {
                case READY_TO_APPLY -> readyToApply++;
                case SKIPPED_FIXED_PRICE -> skippedFixedPrice++;
                case SKIPPED_MISSING_PRICE -> skippedMissingPrice++;
                case SKIPPED_CURRENCY_MISMATCH -> skippedCurrencyMismatch++;
                case SKIPPED_INELIGIBLE_REASON -> skippedIneligibleReason++;
                case SKIPPED_BELOW_MINIMUM_DELTA -> skippedBelowMinimumDelta++;
            }
        }

        private BricklinkPricingApplyReadinessResult result(long elapsedMillis) {
            return new BricklinkPricingApplyReadinessResult(
                    readyToApply > 0 ? "SUCCESS" : "NO_READY_DECISIONS",
                    decisionsSelected,
                    readyToApply,
                    skippedFixedPrice,
                    skippedMissingPrice,
                    skippedCurrencyMismatch,
                    skippedIneligibleReason,
                    skippedBelowMinimumDelta,
                    elapsedMillis
            );
        }
    }
}
