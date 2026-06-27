package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dto.PricingCrawlWorkItemDuplicate;
import io.legohunter.data.dto.PricingCrawlWorkItemMaintenanceSummary;
import io.legohunter.data.dto.PricingHydrationGap;

import java.time.ZonedDateTime;
import java.util.Set;

public record BricklinkPricingMaintenanceReport(
        ZonedDateTime generatedAt,
        boolean dryRun,
        PricingCrawlWorkItemMaintenanceSummary workItemSummary,
        Set<PricingCrawlWorkItemDuplicate> duplicateWorkItems,
        Set<PricingHydrationGap> hydrationGaps
) {
}
