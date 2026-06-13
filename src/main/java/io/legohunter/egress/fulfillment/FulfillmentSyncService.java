package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Shipping;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipstation.api.rest.client.ShipStationRestClient;
import com.shipstation.api.rest.model.OrdersList;
import com.shipstation.api.rest.model.Shipment;
import com.shipstation.api.rest.model.ShipmentsList;
import com.shipstation.api.rest.model.ShipStationOrder;
import io.legohunter.data.dao.MarketplaceOrderDao;
import io.legohunter.data.dao.MarketplaceOrderPayloadDao;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.fulfillment.sync.scheduled", name = "enabled", havingValue = "true")
public class FulfillmentSyncService {
    static final String ORDER_RESPONSE_PAYLOAD = "ORDER_RESPONSE";
    static final String ORDER_ITEMS_RESPONSE_PAYLOAD = "ORDER_ITEMS_RESPONSE";

    private static final TypeReference<List<OrderItem>> ORDER_ITEMS_TYPE = new TypeReference<>() {
    };

    private final MarketplaceOrderDao marketplaceOrderDao;
    private final MarketplaceOrderPayloadDao marketplaceOrderPayloadDao;
    private final BricklinkShipStationOrderMapper shipStationOrderMapper;
    private final FulfillmentOrderItemImageResolver orderItemImageResolver;
    private final FulfillmentSyncMetricsService metricsService;
    private final Optional<ShipStationRestClient> shipStationRestClient;
    private final Optional<BricklinkRestClient> bricklinkRestClient;
    private final FulfillmentSyncProperties properties;
    private final ObjectMapper objectMapper;

    public FulfillmentSyncResult runOnce() {
        long startedAt = System.currentTimeMillis();
        FulfillmentSyncProperties.Scheduled scheduled = properties.getSync().getScheduled();
        int batchSize = scheduled.effectiveBatchSize();
        boolean apply = scheduled.isApply();
        String marketplaceCode = properties.effectiveMarketplaceCode();
        List<String> statuses = properties.effectiveStatuses();

        log.info(
                "fulfillment.sync_job.started provider={} marketplaceCode={} statuses={} batchSize={} apply={}",
                properties.effectiveMetricsTag(),
                marketplaceCode,
                statuses,
                batchSize,
                apply
        );

        Set<MarketplaceOrder> candidates = marketplaceOrderDao.findFulfillmentCandidates(
                marketplaceCode,
                statuses,
                batchSize
        );
        if (candidates.isEmpty()) {
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            log.info(
                    "fulfillment.sync_job.no_work provider={} marketplaceCode={} elapsedMillis={}",
                    properties.effectiveMetricsTag(),
                    marketplaceCode,
                    elapsedMillis
            );
            metricsService.recordRun(providerTag(), "no_work", elapsedMillis);
            return result("NO_WORK", 0, 0, 0, 0, 0, 0, 0, 0, 0, elapsedMillis, apply, List.of(), List.of());
        }

        int ordersLoaded = 0;
        int payloadsMissing = 0;
        int ordersMapped = 0;
        int ordersFailed = 0;
        int ordersCreated = 0;
        int ordersUpdated = 0;
        int ordersShippedReconciled = 0;
        int ordersSkipped = 0;
        List<String> mappedOrderNumbers = new ArrayList<>();
        List<String> failedOrderIds = new ArrayList<>();

        for (MarketplaceOrder candidate : candidates) {
            try {
                Optional<LoadedBricklinkOrder> loadedOrder = loadOrder(candidate);
                if (loadedOrder.isEmpty()) {
                    payloadsMissing++;
                    continue;
                }

                ordersLoaded++;
                ShipStationOrder shipStationOrder = shipStationOrderMapper.map(
                        loadedOrder.get().order(),
                        loadedOrder.get().orderItems(),
                        properties.getShipstation(),
                        orderItemImageResolver.resolveImageUrls(candidate)
                );
                ordersMapped++;
                mappedOrderNumbers.add(shipStationOrder.getOrderNumber());
                FulfillmentOrderWriteResult writeResult = syncOrder(loadedOrder.get().order(), shipStationOrder, apply);
                ordersCreated += writeResult.created();
                ordersUpdated += writeResult.updated();
                ordersShippedReconciled += writeResult.shippedReconciled();
                ordersSkipped += writeResult.skipped();
                log.info(
                        "fulfillment.sync_job.order_mapped provider={} marketplaceOrderId={} externalOrderId={} orderNumber={} orderStatus={} itemCount={} apply={} action={}",
                        properties.effectiveMetricsTag(),
                        candidate.getMarketplaceOrderId(),
                        candidate.getExternalOrderId(),
                        shipStationOrder.getOrderNumber(),
                        shipStationOrder.getOrderStatus(),
                        shipStationOrder.getItems().length,
                        apply,
                        writeResult.action()
                );
            } catch (RuntimeException e) {
                ordersFailed++;
                failedOrderIds.add(candidate.getExternalOrderId());
                log.warn(
                        "fulfillment.sync_job.order_failed provider={} marketplaceOrderId={} externalOrderId={} message={}",
                        properties.effectiveMetricsTag(),
                        candidate.getMarketplaceOrderId(),
                        candidate.getExternalOrderId(),
                        e.getMessage(),
                        e
                );
            }
        }

        long elapsedMillis = System.currentTimeMillis() - startedAt;
        String outcome = outcome(ordersMapped, ordersFailed, payloadsMissing);
        recordMetrics(
                outcome,
                elapsedMillis,
                candidates.size(),
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                ordersFailed,
                ordersCreated,
                ordersUpdated,
                ordersShippedReconciled,
                ordersSkipped
        );
        log.info(
                "fulfillment.sync_job.completed provider={} marketplaceCode={} outcome={} ordersDiscovered={} ordersLoaded={} payloadsMissing={} ordersMapped={} ordersCreated={} ordersUpdated={} ordersShippedReconciled={} ordersSkipped={} ordersFailed={} elapsedMillis={} apply={}",
                properties.effectiveMetricsTag(),
                marketplaceCode,
                outcome,
                candidates.size(),
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                ordersCreated,
                ordersUpdated,
                ordersShippedReconciled,
                ordersSkipped,
                ordersFailed,
                elapsedMillis,
                apply
        );
        return result(
                outcome,
                candidates.size(),
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                ordersFailed,
                ordersCreated,
                ordersUpdated,
                ordersShippedReconciled,
                ordersSkipped,
                elapsedMillis,
                apply,
                mappedOrderNumbers,
                failedOrderIds
        );
    }

