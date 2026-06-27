package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dto.PricingDecisionReview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        for (PricingDecisionReview review : reviews) {
            ApplyReadinessStatus status = status(review);
            counters.record(status);
            if (status == ApplyReadinessStatus.READY_TO_APPLY) {
                log.info(
                        "bricklink.pricing.apply_readiness.ready marketplaceListingId={} pricingDecisionId={} externalListingId={} currentPrice={} proposedPrice={} delta={} currencyCode={} reasonCode={} algorithmVersion={} confidence={} comparableCount={}",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId(),
                        review.getExternalListingId(),
                        money(review.getCurrentUnitPrice()),
                        money(review.getFinalPrice()),
                        money(delta(review)),
                        decisionCurrencyCode(review),
                        review.getReasonCode(),
                        review.getAlgorithmVersion(),
                        review.getConfidence(),
                        review.getComparableCount()
                );
            } else {
                log.info(
                        "bricklink.pricing.apply_readiness.skipped marketplaceListingId={} pricingDecisionId={} status={} reasonCode={} currentPrice={} proposedPrice={} confidence={} comparableCount={}",
                        review.getMarketplaceListingId(),
                        review.getPricingDecisionId(),
                        status,
                        review.getReasonCode(),
                        money(review.getCurrentUnitPrice()),
                        money(review.getFinalPrice()),
                        review.getConfidence(),
                        review.getComparableCount()
                );
            }
        }

        return counters.result(elapsedMillis(start));
    }

    private ApplyReadinessStatus status(PricingDecisionReview review) {
        if (Boolean.TRUE.equals(review.getFixedPrice())) {
            return ApplyReadinessStatus.SKIPPED_FIXED_PRICE;
        }
        if (!properties.effectiveProposedDecisionStatusCode().equals(cleanStatusCode(review.getDecisionStatusCode()))) {
            return ApplyReadinessStatus.SKIPPED_UNSUPPORTED_DECISION_STATUS;
        }
        if (review.getCurrentUnitPrice() == null) {
            return ApplyReadinessStatus.SKIPPED_MISSING_CURRENT_PRICE;
        }
        if (review.getFinalPrice() == null) {
            return ApplyReadinessStatus.SKIPPED_MISSING_FINAL_PRICE;
        }
        if (!sameCurrency(review)) {
            return ApplyReadinessStatus.SKIPPED_CURRENCY_MISMATCH;
        }

        String reasonCode = cleanReasonCode(review.getReasonCode());
        if (properties.effectiveBlockedReasonCodes().contains(reasonCode)) {
            return ApplyReadinessStatus.SKIPPED_BLOCKED_REASON_CODE;
        }
        if (!properties.effectiveApplyEligibleReasonCodes().contains(reasonCode)) {
            return ApplyReadinessStatus.SKIPPED_INELIGIBLE_REASON;
        }

        BigDecimal absoluteDelta = delta(review);
        if (absoluteDelta.compareTo(properties.effectiveMinimumPriceDelta()) < 0) {
            return ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_DELTA;
        }
        if (confidence(review).compareTo(properties.effectiveMinimumConfidence()) < 0) {
            return ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_CONFIDENCE;
        }
        if (comparableCount(review) < properties.effectiveMinimumComparableCount()) {
            return ApplyReadinessStatus.SKIPPED_BELOW_MINIMUM_COMPARABLE_COUNT;
        }

        BigDecimal maximumAbsoluteDelta = properties.effectiveMaximumAbsoluteDelta();
        if (maximumAbsoluteDelta != null && absoluteDelta.compareTo(maximumAbsoluteDelta) > 0) {
            return ApplyReadinessStatus.SKIPPED_ABOVE_MAXIMUM_ABSOLUTE_DELTA;
        }

        BigDecimal maximumPercentDelta = properties.effectiveMaximumPercentDelta();
        if (maximumPercentDelta != null) {
            if (review.getCurrentUnitPrice().signum() <= 0) {
                return ApplyReadinessStatus.SKIPPED_MISSING_CURRENT_PRICE;
            }
            if (percentDelta(review, absoluteDelta).compareTo(maximumPercentDelta) > 0) {
                return ApplyReadinessStatus.SKIPPED_ABOVE_MAXIMUM_PERCENT_DELTA;
            }
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

    private String cleanStatusCode(String statusCode) {
        if (statusCode == null || statusCode.isBlank()) {
            return "";
        }
        return statusCode.trim().toUpperCase();
    }

    private BigDecimal delta(PricingDecisionReview review) {
        return review.getFinalPrice().subtract(review.getCurrentUnitPrice()).abs();
    }

    private BigDecimal percentDelta(PricingDecisionReview review, BigDecimal absoluteDelta) {
        return absoluteDelta.divide(review.getCurrentUnitPrice().abs(), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal confidence(PricingDecisionReview review) {
        return review.getConfidence() == null ? BigDecimal.ZERO : review.getConfidence();
    }

    private int comparableCount(PricingDecisionReview review) {
        return review.getComparableCount() == null ? 0 : review.getComparableCount();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private enum ApplyReadinessStatus {
        READY_TO_APPLY,
        SKIPPED_FIXED_PRICE,
        SKIPPED_MISSING_CURRENT_PRICE,
        SKIPPED_MISSING_FINAL_PRICE,
        SKIPPED_CURRENCY_MISMATCH,
        SKIPPED_UNSUPPORTED_DECISION_STATUS,
        SKIPPED_BLOCKED_REASON_CODE,
        SKIPPED_INELIGIBLE_REASON,
        SKIPPED_BELOW_MINIMUM_DELTA,
        SKIPPED_BELOW_MINIMUM_CONFIDENCE,
        SKIPPED_BELOW_MINIMUM_COMPARABLE_COUNT,
        SKIPPED_ABOVE_MAXIMUM_ABSOLUTE_DELTA,
        SKIPPED_ABOVE_MAXIMUM_PERCENT_DELTA
    }

    private static final class ApplyReadinessCounters {
        private final int decisionsSelected;
        private int readyToApply;
        private int skippedFixedPrice;
        private int skippedMissingCurrentPrice;
        private int skippedMissingFinalPrice;
        private int skippedCurrencyMismatch;
        private int skippedUnsupportedDecisionStatus;
        private int skippedBlockedReasonCode;
        private int skippedIneligibleReason;
        private int skippedBelowMinimumDelta;
        private int skippedBelowMinimumConfidence;
        private int skippedBelowMinimumComparableCount;
        private int skippedAboveMaximumAbsoluteDelta;
        private int skippedAboveMaximumPercentDelta;

        private ApplyReadinessCounters(int decisionsSelected) {
            this.decisionsSelected = decisionsSelected;
        }

        private void record(ApplyReadinessStatus status) {
            switch (status) {
                case READY_TO_APPLY -> readyToApply++;
                case SKIPPED_FIXED_PRICE -> skippedFixedPrice++;
                case SKIPPED_MISSING_CURRENT_PRICE -> skippedMissingCurrentPrice++;
                case SKIPPED_MISSING_FINAL_PRICE -> skippedMissingFinalPrice++;
                case SKIPPED_CURRENCY_MISMATCH -> skippedCurrencyMismatch++;
                case SKIPPED_UNSUPPORTED_DECISION_STATUS -> skippedUnsupportedDecisionStatus++;
                case SKIPPED_BLOCKED_REASON_CODE -> skippedBlockedReasonCode++;
                case SKIPPED_INELIGIBLE_REASON -> skippedIneligibleReason++;
                case SKIPPED_BELOW_MINIMUM_DELTA -> skippedBelowMinimumDelta++;
                case SKIPPED_BELOW_MINIMUM_CONFIDENCE -> skippedBelowMinimumConfidence++;
                case SKIPPED_BELOW_MINIMUM_COMPARABLE_COUNT -> skippedBelowMinimumComparableCount++;
                case SKIPPED_ABOVE_MAXIMUM_ABSOLUTE_DELTA -> skippedAboveMaximumAbsoluteDelta++;
                case SKIPPED_ABOVE_MAXIMUM_PERCENT_DELTA -> skippedAboveMaximumPercentDelta++;
            }
        }

        private BricklinkPricingApplyReadinessResult result(long elapsedMillis) {
            return new BricklinkPricingApplyReadinessResult(
                    readyToApply > 0 ? "SUCCESS" : "NO_READY_DECISIONS",
                    decisionsSelected,
                    readyToApply,
                    skippedFixedPrice,
                    skippedMissingCurrentPrice,
                    skippedMissingFinalPrice,
                    skippedCurrencyMismatch,
                    skippedUnsupportedDecisionStatus,
                    skippedBlockedReasonCode,
                    skippedIneligibleReason,
                    skippedBelowMinimumDelta,
                    skippedBelowMinimumConfidence,
                    skippedBelowMinimumComparableCount,
                    skippedAboveMaximumAbsoluteDelta,
                    skippedAboveMaximumPercentDelta,
                    elapsedMillis
            );
        }
    }
}
