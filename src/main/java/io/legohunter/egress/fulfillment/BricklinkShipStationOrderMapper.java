package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Name;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Payment;
import com.bricklink.api.rest.model.v1.Shipping;
import com.shipstation.api.rest.model.Address;
import com.shipstation.api.rest.model.OrderStatus;
import com.shipstation.api.rest.model.ShipStationOrder;
import com.shipstation.api.rest.model.Weight;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Component
public class BricklinkShipStationOrderMapper {

    public ShipStationOrder map(
            Order order,
            List<OrderItem> orderItems,
            FulfillmentSyncProperties.Shipstation properties
    ) {
        if (order == null || order.getOrder_id() == null || order.getOrder_id().isBlank()) {
            throw new IllegalArgumentException("BrickLink order id is required");
        }

        String orderNumber = properties.effectiveOrderNumberPrefix() + order.getOrder_id();
        Cost cost = order.getCost();
        Payment payment = order.getPayment();
        Shipping shipping = order.getShipping();
        List<com.shipstation.api.rest.model.OrderItem> shipStationItems = orderItems.stream()
                .filter(Objects::nonNull)
                .map(this::mapItem)
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
                .shippingAmount(shippingAmount(cost))
                .customerNotes(null)
                .internalNotes(order.getRemarks())
                .paymentMethod(payment == null ? null : payment.getMethod())
                .requestedShippingService(shipping == null ? null : shipping.getMethod())
                .carrierCode(properties.getCarrierCode())
                .serviceCode(serviceCode(shipping, properties))
                .packageCode(properties.getPackageCode())
                .weight(weight(order.getTotal_weight()))
                .build();

        if (isPaid(order)) {
            shipStationOrder.updateStatusToPaid(
                    payment == null ? null : offset(payment.getDate_paid()),
                    cost == null ? null : cost.getGrand_total()
            );
        }
        return shipStationOrder;
    }

    private com.shipstation.api.rest.model.OrderItem mapItem(OrderItem orderItem) {
        Item item = orderItem.getItem();
        String itemNo = item == null ? null : item.getNo();
        Double weightValue = orderItem.getWeight() == null && item != null ? item.getWeight() : orderItem.getWeight();
        return com.shipstation.api.rest.model.OrderItem.builder()
                .lineItemKey(lineItemKey(orderItem))
                .sku(itemNo)
                .name(itemName(item))
                .imageUrl(item == null ? null : item.getImage_url())
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
        String countryCode = shipping == null || shipping.getAddress() == null
                ? null
                : countryCode(shipping.getAddress().getCountry_code());
        if (countryCode == null || countryCode.equalsIgnoreCase(properties.effectiveDomesticCountryCode())) {
            return properties.getDomesticServiceCode();
        }
        return properties.getInternationalServiceCode();
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

    private String lineItemKey(OrderItem orderItem) {
        return orderItem.getInventory_id() == null ? null : "inventory:" + orderItem.getInventory_id();
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
