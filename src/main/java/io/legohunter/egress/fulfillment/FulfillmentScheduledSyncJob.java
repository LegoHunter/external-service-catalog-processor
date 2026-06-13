package io.legohunter.egress.fulfillment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.fulfillment.sync.scheduled", name = "enabled", havingValue = "true")
public class FulfillmentScheduledSyncJob {
    private final FulfillmentSyncService fulfillmentSyncService;

    @Scheduled(
            fixedDelayString = "${lego.fulfillment.sync.scheduled.fixed-delay-ms:300000}",
            initialDelayString = "${lego.fulfillment.sync.scheduled.initial-delay-ms:30000}"
    )
    @SchedulerLock(
            name = "FulfillmentScheduledSyncJob",
            lockAtMostFor = "${lego.fulfillment.sync.scheduled.lock-at-most-for:10m}",
            lockAtLeastFor = "${lego.fulfillment.sync.scheduled.lock-at-least-for:0s}"
    )
    public void runScheduled() {
        runOnce();
    }

    FulfillmentSyncResult runOnce() {
        return fulfillmentSyncService.runOnce();
    }
}
