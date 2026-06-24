package io.legohunter.ingress.source.bricklink.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingApplyReadinessJobTest {
    @Test
    void runOnceDelegatesToApplyReadinessService() {
        BricklinkPricingApplyReadinessService applyReadinessService = mock(BricklinkPricingApplyReadinessService.class);
        BricklinkPricingApplyReadinessResult expected = new BricklinkPricingApplyReadinessResult(
                "SUCCESS",
                1,
                1,
                0,
                0,
                0,
                0,
                0,
                50L
        );
        when(applyReadinessService.runOnce()).thenReturn(expected);

        BricklinkPricingApplyReadinessJob job = new BricklinkPricingApplyReadinessJob(applyReadinessService);
        BricklinkPricingApplyReadinessResult result = job.runOnce();

        assertThat(result).isEqualTo(expected);
        verify(applyReadinessService).runOnce();
    }
}
