package io.legohunter.egress.fulfillment;

import java.util.List;

public record FulfillmentSyncResult(
        String outcome,
        int ordersDiscovered,
        int ordersLoaded,
        int payloadsMissing,
        int ordersMapped,
        int ordersFailed,
        int ordersCreated,
        int ordersUpdated,
        int ordersShippedReconciled,
        int ordersSkipped,
        long elapsedMillis,
        boolean applied,
        List<String> mappedOrderNumbers,
        List<String> failedOrderIds
) {
    public FulfillmentSyncResult {
        mappedOrderNumbers = mappedOrderNumbers == null ? List.of() : List.copyOf(mappedOrderNumbers);
        failedOrderIds = failedOrderIds == null ? List.of() : List.copyOf(failedOrderIds);
    }
}
