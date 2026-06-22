package io.legohunter.ingress.source.bricklink.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingDecisionJobTest {
    @Test
    void runOnceDelegatesToDecisionService() {
        BricklinkPricingDecisionService decisionService = mock(BricklinkPricingDecisionService.class);
        BricklinkPricingDecisionResult expected = new BricklinkPricingDecisionResult(
                "SUCCESS",
                1,
                1,
                1,
                0,
                0,
                50L
        );
        when(decisionService.runOnce()).thenReturn(expected);

        BricklinkPricingDecisionJob job = new BricklinkPricingDecisionJob(decisionService);
        BricklinkPricingDecisionResult result = job.runOnce();

        assertThat(result).isEqualTo(expected);
        verify(decisionService).runOnce();
    }
}
