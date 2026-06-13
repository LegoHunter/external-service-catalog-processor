package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Name;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Payment;
import com.bricklink.api.rest.model.v1.Shipping;
import com.shipstation.api.rest.model.Address;
import com.shipstation.api.rest.model.CustomsItem;
import com.shipstation.api.rest.model.InsuranceOptions;
import com.shipstation.api.rest.model.InternationalOptions;
import com.shipstation.api.rest.model.OrderStatus;
import com.shipstation.api.rest.model.ShipStationOrder;
import com.shipstation.api.rest.model.Weight;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

@Component
public class BricklinkShipStationOrderMapper {

    public ShipStationOrder map(
            Order order,
            List<OrderItem> orderItems,
            FulfillmentSyncProperties.Shipstation properties
    ) {
        return map(order, orderItems, properties, Map.of());
    }

    public ShipStationOrder map(
            Order order,
            List<OrderItem> orderItems,
            FulfillmentSyncProperties.Shipstation properties,
            Map<String, String> imageUrlsByExternalOrderItemId
    ) {
        if (order == null || order.getOrder_id() == null || order.getOrder_id().isBlank()) {
            throw new IllegalArgumentException("BrickLink order id is required");
        }

        String orderNumber = properties.effectiveOrderNumberPrefix() + order.getOrder_id();
        Cost cost = order.getCost();
        Payment payment = order.getPayment();
        Shipping shipping = order.getShipping();
        Double shippingAmount = shippingAmount(cost);
        List<OrderItem> safeOrderItems = orderItems == null ? List.of() : orderItems;
        Map<String, String> safeImageUrls = imageUrlsByExternalOrderItemId == null ? Map.of() : imageUrlsByExternalOrderItemId;
        List<com.shipstation.api.rest.model.OrderItem> shipStationItems = IntStream.range(0, safeOrderItems.size())
                .mapToObj(index -> mapItem(order.getOrder_id(), safeOrderItems.get(index), index, safeImageUrls))
                .filter(Objects::nonNull)
                .toList();

        ShipStationOrder shipStationOrder = ShipStationOrder.builder()
                .orderNumber(orderNumber)
                .orderKey(orderNumber)
                .orderDate(offset(order.getDate_ordered()))
                .orderStatus(orderStatus(order))
                .customerUsername(order.getBuyer_name())
                .customerEmail(order.getBuyer_email())
                .billTo(mapAddress(shipping))
                .shipTo(mapAddress(shipping))
                .shipStationOrderItems(shipStationItems)
                .orderTotal(cost == null ? null : cost.getGrand_total())
                .taxAmount(cost == null ? null : cost.getSalesTax_collected_by_BL())
                .shippingAmount(shippingAmount)
                .customerNotes(null)
                .internalNotes(order.getRemarks())
                .paymentMethod(payment == null ? null : payment.getMethod())
                .requestedShippingService(shipping == null ? null : shipping.getMethod())
                .carrierCode(properties.getCarrierCode())
                .serviceCode(serviceCode(shipping, properties))
                .packageCode(properties.getPackageCode())
                .weight(weight(order.getTotal_weight()))
                .insuranceOptions(insuranceOptions(cost == null ? null : cost.getGrand_total(), shippingAmount, properties))
                .internationalOptions(internationalOptions(shipping, shipStationItems, properties))
                .build();

        if (isPaid(order)) {
            shipStationOrder.updateStatusToPaid(
                    payment == null ? null : offset(payment.getDate_paid()),
                    cost == null ? null : cost.getGrand_total()
            );
        }
        return shipStationOrder;
    }

    private com.shipstation.api.rest.model.OrderItem mapItem(
            String externalOrderId,
            OrderItem orderItem,
            int index,
            Map<String, String> imageUrlsByExternalOrderItemId
    ) {
        if (orderItem == null) {
            return null;
        }
        Item item = orderItem.getItem();
        String itemNo = item == null ? null : item.getNo();
        Double weightValue = orderItem.getWeight() == null && item != null ? item.getWeight() : orderItem.getWeight();
        return com.shipstation.api.rest.model.OrderItem.builder()
                .lineItemKey(lineItemKey(orderItem))
                .sku(itemNo)
                .name(itemName(item))
                .imageUrl(imageUrlsByExternalOrderItemId.get(externalOrderItemId(externalOrderId, orderItem, index)))
                .weight(weight(weightValue))
                .quantity(Objects.requireNonNullElse(orderItem.getQuantity(), 0))
                .unitPrice(orderItem.getUnit_price_final() == null ? orderItem.getUnit_price() : orderItem.getUnit_price_final())
                .build();
    }

