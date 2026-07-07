package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dto.PricingApplyReadinessReview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BricklinkPricingApplyPreviewService {
    static final int MAX_REPORT_LIMIT = 500;

    private final PricingApplyReadinessDao pricingApplyReadinessDao;

    public BricklinkPricingApplyPreviewReport buildPreview(String readinessStatusCode, String blockReasonCode, int limit) {
        String effectiveReadinessStatusCode = normalize(readinessStatusCode);
        String effectiveBlockReasonCode = normalize(blockReasonCode);
        Set<PricingApplyReadinessReview> reviews = pricingApplyReadinessDao.findLatestReviews(
                effectiveReadinessStatusCode,
                effectiveBlockReasonCode,
                effectiveLimit(limit)
        );
        return new BricklinkPricingApplyPreviewReport(
                now(),
                true,
                effectiveReadinessStatusCode,
                effectiveBlockReasonCode,
                reviews
        );
    }

    public BricklinkPricingDryRunApplySelectionReport buildDryRunApplySelection(int limit) {
        Set<PricingApplyReadinessReview> reviews = pricingApplyReadinessDao.findLatestReadyToApplyReviews(effectiveLimit(limit));
        return new BricklinkPricingDryRunApplySelectionReport(now(), true, reviews.size(), reviews);
    }

    private int effectiveLimit(int limit) {
        return Math.min(MAX_REPORT_LIMIT, Math.max(1, limit));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }
}
