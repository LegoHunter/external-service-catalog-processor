package io.legohunter.ingress.source.bricklink.pricing;

public record BricklinkPricingCrawlResult(
        String outcome,
        int listingsSelected,
        int snapshotsWritten,
        int snapshotListingsWritten,
        int hydratedCatalogItems,
        int skippedListings,
        int failedListings,
        long elapsedMillis
) {
    public static BricklinkPricingCrawlResult noWork(long elapsedMillis) {
        return new BricklinkPricingCrawlResult("NO_WORK", 0, 0, 0, 0, 0, 0, elapsedMillis);
    }
}