    private Address mapAddress(Shipping shipping) {
        if (shipping == null || shipping.getAddress() == null) {
            return null;
        }
        com.bricklink.api.rest.model.v1.Address address = shipping.getAddress();
        return Address.builder()
                .name(name(address.getName()))
                .street1(address.getAddress1())
                .street2(address.getAddress2())
                .street3(address.getFull())
                .city(address.getCity())
                .state(address.getState())
                .postalCode(address.getPostal_code())
                .country(countryCode(address.getCountry_code()))
                .build();
    }

    private String serviceCode(Shipping shipping, FulfillmentSyncProperties.Shipstation properties) {
        if (isInternational(shipping, properties)) {
            return properties.getInternationalServiceCode();
        }
        return properties.getDomesticServiceCode();
    }

    private boolean isInternational(Shipping shipping, FulfillmentSyncProperties.Shipstation properties) {
        String countryCode = shipping == null || shipping.getAddress() == null
                ? null
                : countryCode(shipping.getAddress().getCountry_code());
        return countryCode != null && !countryCode.equalsIgnoreCase(properties.effectiveDomesticCountryCode());
    }

    private String orderStatus(Order order) {
        if (isCancelled(order)) {
            return OrderStatus.CANCELLED.label();
        }
        return OrderStatus.AWAITING_PAYMENT.label();
    }

    private Double shippingAmount(Cost cost) {
        if (cost == null || cost.getShipping() == null && cost.getInsurance() == null) {
            return null;
        }
        return Objects.requireNonNullElse(cost.getShipping(), 0.0)
                + Objects.requireNonNullElse(cost.getInsurance(), 0.0);
    }

    private Weight weight(Double value) {
        return value == null ? null : Weight.builder()
                .value(value)
                .units("grams")
                .build();
    }

    private InsuranceOptions insuranceOptions(
            Double orderTotal,
            Double shippingAmount,
            FulfillmentSyncProperties.Shipstation properties
    ) {
        return InsuranceOptions.builder()
                .provider(properties.effectiveInsuranceProvider())
                .insureShipment(true)
                .insuredValue(insuredValue(orderTotal, shippingAmount))
                .build();
    }

    private Double insuredValue(Double orderTotal, Double shippingAmount) {
        if (orderTotal == null) {
            return null;
        }
        return Math.ceil(orderTotal - Objects.requireNonNullElse(shippingAmount, 0.0));
    }

    private InternationalOptions internationalOptions(
            Shipping shipping,
            List<com.shipstation.api.rest.model.OrderItem> orderItems,
            FulfillmentSyncProperties.Shipstation properties
    ) {
        if (!isInternational(shipping, properties)) {
            return null;
        }
        CustomsItem[] customsItems = orderItems.stream()
                .map(orderItem -> CustomsItem.builder()
                        .customsItemId(null)
                        .description("Lego (Toys) - " + orderItem.getName())
                        .quantity(orderItem.getQuantity())
                        .value(orderItem.getUnitPrice())
                        .harmonizedTariffCode(null)
                        .countryOfOrigin(properties.effectiveCustomsCountryOfOrigin())
                        .build())
                .toArray(CustomsItem[]::new);

        return InternationalOptions.builder()
                .contents(properties.effectiveInternationalContents())
                .nonDelivery(properties.effectiveInternationalNonDelivery())
                .customsItems(customsItems)
                .build();
    }

    private String lineItemKey(OrderItem orderItem) {
        return orderItem.getInventory_id() == null ? null : "inventory:" + orderItem.getInventory_id();
    }

    private String externalOrderItemId(String externalOrderId, OrderItem orderItem, int index) {
        if (orderItem.getInventory_id() != null) {
            return externalOrderId + ":inventory:" + orderItem.getInventory_id();
        }
        return externalOrderId + ":line:" + index;
    }

    private String itemName(Item item) {
        if (item == null) {
            return null;
        }
        String itemNo = item.getNo();
        String name = item.getName();
        if (itemNo == null || itemNo.isBlank()) {
            return HtmlUtils.htmlUnescape(name);
        }
        if (name == null || name.isBlank()) {
            return itemNo;
        }
        return HtmlUtils.htmlUnescape("(%s) - %s".formatted(itemNo, name));
    }

    private String name(Name name) {
        if (name == null) {
            return null;
        }
        if (name.getFull() != null && !name.getFull().isBlank()) {
            return name.getFull();
        }
        return join(name.getFirst(), name.getLast());
    }

    private String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " " + second;
    }

    private String countryCode(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return null;
        }
        String normalized = countryCode.trim().toUpperCase();
        return "UK".equals(normalized) ? "GB" : normalized;
    }

    private OffsetDateTime offset(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atOffset(ZoneOffset.UTC);
    }

    private boolean isPaid(Order order) {
        try {
            return order.getStatus() != null && order.isPaid();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isCancelled(Order order) {
        try {
            return order.getStatus() != null && order.isCancelled();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
