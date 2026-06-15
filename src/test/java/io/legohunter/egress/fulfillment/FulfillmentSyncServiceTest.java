package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.model.v1.Address;
import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Name;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Shipping;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FulfillmentSyncServiceTest {
    private ShipStationRestClient shipStationRestClient;
    private BricklinkRestClient bricklinkRestClient;
    private MarketplaceOrderDao marketplaceOrderDao;
    private MarketplaceOrderPayloadDao marketplaceOrderPayloadDao;
    private FulfillmentOrderItemImageResolver orderItemImageResolver;
    private FulfillmentSyncProperties properties;
    private ObjectMapper objectMapper;
    private FulfillmentSyncService service;

    @BeforeEach
    void setUp() {
        shipStationRestClient = mock(ShipStationRestClient.class);
        bricklinkRestClient = mock(BricklinkRestClient.class);
        marketplaceOrderDao = mock(MarketplaceOrderDao.class);
        marketplaceOrderPayloadDao = mock(MarketplaceOrderPayloadDao.class);
        orderItemImageResolver = mock(FulfillmentOrderItemImageResolver.class);
        properties = new FulfillmentSyncProperties();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new FulfillmentSyncService(
                shipStationRestClient,
                bricklinkRestClient,
                marketplaceOrderDao,
                marketplaceOrderPayloadDao,
                new BricklinkShipStationOrderMapper(),
                orderItemImageResolver,
                new FulfillmentSyncMetricsService(new SimpleMeterRegistry()),
                properties,
                objectMapper
        );
    }

    @Test
    void runOnceReturnsNoWorkWhenNoFulfillmentCandidatesExist() {
        when(marketplaceOrderDao.findFulfillmentCandidates("BRICKLINK", properties.effectiveStatuses(), 25))
                .thenReturn(new LinkedHashSet<>());

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        assertThat(result.ordersDiscovered()).isZero();
        assertThat(result.applied()).isFalse();
        verifyNoInteractions(shipStationRestClient, bricklinkRestClient);
    }

    @Test
    void runOnceLoadsLatestPayloadsAndMapsShipStationOrderInDryRunMode() throws Exception {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        Order order = order("100", "PAID");
        List<OrderItem> orderItems = List.of(orderItem(3001L));

        when(marketplaceOrderDao.findFulfillmentCandidates("BRICKLINK", properties.effectiveStatuses(), 25))
                .thenReturn(new LinkedHashSet<>(List.of(marketplaceOrder)));
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                20,
                FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD
        )).thenReturn(Optional.of(payload(501, 20, FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD, objectMapper.writeValueAsString(order))));
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                20,
                FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD
        )).thenReturn(Optional.of(payload(502, 20, FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD, objectMapper.writeValueAsString(orderItems))));
        when(orderItemImageResolver.resolveImageUrls(marketplaceOrder))
                .thenReturn(Map.of("100:inventory:3001", "https://photos.example/primary.jpg"));

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.ordersDiscovered()).isEqualTo(1);
        assertThat(result.ordersLoaded()).isEqualTo(1);
        assertThat(result.payloadsMissing()).isZero();
        assertThat(result.ordersMapped()).isEqualTo(1);
        assertThat(result.ordersSkipped()).isEqualTo(1);
        assertThat(result.ordersCreated()).isZero();
        assertThat(result.ordersUpdated()).isZero();
        assertThat(result.ordersShippedReconciled()).isZero();
        assertThat(result.ordersFailed()).isZero();
        assertThat(result.mappedOrderNumbers()).containsExactly("BL-100");
        assertThat(result.applied()).isFalse();
        verify(orderItemImageResolver).resolveImageUrls(marketplaceOrder);
        verifyNoInteractions(shipStationRestClient, bricklinkRestClient);
    }

    @Test
    void runOnceCountsMissingPayloadsWithoutFailingCandidateBatch() {
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        when(marketplaceOrderDao.findFulfillmentCandidates("BRICKLINK", properties.effectiveStatuses(), 25))
                .thenReturn(new LinkedHashSet<>(List.of(marketplaceOrder)));
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                20,
                FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD
        )).thenReturn(Optional.empty());
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                20,
                FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD
        )).thenReturn(Optional.empty());

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("PAYLOADS_MISSING");
        assertThat(result.ordersDiscovered()).isEqualTo(1);
        assertThat(result.ordersLoaded()).isZero();
        assertThat(result.payloadsMissing()).isEqualTo(1);
        assertThat(result.ordersMapped()).isZero();
        assertThat(result.ordersFailed()).isZero();

        verify(marketplaceOrderPayloadDao).findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                20,
                FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD
        );
        verify(marketplaceOrderPayloadDao).findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                20,
                FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD
        );
        verifyNoInteractions(shipStationRestClient, bricklinkRestClient);
    }

    @Test
    void runOnceCreatesShipStationOrderWhenApplyEnabledAndNoExistingOrderExists() throws Exception {
        properties.getSync().getScheduled().setApply(true);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        setupLoadedOrder(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder().orders(List.of()).build());
        when(shipStationRestClient.createOrUpdateOrder(any(ShipStationOrder.class)))
                .thenAnswer(invocation -> {
                    ShipStationOrder order = invocation.getArgument(0);
                    order.setOrderId(42L);
                    return order;
                });

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.ordersCreated()).isEqualTo(1);
        assertThat(result.ordersUpdated()).isZero();
        assertThat(result.ordersSkipped()).isZero();
        assertThat(result.applied()).isTrue();
        verify(shipStationRestClient).createOrUpdateOrder(any(ShipStationOrder.class));
        verifyNoInteractions(bricklinkRestClient);
    }

    @Test
    void runOnceUpdatesShipStationOrderWhenApplyEnabledAndExistingOrderIsNotShipped() throws Exception {
        properties.getSync().getScheduled().setApply(true);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        setupLoadedOrder(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));
        ShipStationOrder existingOrder = ShipStationOrder.builder()
                .orderId(42L)
                .orderNumber("BL-100")
                .orderStatus("awaiting_shipment")
                .build();
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder().orders(List.of(existingOrder)).build());
        when(shipStationRestClient.createOrUpdateOrder(any(ShipStationOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.ordersCreated()).isZero();
        assertThat(result.ordersUpdated()).isEqualTo(1);
        verify(shipStationRestClient).createOrUpdateOrder(any(ShipStationOrder.class));
        verifyNoInteractions(bricklinkRestClient);
    }

    @Test
    void runOnceReconcilesShippedShipStationOrderBackToBricklinkWhenApplyEnabled() throws Exception {
        properties.getSync().getScheduled().setApply(true);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        Order order = order("100", "PAID");
        setupLoadedOrder(marketplaceOrder, order, List.of(orderItem(3001L)));
        ShipStationOrder existingOrder = ShipStationOrder.builder()
                .orderId(42L)
                .orderNumber("BL-100")
                .orderStatus("shipped")
                .shipTo(com.shipstation.api.rest.model.Address.builder().country("US").build())
                .build();
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder().orders(List.of(existingOrder)).build());
        when(shipStationRestClient.getShipments(Map.of("orderId", 42L)))
                .thenReturn(ShipmentsList.builder()
                        .shipments(List.of(Shipment.builder()
                                .orderId(42L)
                                .trackingNumber("940011120621")
                                .voided(false)
                                .shipDate(OffsetDateTime.parse("2026-06-10T12:00:00Z"))
                                .build()))
                        .build());
        when(bricklinkRestClient.getOrder("100"))
                .thenReturn(resource(orderWithDriveThruSent("100")));

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.ordersShippedReconciled()).isEqualTo(1);
        assertThat(result.ordersCreated()).isZero();
        assertThat(result.ordersUpdated()).isZero();
        verify(bricklinkRestClient).updateOrder(eq("100"), any(Order.class));
        verify(bricklinkRestClient).updateOrderStatus("100", com.bricklink.api.rest.model.v1.OrderStatus.SHIPPED);
        verify(bricklinkRestClient, never()).sendDriveThru(any(), eq(true));
        verify(marketplaceOrderDao).update(marketplaceOrder);
        verify(shipStationRestClient, never()).createOrUpdateOrder(any());
        assertThat(marketplaceOrder.getExternalStatusCode()).isEqualTo("SHIPPED");
        assertThat(marketplaceOrder.getTrackingPresent()).isTrue();
    }

    @Test
    void runOnceFailsCandidateWhenShippedShipStationOrderHasNoTracking() throws Exception {
        properties.getSync().getScheduled().setApply(true);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        setupLoadedOrder(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));
        ShipStationOrder existingOrder = ShipStationOrder.builder()
                .orderId(42L)
                .orderNumber("BL-100")
                .orderStatus("shipped")
                .build();
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder().orders(List.of(existingOrder)).build());
        when(shipStationRestClient.getShipments(Map.of("orderId", 42L)))
                .thenReturn(ShipmentsList.builder().shipments(List.of()).build());

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.ordersFailed()).isEqualTo(1);
        assertThat(result.failedOrderIds()).containsExactly("100");
        verifyNoInteractions(bricklinkRestClient);
        verify(shipStationRestClient, never()).createOrUpdateOrder(any());
    }

    @Test
    void runOnceFailsCandidateWhenMultipleShipStationOrdersUseSameOrderNumber() throws Exception {
        properties.getSync().getScheduled().setApply(true);
        MarketplaceOrder marketplaceOrder = marketplaceOrder(20, "100");
        setupLoadedOrder(marketplaceOrder, order("100", "PAID"), List.of(orderItem(3001L)));
        when(shipStationRestClient.getOrders(Map.of("orderNumber", "BL-100")))
                .thenReturn(OrdersList.builder()
                        .orders(List.of(
                                ShipStationOrder.builder().orderId(42L).orderNumber("BL-100").build(),
                                ShipStationOrder.builder().orderId(43L).orderNumber("BL-100").build()
                        ))
                        .build());

        FulfillmentSyncResult result = service.runOnce();

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.ordersFailed()).isEqualTo(1);
        verifyNoInteractions(bricklinkRestClient);
        verify(shipStationRestClient, never()).createOrUpdateOrder(any());
    }

    private static MarketplaceOrder marketplaceOrder(Integer marketplaceOrderId, String externalOrderId) {
        return MarketplaceOrder.builder()
                .marketplaceOrderId(marketplaceOrderId)
                .marketplaceCode("BRICKLINK")
                .externalOrderId(externalOrderId)
                .externalStatusCode("PAID")
                .build();
    }

    private static MarketplaceOrderPayload payload(
            Integer marketplaceOrderPayloadId,
            Integer marketplaceOrderId,
            String payloadTypeCode,
            String payloadJson
    ) {
        return MarketplaceOrderPayload.builder()
                .marketplaceOrderPayloadId(marketplaceOrderPayloadId)
                .marketplaceOrderId(marketplaceOrderId)
                .payloadTypeCode(payloadTypeCode)
                .payloadJson(payloadJson)
                .build();
    }

    private static Order order(String orderId, String status) {
        Order order = new Order();
        order.setOrder_id(orderId);
        order.setStatus(status);
        order.setDate_ordered(LocalDateTime.parse("2026-06-08T10:00:00"));
        order.setBuyer_name("buyer-one");
        order.setBuyer_email("buyer@example.com");
        order.setShipping(shipping());
        return order;
    }

    private void setupLoadedOrder(MarketplaceOrder marketplaceOrder, Order order, List<OrderItem> orderItems) throws Exception {
        when(marketplaceOrderDao.findFulfillmentCandidates("BRICKLINK", properties.effectiveStatuses(), 25))
                .thenReturn(new LinkedHashSet<>(List.of(marketplaceOrder)));
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                marketplaceOrder.getMarketplaceOrderId(),
                FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD
        )).thenReturn(Optional.of(payload(501, marketplaceOrder.getMarketplaceOrderId(), FulfillmentSyncService.ORDER_RESPONSE_PAYLOAD, objectMapper.writeValueAsString(order))));
        when(marketplaceOrderPayloadDao.findLatestByMarketplaceOrderIdAndPayloadTypeCode(
                marketplaceOrder.getMarketplaceOrderId(),
                FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD
        )).thenReturn(Optional.of(payload(502, marketplaceOrder.getMarketplaceOrderId(), FulfillmentSyncService.ORDER_ITEMS_RESPONSE_PAYLOAD, objectMapper.writeValueAsString(orderItems))));
        when(orderItemImageResolver.resolveImageUrls(marketplaceOrder)).thenReturn(Map.of());
    }

    private static Order orderWithDriveThruSent(String orderId) {
        Order order = order(orderId, "SHIPPED");
        order.setSent_drive_thru(true);
        return order;
    }

    private static <T> BricklinkResource<T> resource(T data) {
        BricklinkResource<T> resource = new BricklinkResource<>();
        resource.setData(data);
        return resource;
    }

    private static Shipping shipping() {
        Name name = new Name();
        name.setFull("Jane Buyer");
        Address address = new Address();
        address.setName(name);
        address.setAddress1("1 Main St");
        address.setCountry_code("US");
        Shipping shipping = new Shipping();
        shipping.setAddress(address);
        return shipping;
    }

    private static OrderItem orderItem(Long inventoryId) {
        Item item = new Item();
        item.setNo("1234-1");
        item.setName("Test Set");
        OrderItem orderItem = new OrderItem();
        orderItem.setInventory_id(inventoryId);
        orderItem.setItem(item);
        orderItem.setQuantity(1);
        orderItem.setUnit_price_final(5.49);
        return orderItem;
    }
}
