package io.legohunter.egress.imagehosting.readiness;

public record ImageHostingKubernetesReadinessCheck(
        String component,
        String status,
        boolean required,
        String message
) {
    public boolean down() {
        return "DOWN".equals(status);
    }
}
