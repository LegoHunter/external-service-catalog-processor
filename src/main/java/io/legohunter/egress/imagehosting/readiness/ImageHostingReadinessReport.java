package io.legohunter.egress.imagehosting.readiness;

import java.util.List;

public record ImageHostingReadinessReport(
        boolean ready,
        List<ImageHostingReadinessCheck> checks
) {
    public ImageHostingReadinessReport {
        checks = List.copyOf(checks);
    }
}
