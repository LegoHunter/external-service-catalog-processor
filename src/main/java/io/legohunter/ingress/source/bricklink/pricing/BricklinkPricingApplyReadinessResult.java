package io.legohunter.ingress.source.bricklink.pricing;

public record BricklinkPricingApplyReadinessResult(
        String outcome,
        int decisionsSelected,
        int readyToApply,
        int skippedFixedPrice,
        int skippedMissingCurrentPrice,
        int skippedMissingFinalPrice,
        int skippedCurrencyMismatch,
        int skippedUnsupportedDecisionStatus,
        int skippedBlockedReasonCode,
        int skippedIneligibleReason,
        int skippedBelowMinimumDelta,
        int skippedBelowMinimumConfidence,
        int skippedBelowMinimumComparableCount,
        int skippedAboveMaximumAbsoluteDelta,
        int skippedAboveMaximumPercentDelta,
        long elapsedMillis
) {
    public static BricklinkPricingApplyReadinessResult noWork(long elapsedMillis) {
        return new BricklinkPricingApplyReadinessResult(
                "NO_WORK",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                elapsedMillis
        );
    }
}
