package io.legohunter.ingress.scheduling.job;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TestCronJob {
    @Scheduled(cron = "-") // disabled
    @SchedulerLock(name = "TestCronJob_lock", lockAtMostFor = "30s", lockAtLeastFor = "5s")
    public void run() {
        log.info("TestCronJob executed at {}", System.currentTimeMillis());
    }
}
