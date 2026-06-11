package io.legohunter.ingress.source.bricklink.orders;

import com.bricklink.api.rest.client.BricklinkRestClient;
import com.bricklink.api.rest.model.v1.BricklinkResource;
import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Shipping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.orders.sync.scheduled", name = "enabled", havingValue = "true")
public class BricklinkOpenOrderProbeService {
    private static final String CANCELLED = "CANCELLED";

    private final BricklinkRestClient bricklinkRestClient;
    private final BricklinkOrderSyncProperties properties;
    private final BricklinkOrderSyncMetricsService metricsService;

    public BricklinkOrderProbeResult runOnce() {
        long startedAt = System.currentTimeMillis();
        String providerTag = properties.effectiveMetricsTag();
        List<String> statuses = properties.effectiveStatuses();

        log.info(
                "bricklink.order_sync.probe.started provider={} direction={} statuses={} includeUnfiledCancelled={}",
                providerTag,
                properties.effectiveDirection(),
                statuses,
                properties.isIncludeUnfiledCancelled()
        );

        List<Order> orderSummaries = getOpenOrderSummaries(statuses);
        metricsService.recordOrders(providerTag, "discovered", orderSummaries.size());

        if (orderSummaries.isEmpty()) {
            long elapsedMillis = System.currentTimeMillis() - startedAt;
            metricsService.recordRun(providerTag, "no_work", elapsedMillis);
            log.info(
                    "bricklink.order_sync.probe.no_work provider={} elapsedMillis={}",
                    providerTag,
                    elapsedMillis
            );
            return new BricklinkOrderProbeResult("NO_WORK", 0, 0, 0, 0, elapsedMillis);
        }

        int ordersFetched = 0;
        int ordersFailed = 0;
        int orderItemsFetched = 0;
        for (Order orderSummary : orderSummaries) {
            String orderId = orderSummary.getOrder_id();
            try {
                Order order = data(bricklinkRestClient.getOrder(orderId));
                if (order == null) {
                    order = orderSummary;
                }
                List<OrderItem> orderItems = getOrderItems(orderId);
                ordersFetched++;
                orderItemsFetched += orderItems.size();
                logFetchedOrder(providerTag, order, orderItems.size());
                logOrderCoverage(providerTag, order, orderItems.size());
                logOrderItemCoverage(providerTag, order.getOrder_id(), orderItems);
            } catch (RuntimeException e) {
                ordersFailed++;
                log.warn(
                        "bricklink.order_sync.probe.order_failed provider={} orderId={} summaryStatus={} message={}",
                        providerTag,
                        orderId,
                        orderSummary.getStatus(),
                        e.getMessage(),
                        e
                );
            }
        }

        long elapsedMillis = System.currentTimeMillis() - startedAt;
        String outcome = outcome(ordersFetched, ordersFailed);
        metricsService.recordRun(providerTag, outcome.toLowerCase(), elapsedMillis);
        metricsService.recordOrders(providerTag, "fetched", ordersFetched);
        metricsService.recordOrders(providerTag, "failed", ordersFailed);
        log.info(
                "bricklink.order_sync.probe.completed provider={} outcome={} ordersDiscovered={} ordersFetched={} ordersFailed={} orderItemsFetched={} elapsedMillis={}",
                providerTag,
                outcome,
                orderSummaries.size(),
                ordersFetched,
                ordersFailed,
                orderItemsFetched,
                elapsedMillis
        );

        return new BricklinkOrderProbeResult(
                outcome,
                orderSummaries.size(),
                ordersFetched,
                ordersFailed,
                orderItemsFetched,
                elapsedMillis
        );
    }

    List<Order> getOpenOrderSummaries(List<String> statuses) {
        Map<String, Order> ordersById = new LinkedHashMap<>();
        for (String status : statuses) {
            addOrders(ordersById, baseParams(), status);
        }
        if (properties.isIncludeUnfiledCancelled() && !statuses.contains(CANCELLED)) {
            Map<String, Object> cancelledParams = baseParams();
            cancelledParams.put("filed", false);
            addOrders(ordersById, cancelledParams, CANCELLED);
        }
        return new ArrayList<>(ordersById.values());
    }

    List<OrderItem> getOrderItems(String orderId) {
        List<List<OrderItem>> batches = data(bricklinkRestClient.getOrderItems(orderId));
        if (batches == null || batches.isEmpty()) {
            return List.of();
        }
        return batches.stream()
                .filter(batch -> batch != null && !batch.isEmpty())
                .flatMap(Collection::stream)
                .toList();
    }

    private void addOrders(Map<String, Order> ordersById, Map<String, Object> params, String status) {
        List<Order> orders = data(bricklinkRestClient.getOrders(params, List.of(status)));
        if (orders == null) {
            return;
        }
        for (Order order : orders) {
            if (order != null && order.getOrder_id() != null) {
                ordersById.putIfAbsent(order.getOrder_id(), order);
            }
        }
    }

