package io.legohunter.egress.imagehosting.readiness;

import java.util.List;

public record ImageHostingKubernetesReadinessReport(
        boolean ready,
        List<ImageHostingKubernetesReadinessCheck> checks
) {
    public ImageHostingKubernetesReadinessReport {
        checks = List.copyOf(checks);
    }
}
