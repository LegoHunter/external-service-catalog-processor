package io.legohunter.ingress.source.bricklink.pricing;

public record BricklinkPricingApplyReadinessResult(
        String outcome,
        int decisionsSelected,
        int readyToApply,
        int skippedFixedPrice,
        int skippedMissingPrice,
        int skippedCurrencyMismatch,
        int skippedIneligibleReason,
        int skippedBelowMinimumDelta,
        long elapsedMillis
) {
    public static BricklinkPricingApplyReadinessResult noWork(long elapsedMillis) {
        return new BricklinkPricingApplyReadinessResult("NO_WORK", 0, 0, 0, 0, 0, 0, 0, elapsedMillis);
    }
}
