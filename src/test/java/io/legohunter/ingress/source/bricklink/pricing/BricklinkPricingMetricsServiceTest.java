package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingCrawlWorkItemDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BricklinkPricingMetricsServiceTest {
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PricingCrawlWorkItemDao pricingCrawlWorkItemDao = mock(PricingCrawlWorkItemDao.class);
    private final PricingDecisionDao pricingDecisionDao = mock(PricingDecisionDao.class);
    private final BricklinkPricingCrawlProperties crawlProperties = new BricklinkPricingCrawlProperties();
    private final BricklinkPricingMetricsService metricsService = new BricklinkPricingMetricsService(
            meterRegistry,
            pricingCrawlWorkItemDao,
            pricingDecisionDao,
            crawlProperties
    );

    @Test
    void recordsPricingCrawlMetricsAndCurrentWorkItemGauges() {
        crawlProperties.setClaimStaleAfter(Duration.ofHours(2));
        when(pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING)).thenReturn(7L);
        when(pricingCrawlWorkItemDao.countDueByWorkStatusCode(eq(BricklinkPricingCrawlService.STATUS_PENDING), any())).thenReturn(2L);
        when(pricingCrawlWorkItemDao.countRetryableByWorkStatusCode(BricklinkPricingCrawlService.STATUS_PENDING)).thenReturn(3L);
        when(pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_CLAIMED)).thenReturn(1L);
        when(pricingCrawlWorkItemDao.countStaleClaimed(eq(BricklinkPricingCrawlService.STATUS_CLAIMED), any())).thenReturn(1L);
        when(pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SUCCEEDED)).thenReturn(11L);
        when(pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_FAILED_PRICING_HTTP_ERROR)).thenReturn(4L);
        when(pricingCrawlWorkItemDao.countByWorkStatusCode(BricklinkPricingCrawlService.STATUS_SKIPPED_MISSING_CONDITION)).thenReturn(5L);

        metricsService.recordCrawl(new BricklinkPricingCrawlResult(
                "SUCCESS",
                4,
                3,
                1,
                1,
                1,
                99,
                1,
                2,
                1,
                250
        ));

        assertThat(counter("bricklink_pricing_crawl_job", "outcome", "success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_pricing_crawl_job_duration", "outcome", "success")).isOne();
        assertThat(counter("bricklink_pricing_crawl_listing", "result", "selected")).isEqualTo(4.0d);
        assertThat(counter("bricklink_pricing_crawl_work_item", "result", "scheduled")).isEqualTo(3.0d);
        assertThat(counter("bricklink_pricing_crawl_snapshot", "result", "snapshot_listing")).isEqualTo(99.0d);
        assertThat(gauge("bricklink_pricing_crawl_work_item_current", "state", "pending")).isEqualTo(7.0d);
        assertThat(gauge("bricklink_pricing_crawl_work_item_current", "state", "due")).isEqualTo(2.0d);
        assertThat(gauge("bricklink_pricing_crawl_work_item_current", "state", "retryable")).isEqualTo(3.0d);
        assertThat(gauge("bricklink_pricing_crawl_work_item_current", "state", "failed")).isEqualTo(4.0d);
        assertThat(gauge("bricklink_pricing_crawl_work_item_current", "state", "skipped")).isEqualTo(5.0d);
    }

    @Test
    void recordsPricingDecisionMetricsAndLatestDecisionGauges() {
        when(pricingDecisionDao.countLatestByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_PROPOSED)).thenReturn(8L);
        when(pricingDecisionDao.countLatestByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_FAILED)).thenReturn(2L);
        when(pricingDecisionDao.countLatestByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_SKIPPED)).thenReturn(1L);
        when(pricingDecisionDao.countLatestUnappliedByDecisionStatusCode(BricklinkPricingDecisionService.STATUS_PROPOSED)).thenReturn(6L);

        metricsService.recordDecision(new BricklinkPricingDecisionResult("PARTIAL_SUCCESS", 10, 9, 6, 1, 2, 125));

        assertThat(counter("bricklink_pricing_decision_job", "outcome", "partial_success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_pricing_decision_job_duration", "outcome", "partial_success")).isOne();
        assertThat(counter("bricklink_pricing_decision_listing", "result", "selected")).isEqualTo(10.0d);
        assertThat(counter("bricklink_pricing_decision", "result", "written")).isEqualTo(9.0d);
        assertThat(counter("bricklink_pricing_decision", "result", "proposed")).isEqualTo(6.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "proposed", "unapplied_only", "false")).isEqualTo(8.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "failed", "unapplied_only", "false")).isEqualTo(2.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "skipped", "unapplied_only", "false")).isEqualTo(1.0d);
        assertThat(gauge("bricklink_pricing_decision_current", "status", "proposed", "unapplied_only", "true")).isEqualTo(6.0d);
    }

    @Test
    void recordsApplyReadinessMetrics() {
        metricsService.recordApplyReadiness(new BricklinkPricingApplyReadinessResult(
                "SUCCESS",
                12,
                7,
                1,
                1,
                1,
                1,
                1,
                75
        ));

        assertThat(counter("bricklink_pricing_apply_readiness_job", "outcome", "success")).isEqualTo(1.0d);
        assertThat(timerCount("bricklink_pricing_apply_readiness_job_duration", "outcome", "success")).isOne();
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "selected")).isEqualTo(12.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "ready_to_apply")).isEqualTo(7.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_fixed_price")).isEqualTo(1.0d);
        assertThat(counter("bricklink_pricing_apply_readiness_decision", "result", "skipped_below_minimum_delta")).isEqualTo(1.0d);
    }

    private double counter(String metricName, String tag, String value) {
        return meterRegistry.get(metricName).tag(tag, value).counter().count();
    }

    private double timerCount(String metricName, String tag, String value) {
        return meterRegistry.get(metricName).tag(tag, value).timer().count();
    }

    private double gauge(String metricName, String tag, String value) {
        return meterRegistry.get(metricName).tag(tag, value).gauge().value();
    }

    private double gauge(String metricName, String tag1, String value1, String tag2, String value2) {
        return meterRegistry.get(metricName).tag(tag1, value1).tag(tag2, value2).gauge().value();
    }
}
