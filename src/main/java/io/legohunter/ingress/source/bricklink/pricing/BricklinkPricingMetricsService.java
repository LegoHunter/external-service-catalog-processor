package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class BricklinkPricingMetricsService {
    private static final String OUTCOME_TAG = "outcome";
    private static final String RESULT_TAG = "result";
    private static final String STATE_TAG = "state";
    private static final String STATUS_TAG = "status";

    private final MeterRegistry meterRegistry;
    private final PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private final PricingDecisionDao pricingDecisionDao;
    private final BricklinkPricingCrawlProperties crawlProperties;
    private boolean gaugesRegistered;

    public void recordCrawl(BricklinkPricingCrawlResult result) {
        registerGauges();
        recordJob("bricklink_pricing_crawl_job", "bricklink_pricing_crawl_job_duration", result.outcome(), result.elapsedMillis());
        increment("bricklink_pricing_crawl_listing", "selected", result.listingsSelected());
        increment("bricklink_pricing_crawl_listing", "skipped", result.skippedListings());
        increment("bricklink_pricing_crawl_listing", "failed", result.failedListings());
        increment("bricklink_pricing_crawl_work_item", "scheduled", result.workItemsScheduled());
        increment("bricklink_pricing_crawl_work_item", "claimed", result.workItemsClaimed());
        increment("bricklink_pricing_crawl_work_item", "stale_requeued", result.staleWorkItemsRequeued());
        increment("bricklink_pricing_crawl_snapshot", "snapshot", result.snapshotsWritten());
        increment("bricklink_pricing_crawl_snapshot", "snapshot_listing", result.snapshotListingsWritten());
        increment("bricklink_pricing_crawl_catalog_item", "hydrated", result.hydratedCatalogItems());
    }

    public void recordDecision(BricklinkPricingDecisionResult result) {
        registerGauges();
        recordJob("bricklink_pricing_decision_job", "bricklink_pricing_decision_job_duration", result.outcome(), result.elapsedMillis());
        increment("bricklink_pricing_decision_listing", "selected", result.listingsSelected());
        increment("bricklink_pricing_decision", "written", result.decisionsWritten());
        increment("bricklink_pricing_decision", "proposed", result.proposedDecisions());
        increment("bricklink_pricing_decision", "skipped", result.skippedDecisions());
        increment("bricklink_pricing_decision", "failed", result.failedDecisions());
    }

    public void recordApplyReadiness(BricklinkPricingApplyReadinessResult result) {
        registerGauges();
        recordJob(
                "bricklink_pricing_apply_readiness_job",
                "bricklink_pricing_apply_readiness_job_duration",
                result.outcome(),
                result.elapsedMillis()
        );
        increment("bricklink_pricing_apply_readiness_decision", "selected", result.decisionsSelected());
        increment("bricklink_pricing_apply_readiness_decision", "ready_to_apply", result.readyToApply());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_fixed_price", result.skippedFixedPrice());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_missing_price", result.skippedMissingPrice());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_currency_mismatch", result.skippedCurrencyMismatch());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_ineligible_reason", result.skippedIneligibleReason());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_delta", result.skippedBelowMinimumDelta());
    }

    private synchronized void registerGauges() {
        if (gaugesRegistered) {
            return;
        }
        registerCrawlGauge("pending", () -> pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING));
        registerCrawlGauge("due", () -> pricingCrawlWorkItemDao.countDueByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING, now()));
        registerCrawlGauge("retryable", () -> pricingCrawlWorkItemDao.countRetryableByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING));
        registerCrawlGauge("claimed", () -> pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_CLAIMED));
        registerCrawlGauge("stale_claimed", () -> pricingCrawlWorkItemDao.countStaleClaimed(
                BricklinkPricingCrawlService.STATUS_CLAIMED,
                now().minus(crawlProperties.effectiveClaimStaleAfter())
        ));
        registerCrawlGauge("succeeded", () -> pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SUCCEEDED));
        registerCrawlGauge("failed", this::failedCrawlWorkItemCount);
        registerCrawlGauge("skipped", this::skippedCrawlWorkItemCount);

        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_PROPOSED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_FAILED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_SKIPPED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_PROPOSED, true);
        gaugesRegistered = true;
    }

    private void registerCrawlGauge(String state, CountSupplier supplier) {
        Gauge.builder("bricklink_pricing_crawl_work_item_current", this, ignored -> supplier.count())
                .description("Current BrickLink pricing crawl work item count by operational state.")
                .tag(STATE_TAG, state)
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerDecisionGauge(String status, boolean unappliedOnly) {
        Gauge.builder("bricklink_pricing_decision_current", this, ignored -> decisionCount(status, unappliedOnly))
                .description("Current latest BrickLink pricing decision count by status.")
                .tag(STATUS_TAG, status.toLowerCase())
                .tag("unapplied_only", Boolean.toString(unappliedOnly))
                .strongReference(true)
                .register(meterRegistry);
    }

    private long decisionCount(String status, boolean unappliedOnly) {
        if (unappliedOnly) {
            return pricingDecisionDao.countLatestUnappliedByDecisionStatusCode(status);
        }
        return pricingDecisionDao.countLatestByDecisionStatusCode(status);
    }

    private long failedCrawlWorkItemCount() {
        return pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_FAILED_ITEM_ID_LOOKUP_NO_MATCH)
                + pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_FAILED_ITEM_ID_LOOKUP_AMBIGUOUS)
                + pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_FAILED_ITEM_ID_LOOKUP_HTTP_ERROR)
                + pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_FAILED_PRICING_HTTP_ERROR)
                + pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_FAILED_PRICING_PARSE_ERROR);
    }

    private long skippedCrawlWorkItemCount() {
        return pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SKIPPED_MISSING_ITEM_NUMBER)
                + pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SKIPPED_MISSING_CONDITION)
                + pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SKIPPED_MISSING_LISTING)
                + pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SKIPPED_MISSING_CATALOG);
    }

    private void recordJob(String counterName, String timerName, String outcome, long elapsedMillis) {
        Counter.builder(counterName)
                .tag(OUTCOME_TAG, tagValue(outcome))
                .register(meterRegistry)
                .increment();

        Timer.builder(timerName)
                .tag(OUTCOME_TAG, tagValue(outcome))
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    private void increment(String metricName, String result, double count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(metricName)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment(count);
    }

    private String tagValue(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    @FunctionalInterface
    private interface CountSupplier {
        long count();
    }
}