    private FulfillmentOrderWriteResult syncOrder(Order bricklinkOrder, ShipStationOrder shipStationOrder, boolean apply) {
        if (!apply) {
            return FulfillmentOrderWriteResult.skipped("DRY_RUN");
        }

        ShipStationRestClient shipStationClient = requiredShipStationClient();
        Optional<ShipStationOrder> existingOrder = findShipStationOrder(shipStationClient, shipStationOrder.getOrderNumber());
        if (existingOrder.filter(ShipStationOrder::isShipped).isPresent()) {
            reconcileBricklinkShipment(bricklinkOrder, existingOrder.get(), shipStationClient, requiredBricklinkClient());
            return FulfillmentOrderWriteResult.forShippedReconciled();
        }

        existingOrder.map(ShipStationOrder::getOrderId).ifPresent(shipStationOrder::setOrderId);
        ShipStationOrder syncedOrder = shipStationClient.createOrUpdateOrder(shipStationOrder);
        log.info(
                "fulfillment.sync_job.shipstation_synced orderNumber={} orderId={} existing={} status={}",
                shipStationOrder.getOrderNumber(),
                syncedOrder == null ? null : syncedOrder.getOrderId(),
                existingOrder.isPresent(),
                syncedOrder == null ? null : syncedOrder.getOrderStatus()
        );
        return existingOrder.isPresent()
                ? FulfillmentOrderWriteResult.forUpdated()
                : FulfillmentOrderWriteResult.forCreated();
    }

    private Optional<ShipStationOrder> findShipStationOrder(ShipStationRestClient shipStationClient, String orderNumber) {
        OrdersList ordersList = shipStationClient.getOrders(Map.of("orderNumber", orderNumber));
        List<ShipStationOrder> matchingOrders = ordersList == null || ordersList.getOrders() == null
                ? List.of()
                : ordersList.getOrders().stream()
                .filter(order -> orderNumber.equals(order.getOrderNumber()))
                .toList();
        if (matchingOrders.size() > 1) {
            throw new IllegalStateException("Found [%d] ShipStation orders for order number [%s]".formatted(
                    matchingOrders.size(),
                    orderNumber
            ));
        }
        return matchingOrders.stream().findFirst();
    }

