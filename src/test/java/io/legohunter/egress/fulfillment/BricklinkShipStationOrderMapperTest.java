package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.model.v1.Address;
import com.bricklink.api.rest.model.v1.Cost;
import com.bricklink.api.rest.model.v1.Item;
import com.bricklink.api.rest.model.v1.Name;
import com.bricklink.api.rest.model.v1.Order;
import com.bricklink.api.rest.model.v1.OrderItem;
import com.bricklink.api.rest.model.v1.Payment;
import com.bricklink.api.rest.model.v1.Shipping;
import com.shipstation.api.rest.model.OrderStatus;
import com.shipstation.api.rest.model.ShipStationOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkShipStationOrderMapperTest {
    private final BricklinkShipStationOrderMapper mapper = new BricklinkShipStationOrderMapper();

    @Test
    void mapBuildsPaidDomesticShipStationOrderFromBricklinkPayload() {
        FulfillmentSyncProperties.Shipstation properties = new FulfillmentSyncProperties.Shipstation();
        Order order = order("100", "PAID");
        order.setBuyer_name("buyer-one");
        order.setBuyer_email("buyer@example.com");
        order.setRemarks("seller note");
        order.setTotal_weight(128.5);
        order.setCost(cost());
        order.setPayment(payment());
        order.setShipping(shipping("US"));
        OrderItem orderItem = orderItem(3001L, null);

        ShipStationOrder result = mapper.map(order, List.of(orderItem), properties);

        assertThat(result.getOrderNumber()).isEqualTo("BL-100");
        assertThat(result.getOrderKey()).isEqualTo("BL-100");
        assertThat(result.getOrderDate()).isEqualTo(LocalDateTime.parse("2026-06-08T10:00:00").atOffset(ZoneOffset.UTC));
        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.AWAITING_SHIPMENT.label());
        assertThat(result.getPaymentDate()).isEqualTo(LocalDateTime.parse("2026-06-08T12:00:00").atOffset(ZoneOffset.UTC));
        assertThat(result.getAmountPaid()).isEqualTo(38.0);
        assertThat(result.getCustomerUsername()).isEqualTo("buyer-one");
        assertThat(result.getCustomerEmail()).isEqualTo("buyer@example.com");
        assertThat(result.getShipTo().getName()).isEqualTo("Jane Buyer");
        assertThat(result.getShipTo().getCountry()).isEqualTo("US");
        assertThat(result.getServiceCode()).isEqualTo("usps_priority_mail");
        assertThat(result.getCarrierCode()).isEqualTo("stamps_com");
        assertThat(result.getInternalNotes()).isEqualTo("seller note");
        assertThat(result.getOrderTotal()).isEqualTo(38.0);
        assertThat(result.getShippingAmount()).isEqualTo(8.75);
        assertThat(result.getWeight().getValue()).isEqualTo(128.5);
        assertThat(result.getInsuranceOptions().getProvider()).isEqualTo("shipsurance");
        assertThat(result.getInsuranceOptions().getInsureShipment()).isTrue();
        assertThat(result.getInsuranceOptions().getInsuredValue()).isEqualTo(30.0);
        assertThat(result.getInternationalOptions()).isNull();

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems()[0].getLineItemKey()).isEqualTo("inventory:3001");
        assertThat(result.getItems()[0].getSku()).isEqualTo("1234-1");
        assertThat(result.getItems()[0].getName()).isEqualTo("(1234-1) - Test & Set");
        assertThat(result.getItems()[0].getQuantity()).isZero();
        assertThat(result.getItems()[0].getUnitPrice()).isEqualTo(5.49);
        assertThat(result.getItems()[0].getWeight().getValue()).isEqualTo(10.25);
        assertThat(result.getItems()[0].getImageUrl()).isNull();
    }

    @Test
    void mapUsesInternationalServiceForNonDomesticCountry() {
        FulfillmentSyncProperties.Shipstation properties = new FulfillmentSyncProperties.Shipstation();
        Order order = order("101", "PENDING");
        order.setCost(cost());
        order.setShipping(shipping("CA"));
        OrderItem orderItem = orderItem(3002L, 2);

        ShipStationOrder result = mapper.map(order, List.of(orderItem), properties);

        assertThat(result.getOrderStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT.label());
        assertThat(result.getServiceCode()).isEqualTo("usps_priority_mail_international");
        assertThat(result.getInsuranceOptions().getProvider()).isEqualTo("shipsurance");
        assertThat(result.getInsuranceOptions().getInsuredValue()).isEqualTo(30.0);
        assertThat(result.getInternationalOptions().getContents()).isEqualTo("merchandise");
        assertThat(result.getInternationalOptions().getNonDelivery()).isEqualTo("return_to_sender");
        assertThat(result.getInternationalOptions().getCustomsItems()).hasSize(1);
        assertThat(result.getInternationalOptions().getCustomsItems()[0].getDescription())
                .isEqualTo("Lego (Toys) - (1234-1) - Test & Set");
        assertThat(result.getInternationalOptions().getCustomsItems()[0].getQuantity()).isEqualTo(2);
        assertThat(result.getInternationalOptions().getCustomsItems()[0].getValue()).isEqualTo(5.49);
        assertThat(result.getInternationalOptions().getCustomsItems()[0].getCountryOfOrigin()).isEqualTo("US");
    }

    @Test
    void mapUsesResolvedHostedImageUrlForOrderItem() {
        FulfillmentSyncProperties.Shipstation properties = new FulfillmentSyncProperties.Shipstation();
        Order order = order("100", "PENDING");
        order.setCost(cost());
        OrderItem orderItem = orderItem(3001L, 1);

        ShipStationOrder result = mapper.map(
                order,
                List.of(orderItem),
                properties,
                Map.of("100:inventory:3001", "https://photos.example/primary.jpg")
        );

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems()[0].getImageUrl()).isEqualTo("https://photos.example/primary.jpg");
    }

    private static Order order(String orderId, String status) {
        Order order = new Order();
        order.setOrder_id(orderId);
        order.setStatus(status);
        order.setDate_ordered(LocalDateTime.parse("2026-06-08T10:00:00"));
        return order;
    }

    private static Cost cost() {
        Cost cost = new Cost();
        cost.setGrand_total(38.0);
        cost.setShipping(8.0);
        cost.setInsurance(0.75);
        cost.setSalesTax_collected_by_BL(1.25);
        return cost;
    }

    private static Payment payment() {
        Payment payment = new Payment();
        payment.setMethod("PayPal");
        payment.setDate_paid(LocalDateTime.parse("2026-06-08T12:00:00"));
        return payment;
    }

    private static Shipping shipping(String countryCode) {
        Name name = new Name();
        name.setFull("Jane Buyer");

        Address address = new Address();
        address.setName(name);
        address.setAddress1("1 Main St");
        address.setCity("Boston");
        address.setState("MA");
        address.setPostal_code("02110");
        address.setCountry_code(countryCode);

        Shipping shipping = new Shipping();
        shipping.setMethod("USPS Priority");
        shipping.setAddress(address);
        return shipping;
    }

    private static OrderItem orderItem(Long inventoryId, Integer quantity) {
        Item item = new Item();
        item.setNo("1234-1");
        item.setName("Test &amp; Set");
        item.setImage_url("https://example.com/1234-1.jpg");

        OrderItem orderItem = new OrderItem();
        orderItem.setInventory_id(inventoryId);
        orderItem.setItem(item);
        orderItem.setQuantity(quantity);
        orderItem.setUnit_price(5.99);
        orderItem.setUnit_price_final(5.49);
        orderItem.setWeight(10.25);
        return orderItem;
    }
}
