package io.legohunter.egress.fulfillment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentScheduledSyncJobTest {

    @Test
    void runOnceDelegatesToFulfillmentSyncService() {
        FulfillmentSyncService service = mock(FulfillmentSyncService.class);
        FulfillmentSyncResult expected = new FulfillmentSyncResult(
                "NO_WORK",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                12,
                false,
                List.of(),
                List.of()
        );
        when(service.runOnce()).thenReturn(expected);

        FulfillmentScheduledSyncJob job = new FulfillmentScheduledSyncJob(service);

        FulfillmentSyncResult result = job.runOnce();

        assertThat(result).isSameAs(expected);
        verify(service).runOnce();
    }
}
