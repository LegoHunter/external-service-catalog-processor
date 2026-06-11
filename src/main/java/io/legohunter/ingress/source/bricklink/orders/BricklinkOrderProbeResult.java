package io.legohunter.ingress.source.bricklink.orders;

public record BricklinkOrderProbeResult(
        String outcome,
        int ordersDiscovered,
        int ordersFetched,
        int ordersFailed,
        int orderItemsFetched,
        long elapsedMillis
) {
}
