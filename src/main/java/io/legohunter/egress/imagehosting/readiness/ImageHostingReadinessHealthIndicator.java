package io.legohunter.egress.imagehosting.readiness;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("imageHostingReadiness")
@RequiredArgsConstructor
@ConditionalOnEnabledHealthIndicator("imageHostingReadiness")
@ConditionalOnProperty(
        prefix = "lego.image-hosting.readiness",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ImageHostingReadinessHealthIndicator implements HealthIndicator {
    private final ImageHostingReadinessService readinessService;

    @Override
    public Health health() {
        ImageHostingReadinessReport report = readinessService.evaluate();
        Health.Builder builder = report.ready()
                ? Health.status(Status.UP)
                : Health.status(Status.DOWN);

        return builder
                .withDetail("ready", report.ready())
                .withDetail("checks", report.checks())
                .build();
    }
}