    private void reconcileBricklinkShipment(
            Order bricklinkOrder,
            ShipStationOrder shipStationOrder,
            ShipStationRestClient shipStationClient,
            BricklinkRestClient bricklinkClient
    ) {
        Shipment shipment = findShipment(shipStationClient, shipStationOrder)
                .orElseThrow(() -> new IllegalStateException(
                        "Unable to find non-voided shipment with tracking for ShipStation order " + shipStationOrder.getOrderId()
                ));
        Shipping shipping = Optional.ofNullable(bricklinkOrder.getShipping()).orElseGet(Shipping::new);
        shipping.setTracking_no(shipment.getTrackingNumber());
        shipping.setTracking_link(trackingUrl(shipStationOrder, shipment).orElse(null));
        shipping.setDate_shipped(dateShipped(shipment).map(OffsetDateTime::toZonedDateTime).orElse(null));

        Order orderUpdate = new Order();
        orderUpdate.setShipping(shipping);
        orderUpdate.setCost(bricklinkOrder.getCost());
        orderUpdate.setRemarks(null);
        orderUpdate.setIs_filed(false);

        bricklinkClient.updateOrder(bricklinkOrder.getOrder_id(), orderUpdate);
        bricklinkClient.updateOrderStatus(bricklinkOrder.getOrder_id(), com.bricklink.api.rest.model.v1.OrderStatus.SHIPPED);

        Order updatedOrder = data(bricklinkClient.getOrder(bricklinkOrder.getOrder_id()));
        if (updatedOrder != null && !Boolean.TRUE.equals(updatedOrder.getSent_drive_thru())) {
            bricklinkClient.sendDriveThru(bricklinkOrder.getOrder_id(), true);
        }

        log.info(
                "fulfillment.sync_job.bricklink_shipped_reconciled orderNumber={} bricklinkOrderId={} trackingNumber={} shipDate={}",
                shipStationOrder.getOrderNumber(),
                bricklinkOrder.getOrder_id(),
                shipment.getTrackingNumber(),
                dateShipped(shipment).orElse(null)
        );
    }

    private Optional<Shipment> findShipment(ShipStationRestClient shipStationClient, ShipStationOrder shipStationOrder) {
        Map<String, Object> params = shipStationOrder.getOrderId() == null
                ? Map.of("orderNumber", shipStationOrder.getOrderNumber())
                : Map.of("orderId", shipStationOrder.getOrderId());
        ShipmentsList shipmentsList = shipStationClient.getShipments(params);
        if (shipmentsList == null || shipmentsList.getShipments() == null) {
            return Optional.empty();
        }
        return shipmentsList.getShipments().stream()
                .filter(shipment -> !Boolean.TRUE.equals(shipment.getVoided()))
                .filter(shipment -> shipment.getTrackingNumber() != null && !shipment.getTrackingNumber().isBlank())
                .findFirst();
    }

    private Optional<OffsetDateTime> dateShipped(Shipment shipment) {
        return Optional.ofNullable(shipment.getShipDate())
                .or(() -> Optional.ofNullable(shipment.getCreateDate()));
    }

    private Optional<String> trackingUrl(ShipStationOrder order, Shipment shipment) {
        String trackingNumber = shipment.getTrackingNumber();
        if (trackingNumber == null || trackingNumber.isBlank()) {
            return Optional.empty();
        }
        String country = order.getShipTo() == null ? null : order.getShipTo().getCountry();
        if ("US".equalsIgnoreCase(country)) {
            return Optional.of("https://tools.usps.com/go/TrackConfirmAction.action?tLabels=%s".formatted(trackingNumber));
        }
        return Optional.of("http://parcelsapp.com/en/tracking/%s".formatted(trackingNumber));
    }

    private ShipStationRestClient requiredShipStationClient() {
        return shipStationRestClient.orElseThrow(() -> new IllegalStateException(
                "ShipStation client is required when lego.fulfillment.sync.scheduled.apply=true. Configure shipstation.rest.api-key and shipstation.rest.api-secret."
        ));
    }

    private BricklinkRestClient requiredBricklinkClient() {
        return bricklinkRestClient.orElseThrow(() -> new IllegalStateException(
                "BrickLink client is required when lego.fulfillment.sync.scheduled.apply=true."
        ));
    }

