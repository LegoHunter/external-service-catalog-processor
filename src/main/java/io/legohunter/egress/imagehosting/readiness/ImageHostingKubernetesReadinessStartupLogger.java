package io.legohunter.egress.imagehosting.readiness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "lego.image-hosting.readiness",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ImageHostingKubernetesReadinessStartupLogger implements ApplicationRunner {
    private final ImageHostingKubernetesReadinessService readinessService;

    @Override
    public void run(ApplicationArguments args) {
        ImageHostingKubernetesReadinessReport report = readinessService.evaluate();
        log.info(
                "image_hosting.readiness.startup ready={} checkCount={}",
                report.ready(),
                report.checks().size()
        );
        report.checks().forEach(check -> logCheck(report.ready(), check));
    }

    private void logCheck(boolean ready, ImageHostingKubernetesReadinessCheck check) {
        String message = "image_hosting.readiness.check component={} status={} required={} message={}";
        if (check.down()) {
            log.error(message, check.component(), check.status(), check.required(), check.message());
        } else if (!ready || "WARN".equals(check.status())) {
            log.warn(message, check.component(), check.status(), check.required(), check.message());
        } else {
            log.info(message, check.component(), check.status(), check.required(), check.message());
        }
    }
}
