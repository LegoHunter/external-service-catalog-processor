package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipstation.api.rest.model.ShipStationOrder;
import io.legohunter.data.dao.MarketplaceOrderDao;
import io.legohunter.data.dao.MarketplaceOrderPayloadDao;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
            return new FulfillmentSyncResult("NO_WORK", 0, 0, 0, 0, 0, elapsedMillis, apply, List.of(), List.of());
        }

        int ordersLoaded = 0;
        int payloadsMissing = 0;
        int ordersMapped = 0;
        int ordersFailed = 0;
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
                        properties.getShipstation()
                );
                ordersMapped++;
                mappedOrderNumbers.add(shipStationOrder.getOrderNumber());
                log.info(
                        "fulfillment.sync_job.order_mapped provider={} marketplaceOrderId={} externalOrderId={} orderNumber={} orderStatus={} itemCount={} apply={}",
                        properties.effectiveMetricsTag(),
                        candidate.getMarketplaceOrderId(),
                        candidate.getExternalOrderId(),
                        shipStationOrder.getOrderNumber(),
                        shipStationOrder.getOrderStatus(),
                        shipStationOrder.getItems().length,
                        apply
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
        log.info(
                "fulfillment.sync_job.completed provider={} marketplaceCode={} outcome={} ordersDiscovered={} ordersLoaded={} payloadsMissing={} ordersMapped={} ordersFailed={} elapsedMillis={} apply={}",
                properties.effectiveMetricsTag(),
                marketplaceCode,
                outcome,
                candidates.size(),
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                ordersFailed,
                elapsedMillis,
                apply
        );
        return new FulfillmentSyncResult(
                outcome,
                candidates.size(),
                ordersLoaded,
                payloadsMissing,
                ordersMapped,
                ordersFailed,
                elapsedMillis,
                apply,
                mappedOrderNumbers,
                failedOrderIds
        );
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

    private record LoadedBricklinkOrder(Order order, List<OrderItem> orderItems) {
    }
}