    private Optional<LoadedBricklinkOrder> loadOrder(MarketplaceOrder marketplaceOrder) {
        Integer marketplaceOrderId = marketplaceOrder.getMarketplaceOrderId();
        Optional<MarketplaceOrderPayload> orderPayload =
                marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                        marketplaceOrderId,
                        ORDER_RESPONSE_PAYLOAD
                );
        Optional<MarketplaceOrderPayload> orderItemsPayload =
                marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                        marketplaceOrderId,
                        ORDER_ITEMS_RESPONSE_PAYLOAD
                );

        if (orderPayload.isEmpty() || orderItemsPayload.isEmpty()) {
            log.info(
                    "fulfillment.sync_job.payload_missing provider={} marketplaceOrderId={} externalOrderId={} hasOrderPayload={} hasOrderItemsPayload={}",
                    properties.effectiveMetricsTag(),
                    marketplaceOrderId,
                    marketplaceOrder.getExternalOrderId(),
                    orderPayload.isPresent(),
                    orderItemsPayload.isPresent()
            );
            return Optional.empty();
        }

        return Optional.of(new LoadedBricklinkOrder(
                readOrder(orderPayload.get()),
                readOrderItems(orderItemsPayload.get())
        ));
    }

    private Order readOrder(MarketplaceOrderPayload payload) {
        try {
            return objectMapper.readValue(payload.getPayloadJson(), Order.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to decode BrickLink order payload " + payload.getMarketplaceOrderPayloadId(), e);
        }
    }

    private List<OrderItem> readOrderItems(MarketplaceOrderPayload payload) {
        try {
            return objectMapper.readValue(payload.getPayloadJson(), ORDER_ITEMS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to decode BrickLink order items payload " + payload.getMarketplaceOrderPayloadId(), e);
        }
    }

    private void recordMetrics(
            String outcome,
            long elapsedMillis,
            int ordersDiscovered,
            int ordersLoaded,
            int payloadsMissing,
            int ordersMapped,
            int ordersFailed,
            int ordersCreated,
            int ordersUpdated,
            int ordersShippedReconciled,
            int ordersSkipped
    ) {
        String providerTag = providerTag();
        metricsService.recordRun(providerTag, outcome.toLowerCase(), elapsedMillis);
        metricsService.recordOrders(providerTag, "discovered", ordersDiscovered);
        metricsService.recordOrders(providerTag, "loaded", ordersLoaded);
        metricsService.recordOrders(providerTag, "payload_missing", payloadsMissing);
        metricsService.recordOrders(providerTag, "mapped", ordersMapped);
        metricsService.recordOrders(providerTag, "failed", ordersFailed);
        metricsService.recordOrders(providerTag, "created", ordersCreated);
        metricsService.recordOrders(providerTag, "updated", ordersUpdated);
        metricsService.recordOrders(providerTag, "shipped_reconciled", ordersShippedReconciled);
        metricsService.recordOrders(providerTag, "skipped", ordersSkipped);
    }

    private String outcome(int ordersMapped, int ordersFailed, int payloadsMissing) {
        if (ordersFailed == 0) {
            if (ordersMapped == 0 && payloadsMissing > 0) {
                return "PAYLOADS_MISSING";
            }
            return "SUCCESS";
        }
        if (ordersMapped == 0) {
            return "FAILED";
        }
        return "PARTIAL_FAILURE";
    }

    private String providerTag() {
        return properties.effectiveMetricsTag();
    }

    private <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private FulfillmentSyncResult result(
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
            boolean apply,
            List<String> mappedOrderNumbers,
            List<String> failedOrderIds
    ) {
        return new FulfillmentSyncResult(
                outcome,
                ordersDiscovered,
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                ordersFailed,
                ordersCreated,
                ordersUpdated,
                ordersShippedReconciled,
                ordersSkipped,
                elapsedMillis,
                apply,
                mappedOrderNumbers,
                failedOrderIds
        );
    }

    private record LoadedBricklinkOrder(Order order, List<OrderItem> orderItems) {
    }

    private record FulfillmentOrderWriteResult(
            String action,
            int created,
            int updated,
            int shippedReconciled,
            int skipped
    ) {
        static FulfillmentOrderWriteResult forCreated() {
            return new FulfillmentOrderWriteResult("CREATED", 1, 0, 0, 0);
        }

        static FulfillmentOrderWriteResult forUpdated() {
            return new FulfillmentOrderWriteResult("UPDATED", 0, 1, 0, 0);
        }

        static FulfillmentOrderWriteResult forShippedReconciled() {
            return new FulfillmentOrderWriteResult("SHIPPED_RECONCILED", 0, 0, 1, 0);
        }

        static FulfillmentOrderWriteResult skipped(String reason) {
            return new FulfillmentOrderWriteResult("SKIPPED_" + reason, 0, 0, 0, 1);
        }
    }
}
