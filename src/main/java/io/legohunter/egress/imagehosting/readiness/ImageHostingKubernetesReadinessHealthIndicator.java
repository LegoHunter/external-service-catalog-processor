package io.legohunter.egress.imagehosting.readiness;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("imageHostingKubernetesReadiness")
@RequiredArgsConstructor
@ConditionalOnEnabledHealthIndicator("imageHostingKubernetesReadiness")
@ConditionalOnProperty(
        prefix = "lego.image-hosting.readiness",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ImageHostingKubernetesReadinessHealthIndicator implements HealthIndicator {
    private final ImageHostingKubernetesReadinessService readinessService;

    @Override
    public Health health() {
        ImageHostingKubernetesReadinessReport report = readinessService.evaluate();
        Health.Builder builder = report.ready()
                ? Health.status(Status.UP)
                : Health.status(Status.DOWN);

        return builder
                .withDetail("ready", report.ready())
                .withDetail("checks", report.checks())
                .build();
    }
}
