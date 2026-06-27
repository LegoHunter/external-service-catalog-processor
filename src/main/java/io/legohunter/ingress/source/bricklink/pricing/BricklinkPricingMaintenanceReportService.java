package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dto.PricingCrawlWorkItemDuplicate;
import io.legohunter.data.dto.PricingCrawlWorkItemMaintenanceSummary;
import io.legohunter.data.dto.PricingHydrationGap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BricklinkPricingMaintenanceReportService {
    private final PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private final MarketplaceListingDao marketplaceListingDao;
    private final BricklinkPricingCrawlProperties crawlProperties;

    public BricklinkPricingMaintenanceReport buildReport(int limit) {
        int effectiveLimit = Math.max(1, limit);
        ZonedDateTime generatedAt = now();
        PricingCrawlWorkItemMaintenanceSummary summary = pricingCrawlWorkItemDao.summarizeMaintenance(
                BricklinkPricingCrawlService.STATUS_PENDING,
                BricklinkPricingCrawlService.STATUS_CLAIMED,
                BricklinkPricingCrawlService.STATUS_SUCCEEDED,
                generatedAt,
                generatedAt.minus(crawlProperties.effectiveClaimStaleAfter())
        );
        Set<PricingCrawlWorkItemDuplicate> duplicateWorkItems = pricingCrawlWorkItemDao.findDuplicateMarketplaceListingWorkItems(
                BricklinkPricingCrawlService.STATUS_PENDING,
                effectiveLimit
        );
        Set<PricingHydrationGap> hydrationGaps = marketplaceListingDao.findPricingHydrationGapsByListingExternalServiceIdAndListingStatusCode(
                crawlProperties.getBricklinkExternalServiceId(),
                crawlProperties.effectiveActiveListingStatusCode(),
                effectiveLimit
        );
        return new BricklinkPricingMaintenanceReport(generatedAt, true, summary, duplicateWorkItems, hydrationGaps);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }
}
