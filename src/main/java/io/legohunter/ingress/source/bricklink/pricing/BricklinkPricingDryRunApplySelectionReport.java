package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dto.PricingApplyReadinessReview;

import java.time.ZonedDateTime;
import java.util.Set;

public record BricklinkPricingDryRunApplySelectionReport(
        ZonedDateTime generatedAt,
        boolean dryRun,
        int selectedCount,
        Set<PricingApplyReadinessReview> selectedReadinessReviews
) {
}
