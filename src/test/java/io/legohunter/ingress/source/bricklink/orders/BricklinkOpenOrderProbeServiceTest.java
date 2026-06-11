package io.legohunter.ingress.source.bricklink.orders;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BricklinkOpenOrderProbeServiceTest {
    @Mock
    private BricklinkRestClient bricklinkRestClient;

    private BricklinkOrderSyncProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private BricklinkOpenOrderProbeService probeService;

    @BeforeEach
    void setUp() {
        properties = new BricklinkOrderSyncProperties();
        meterRegistry = new SimpleMeterRegistry();
        probeService = new BricklinkOpenOrderProbeService(
                bricklinkRestClient,
                properties,
                new BricklinkOrderSyncMetricsService(meterRegistry)
        );
    }

    @Test
    void runOnceRecordsNoWorkAndQueriesConfiguredStatusesPlusUnfiledCancelled() {
        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of()));

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("NO_WORK");
        assertThat(result.ordersDiscovered()).isZero();
        assertThat(result.ordersFetched()).isZero();
        assertThat(result.ordersFailed()).isZero();
        assertThat(result.orderItemsFetched()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Iterable> statusCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(bricklinkRestClient, org.mockito.Mockito.times(7))
                .getOrders(paramsCaptor.capture(), statusCaptor.capture());

        List<String> queriedStatuses = statusCaptor.getAllValues().stream()
                .flatMap(statuses -> {
                    List<String> values = new ArrayList<>();
                    statuses.forEach(status -> values.add(String.valueOf(status)));
                    return values.stream();
                })
                .toList();
        assertThat(queriedStatuses).containsExactly(
                "PENDING",
                "UPDATED",
                "READY",
                "PROCESSING",
                "PAID",
                "PACKED",
                "CANCELLED"
        );
        assertThat(paramsCaptor.getAllValues().getLast())
                .containsEntry("direction", "in")
                .containsEntry("filed", false);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync",
                "provider", "bricklink",
                "outcome", "no_work"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void runOnceFetchesOrderDetailsAndFlattensOrderItemBatches() {
        properties.setStatuses(List.of("pending"));
        properties.setIncludeUnfiledCancelled(false);
        Order summary = order("100", "PENDING");
        Order detail = order("100", "PAID");
        detail.setTotal_count(3);
        detail.setUnique_count(2);
        detail.setCost(cost("USD", 30.0, 38.0, 8.0));

        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(summary)));
        when(bricklinkRestClient.getOrder("100"))
                .thenReturn(resource(detail));
        when(bricklinkRestClient.getOrderItems("100"))
                .thenReturn(resource(List.of(
                        List.of(orderItem("3001"), orderItem("3002")),
                        List.of(orderItem("3003"))
                )));

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.ordersDiscovered()).isEqualTo(1);
        assertThat(result.ordersFetched()).isEqualTo(1);
        assertThat(result.ordersFailed()).isZero();
        assertThat(result.orderItemsFetched()).isEqualTo(3);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync_order",
                "provider", "bricklink",
                "result", "discovered"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync_order",
                "provider", "bricklink",
                "result", "fetched"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void runOnceContinuesWhenOneOrderFails() {
        properties.setStatuses(List.of("PENDING"));
        properties.setIncludeUnfiledCancelled(false);
        Order first = order("100", "PENDING");
        Order second = order("200", "PENDING");

        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(first, second)));
        when(bricklinkRestClient.getOrder("100"))
                .thenReturn(resource(first));
        when(bricklinkRestClient.getOrderItems("100"))
                .thenReturn(resource(List.of()));
        when(bricklinkRestClient.getOrder("200"))
                .thenThrow(new IllegalStateException("BrickLink unavailable"));

        BricklinkOrderProbeResult result = probeService.runOnce();

        assertThat(result.outcome()).isEqualTo("PARTIAL_FAILURE");
        assertThat(result.ordersDiscovered()).isEqualTo(2);
        assertThat(result.ordersFetched()).isEqualTo(1);
        assertThat(result.ordersFailed()).isEqualTo(1);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync",
                "provider", "bricklink",
                "outcome", "partial_failure"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "bricklink_order_sync_order",
                "provider", "bricklink",
                "result", "failed"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void getOpenOrderSummariesDeduplicatesByOrderId() {
        properties.setIncludeUnfiledCancelled(false);
        when(bricklinkRestClient.getOrders(anyMap(), any()))
                .thenReturn(resource(List.of(order("100", "PENDING"), order("100", "PENDING"))));

        List<Order> orders = probeService.getOpenOrderSummaries(List.of("PENDING"));

        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().getOrder_id()).isEqualTo("100");
    }

    private static Order order(String orderId, String status) {
        Order order = new Order();
        order.setOrder_id(orderId);
        order.setStatus(status);
        order.setDate_ordered(LocalDateTime.parse("2026-06-08T10:00:00"));
        order.setDate_status_changed(LocalDateTime.parse("2026-06-08T11:00:00"));
        return order;
    }

    private static OrderItem orderItem(String inventoryId) {
        Item item = new Item();
        item.setNo("1234-1");
        item.setType("SET");
        item.setName("Test Set");

        OrderItem orderItem = new OrderItem();
        orderItem.setInventory_id(Long.valueOf(inventoryId));
        orderItem.setItem(item);
        orderItem.setQuantity(1);
        return orderItem;
    }

    private static Cost cost(String currencyCode, double subtotal, double grandTotal, double shipping) {
        Cost cost = new Cost();
        cost.setCurrency_code(currencyCode);
        cost.setSubtotal(subtotal);
        cost.setGrand_total(grandTotal);
        cost.setShipping(shipping);
        return cost;
    }

    private static <T> BricklinkResource<T> resource(T data) {
        BricklinkResource<T> resource = new BricklinkResource<>();
        resource.setData(data);
        return resource;
    }
}
