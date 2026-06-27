package io.legohunter.ingress.source.bricklink.pricing;

public record BricklinkPricingCrawlResult(
        String outcome,
        int listingsSelected,
        int workItemsScheduled,
        int workItemsClaimed,
        int staleWorkItemsRequeued,
        int snapshotsWritten,
        int zeroComparableSnapshotsWritten,
        int snapshotListingsWritten,
        int hydratedCatalogItems,
        int catalogItemLookupNoMatches,
        int catalogItemLookupAmbiguousMatches,
        int catalogItemLookupFailures,
        int skippedListings,
        int failedListings,
        long elapsedMillis
) {
    public static BricklinkPricingCrawlResult noWork(long elapsedMillis) {
        return new BricklinkPricingCrawlResult("NO_WORK", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, elapsedMillis);
    }
}