    private Map<String, Object> baseParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("direction", properties.effectiveDirection());
        return params;
    }

    private void logFetchedOrder(String providerTag, Order order, int orderItemCount) {
        Cost cost = order.getCost();
        Shipping shipping = order.getShipping();
        log.info(
                "bricklink.order_sync.probe.order_fetched provider={} orderId={} status={} dateOrdered={} dateStatusChanged={} totalCount={} uniqueCount={} orderItemCount={} currency={} subtotal={} grandTotal={} shippingCost={} shippingMethod={} trackingPresent={} paymentStatus={}",
                providerTag,
                order.getOrder_id(),
                order.getStatus(),
                date(order.getDate_ordered()),
                date(order.getDate_status_changed()),
                order.getTotal_count(),
                order.getUnique_count(),
                orderItemCount,
                cost == null ? null : cost.getCurrency_code(),
                cost == null ? null : cost.getSubtotal(),
                cost == null ? null : cost.getGrand_total(),
                cost == null ? null : cost.getShipping(),
                shipping == null ? null : shipping.getMethod(),
                shipping != null && shipping.getTracking_no() != null && !shipping.getTracking_no().isBlank(),
                order.getPayment() == null ? null : order.getPayment().getStatus()
        );
    }

    private void logOrderCoverage(String providerTag, Order order, int orderItemCount) {
        Cost cost = order.getCost();
        Cost displayCost = order.getDisp_cost();
        Shipping shipping = order.getShipping();
        log.info(
                "bricklink.order_sync.probe.coverage provider={} orderId={} status={} itemCount={} hasBuyerName={} hasBuyerEmail={} hasPayment={} hasPaymentMethod={} hasPaymentCurrency={} hasPaymentDatePaid={} hasPaymentStatus={} hasShipping={} hasShippingMethod={} hasShippingMethodId={} hasShippingAddress={} hasTracking={} hasCost={} hasDisplayCost={} currency={} displayCurrency={} hasRemarks={} hasTotalCount={} hasUniqueCount={} hasTotalWeight={} isInvoiced={} isFiled={} sentDriveThru={} requireInsurance={}",
                providerTag,
                order.getOrder_id(),
                order.getStatus(),
                orderItemCount,
                present(order.getBuyer_name()),
                present(order.getBuyer_email()),
                order.getPayment() != null,
                order.getPayment() != null && present(order.getPayment().getMethod()),
                order.getPayment() != null && present(order.getPayment().getCurrency_code()),
                order.getPayment() != null && order.getPayment().getDate_paid() != null,
                order.getPayment() != null && present(order.getPayment().getStatus()),
                shipping != null,
                shipping != null && present(shipping.getMethod()),
                shipping != null && present(shipping.getMethod_id()),
                shipping != null && shipping.getAddress() != null,
                shipping != null && present(shipping.getTracking_no()),
                cost != null,
                displayCost != null,
                cost == null ? null : cost.getCurrency_code(),
                displayCost == null ? null : displayCost.getCurrency_code(),
                present(order.getRemarks()),
                order.getTotal_count() != null,
                order.getUnique_count() != null,
                order.getTotal_weight() != null,
                order.getIs_invoiced(),
                order.getIs_filed(),
                order.getSent_drive_thru(),
                order.getRequire_insurance()
        );
    }

    private void logOrderItemCoverage(String providerTag, String orderId, List<OrderItem> orderItems) {
        log.info(
                "bricklink.order_sync.probe.item_coverage provider={} orderId={} itemCount={} hasInventoryIdCount={} hasItemCount={} hasItemNoCount={} hasItemTypeCount={} hasColorIdCount={} hasColorNameCount={} hasQuantityCount={} hasConditionCount={} hasCompletenessCount={} hasUnitPriceCount={} hasFinalUnitPriceCount={} hasCurrencyCount={} hasRemarksCount={} hasDescriptionCount={} hasWeightCount={}",
                providerTag,
                orderId,
                orderItems.size(),
                count(orderItems, item -> item.getInventory_id() != null),
                count(orderItems, item -> item.getItem() != null),
                count(orderItems, item -> item.getItem() != null && present(item.getItem().getNo())),
                count(orderItems, item -> item.getItem() != null && present(item.getItem().getType())),
                count(orderItems, item -> item.getColor_id() != null),
                count(orderItems, item -> present(item.getColor_name())),
                count(orderItems, item -> item.getQuantity() != null),
                count(orderItems, item -> present(item.getNew_or_used())),
                count(orderItems, item -> present(item.getCompleteness())),
                count(orderItems, item -> item.getUnit_price() != null),
                count(orderItems, item -> item.getUnit_price_final() != null),
                count(orderItems, item -> present(item.getCurrency_code())),
                count(orderItems, item -> present(item.getRemarks())),
                count(orderItems, item -> present(item.getDescription())),
                count(orderItems, item -> item.getWeight() != null)
        );
    }

    private int count(List<OrderItem> orderItems, java.util.function.Predicate<OrderItem> predicate) {
        return (int) orderItems.stream().filter(predicate).count();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private LocalDateTime date(LocalDateTime dateTime) {
        return dateTime;
    }

    private <T> T data(BricklinkResource<T> resource) {
        return resource == null ? null : resource.getData();
    }

    private String outcome(int ordersFetched, int ordersFailed) {
        if (ordersFailed == 0) {
            return "SUCCESS";
        }
        if (ordersFetched == 0) {
            return "FAILED";
        }
        return "PARTIAL_FAILURE";
    }
}
