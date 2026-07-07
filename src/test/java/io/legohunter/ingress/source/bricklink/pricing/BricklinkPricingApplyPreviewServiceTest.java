package io.legohunter.ingress.source.bricklink.pricing;

import io.legohunter.data.dao.PricingApplyReadinessDao;
import io.legohunter.data.dto.PricingApplyReadinessReview;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyPreviewServiceTest {
    private final PricingApplyReadinessDao pricingApplyReadinessDao = mock(PricingApplyReadinessDao.class);
    private final BricklinkPricingApplyPreviewService service = new BricklinkPricingApplyPreviewService(pricingApplyReadinessDao);

    @Test
    void buildPreviewNormalizesFiltersAndClampsLimit() {
        PricingApplyReadinessReview review = PricingApplyReadinessReview.builder()
                .readinessStatusCode("READY_TO_APPLY")
                .build();
        when(pricingApplyReadinessDao.findLatestReviews("READY_TO_APPLY", "STALE_DECISION", 500))
                .thenReturn(Set.of(review));

        BricklinkPricingApplyPreviewReport report = service.buildPreview(" ready_to_apply ", " stale_decision ", 999);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.readinessStatusCode()).isEqualTo("READY_TO_APPLY");
        assertThat(report.blockReasonCode()).isEqualTo("STALE_DECISION");
        assertThat(report.readinessReviews()).containsExactly(review);
        verify(pricingApplyReadinessDao).findLatestReviews("READY_TO_APPLY", "STALE_DECISION", 500);
    }

    @Test
    void buildDryRunApplySelectionSelectsOnlyLatestReadyRows() {
        PricingApplyReadinessReview review = PricingApplyReadinessReview.builder()
                .readinessStatusCode("READY_TO_APPLY")
                .build();
        when(pricingApplyReadinessDao.findLatestReadyToApplyReviews(1)).thenReturn(Set.of(review));

        BricklinkPricingDryRunApplySelectionReport report = service.buildDryRunApplySelection(0);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.selectedCount()).isOne();
        assertThat(report.selectedReadinessReviews()).containsExactly(review);
        verify(pricingApplyReadinessDao).findLatestReadyToApplyReviews(1);
    }
}
