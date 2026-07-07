package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dto.PricingApplyReadinessReview;

import java.time.ZonedDateTime;
import java.util.Set;

public record BricklinkPricingApplyPreviewReport(
        ZonedDateTime generatedAt,
        boolean dryRun,
        String readinessStatusCode,
        String blockReasonCode,
        Set<PricingApplyReadinessReview> readinessReviews
) {
}
