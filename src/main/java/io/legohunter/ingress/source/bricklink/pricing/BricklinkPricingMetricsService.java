package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingApplyReadinessDao;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class BricklinkPricingMetricsService {
    private static final String OUTCOME_TAG = "outcome";
    private static final String RESULT_TAG = "result";
    private static final String STATE_TAG = "state";
    private static final String STATUS_TAG = "status";
    private static final List<String> APPLY_READINESS_STATUS_CODES = List.of(
            "READY_TO_APPLY",
            "BLOCKED_FIXED_PRICE",
            "BLOCKED_MISSING_CURRENT_PRICE",
            "BLOCKED_MISSING_FINAL_PRICE",
            "BLOCKED_CURRENCY_MISMATCH",
            "BLOCKED_UNSUPPORTED_DECISION_STATUS",
            "BLOCKED_REASON_CODE",
            "BLOCKED_INELIGIBLE_REASON",
            "BLOCKED_BELOW_MINIMUM_DELTA",
            "BLOCKED_BELOW_MINIMUM_DELTA_PERCENT",
            "BLOCKED_BELOW_MINIMUM_CONFIDENCE",
            "BLOCKED_BELOW_MINIMUM_COMPARABLE_COUNT",
            "BLOCKED_ABOVE_MAXIMUM_ABSOLUTE_DELTA",
            "BLOCKED_ABOVE_MAXIMUM_PERCENT_DELTA",
            "BLOCKED_STALE_DECISION"
    );
    private static final List<String> APPLY_READINESS_BLOCK_REASON_CODES = List.of(
            "FIXED_PRICE",
            "MISSING_CURRENT_PRICE",
            "MISSING_FINAL_PRICE",
            "CURRENCY_MISMATCH",
            "UNSUPPORTED_DECISION_STATUS",
            "BLOCKED_REASON_CODE",
            "INELIGIBLE_REASON",
            "BELOW_MINIMUM_DELTA",
            "BELOW_MINIMUM_DELTA_PERCENT",
            "BELOW_MINIMUM_CONFIDENCE",
            "BELOW_MINIMUM_COMPARABLE_COUNT",
            "ABOVE_MAXIMUM_ABSOLUTE_DELTA",
            "ABOVE_MAXIMUM_PERCENT_DELTA",
            "STALE_DECISION"
    );

    private final MeterRegistry meterRegistry;
    private final PricingCrawlWorkItemDao pricingCrawlWorkItemDao;
    private final PricingDecisionDao pricingDecisionDao;
    private final PricingApplyReadinessDao pricingApplyReadinessDao;
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
        increment("bricklink_pricing_crawl_snapshot", "zero_comparable_snapshot", result.zeroComparableSnapshotsWritten());
        increment("bricklink_pricing_crawl_snapshot", "snapshot_listing", result.snapshotListingsWritten());
        increment("bricklink_pricing_crawl_catalog_item", "hydrated", result.hydratedCatalogItems());
        increment("bricklink_pricing_crawl_catalog_item", "no_match", result.catalogItemLookupNoMatches());
        increment("bricklink_pricing_crawl_catalog_item", "ambiguous_match", result.catalogItemLookupAmbiguousMatches());
        increment("bricklink_pricing_crawl_catalog_item", "failed_request", result.catalogItemLookupFailures());
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
        increment("bricklink_pricing_apply_readiness_decision", "skipped_missing_current_price", result.skippedMissingCurrentPrice());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_missing_final_price", result.skippedMissingFinalPrice());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_currency_mismatch", result.skippedCurrencyMismatch());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_unsupported_decision_status", result.skippedUnsupportedDecisionStatus());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_blocked_reason_code", result.skippedBlockedReasonCode());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_ineligible_reason", result.skippedIneligibleReason());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_delta", result.skippedBelowMinimumDelta());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_delta_percent", result.skippedBelowMinimumDeltaPercent());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_confidence", result.skippedBelowMinimumConfidence());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_below_minimum_comparable_count", result.skippedBelowMinimumComparableCount());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_above_maximum_absolute_delta", result.skippedAboveMaximumAbsoluteDelta());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_above_maximum_percent_delta", result.skippedAboveMaximumPercentDelta());
        increment("bricklink_pricing_apply_readiness_decision", "skipped_stale_decision", result.skippedStaleDecision());
    }

    private synchronized void registerGauges() {
        if (gaugesRegistered) {
            return;
        }
        registerCrawlGauge("pending", () -> pricingCrawlWorkItemDao.countLatestByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING));
        registerCrawlGauge("due", () -> pricingCrawlWorkItemDao.countLatestDueByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING, now()));
        registerCrawlGauge("retryable", () -> pricingCrawlWorkItemDao.countLatestRetryableByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING));
        registerCrawlGauge("claimed", () -> pricingCrawlWorkItemDao.countLatestByWorkStatusCode(BricklinkPricingCrawlService.STATUS_CLAIMED));
        registerCrawlGauge("stale_claimed", () -> pricingCrawlWorkItemDao.countLatestStaleClaimed(
                BricklinkPricingCrawlService.STATUS_CLAIMED,
                now().minus(crawlProperties.effectiveClaimStaleAfter())
        ));
        registerCrawlGauge("succeeded", () -> pricingCrawlWorkItemDao.countLatestByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SUCCEEDED));
        registerCrawlGauge("failed", () -> pricingCrawlWorkItemDao.countLatestByWorkStatusPattern("FAILED%"));
        registerCrawlGauge("skipped", () -> pricingCrawlWorkItemDao.countLatestByWorkStatusPattern("SKIPPED%"));

        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_PROPOSED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_FAILED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_SKIPPED, false);
        registerDecisionGauge(BricklinkPricingDecisionService.STATUS_PROPOSED, true);
        APPLY_READINESS_STATUS_CODES.forEach(this::registerApplyReadinessGauge);
        APPLY_READINESS_BLOCK_REASON_CODES.forEach(this::registerApplyReadinessBlockReasonGauge);
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

    private void registerApplyReadinessGauge(String status) {
        Gauge.builder(
                        "bricklink_pricing_apply_readiness_current",
                        this,
                        ignored -> pricingApplyReadinessDao.countLatestByReadinessStatusCode(status)
                )
                .description("Current latest BrickLink pricing apply-readiness count by status.")
                .tag(STATUS_TAG, status.toLowerCase())
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerApplyReadinessBlockReasonGauge(String reason) {
        Gauge.builder(
                        "bricklink_pricing_apply_readiness_block_reason_current",
                        this,
                        ignored -> pricingApplyReadinessDao.countLatestByBlockReasonCode(reason)
                )
                .description("Current latest BrickLink pricing apply-readiness count by block reason.")
                .tag("reason", reason.toLowerCase())
                .strongReference(true)
                .register(meterRegistry);
    }

    private long decisionCount(String status, boolean unappliedOnly) {
        if (unappliedOnly) {
            return pricingDecisionDao.countLatestUnappliedByDecisionStatusCode(status);
        }
        return pricingDecisionDao.countLatestByDecisionStatusCode(status);
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
