package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.model.v1.Address;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Name;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Shipping;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.MarketplaceOrderDao;
import io.legohunter.data.dao.MarketplaceOrderPayloadDao;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentSyncServiceTest {
    private MarketplaceOrderDao marketplaceOrderDao;
    private MarketplaceOrderPayloadDao marketplaceOrderPayloadDao;
    private FulfillmentOrderItemImageResolver orderItemImageResolver;
    private FulfillmentSyncProperties properties;
    private ObjectMapper objectMapper;
    private FulfillmentSyncService service;

    @BeforeEach
    void setUp() {
        marketplaceOrderDao = mock(MarketplaceOrderDao.class);
        marketplaceOrderPayloadDao = mock(MarketplaceOrderPayloadDao.class);
        orderItemImageResolver = mock(FulfillmentOrderItemImageResolver.class);
        properties = new FulfillmentSyncProperties();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new FulfillmentSyncService(
                marketplaceOrderDao,
                marketplaceOrderPayloadDao,
                new BricklinkShipStationOrderMapper(),
                orderItemImageResolver,
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
        assertThat(result.ordersFailed()).isZero();
        assertThat(result.mappedOrderNumbers()).containsExactly("BL-100");
        assertThat(result.applied()).isFalse();
        verify(orderItemImageResolver).resolveImageUrls(marketplaceOrder);
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
