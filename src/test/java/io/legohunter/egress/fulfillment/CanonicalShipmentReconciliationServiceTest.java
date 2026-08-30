package io.legohunter.egress.fulfillment;

import com.shipstation.api.rest.model.ShipStationOrder;
import io.legohunter.data.dao.CarrierDao;
import io.legohunter.data.dao.MarketplaceOrderTransactionLinkDao;
import io.legohunter.data.dao.ShipmentDao;
import io.legohunter.data.dao.TransactionsDao;
import io.legohunter.data.dto.Carrier;
import io.legohunter.data.dto.MarketplaceOrder;
import io.legohunter.data.dto.MarketplaceOrderTransactionLink;
import io.legohunter.data.dto.Shipment;
import io.legohunter.data.dto.Transactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalShipmentReconciliationServiceTest {
    private MarketplaceOrderTransactionLinkDao linkDao;
    private TransactionsDao transactionsDao;
    private ShipmentDao shipmentDao;
    private CarrierDao carrierDao;
    private CanonicalShipmentReconciliationService service;

    @BeforeEach
    void setUp() {
        linkDao = mock(MarketplaceOrderTransactionLinkDao.class);
        transactionsDao = mock(TransactionsDao.class);
        shipmentDao = mock(ShipmentDao.class);
        carrierDao = mock(CarrierDao.class);
        service = new CanonicalShipmentReconciliationService(linkDao, transactionsDao, shipmentDao, carrierDao);
    }

    @Test
    void openOrderIsNotEligibleForFulfillment() {
        MarketplaceOrder marketplaceOrder = marketplaceOrder();
        when(linkDao.findByMarketplaceOrderId(101)).thenReturn(Set.of(header("OPEN", 501L)));

        assertThat(service.findInvoicedOrder(marketplaceOrder)).isEmpty();

        verify(transactionsDao, never()).findById(any());
    }

    @Test
    void invoicedOrderUsesCanonicalHeaderAndOnlyActiveTransactionItems() {
        MarketplaceOrder marketplaceOrder = marketplaceOrder();
        when(linkDao.findByMarketplaceOrderId(101)).thenReturn(Set.of(
                header("INVOICED", 501L),
                line("ACTIVE", 501L, 601L),
                line("ACTIVE", 501L, 602L),
                line("UNLINKED", 501L, 603L),
                line("ACTIVE", 999L, 604L)
        ));
        Transactions transaction = Transactions.builder().transactionId(501L).transactionOrderId("BL-100").build();
        when(transactionsDao.findById(501L)).thenReturn(Optional.of(transaction));

        assertThat(service.findInvoicedOrder(marketplaceOrder))
                .hasValueSatisfying(canonical -> {
                    assertThat(canonical.transaction()).isSameAs(transaction);
                    assertThat(canonical.transactionItemIds()).containsExactlyInAnyOrder(601L, 602L);
                });
    }

    @Test
    void trackedShipStationShipmentPersistsUsingAuthoritativeCarrierAndLinksAllOrderItems() {
        CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder canonicalOrder = canonicalOrder();
        com.shipstation.api.rest.model.Shipment shipStationShipment = com.shipstation.api.rest.model.Shipment.builder()
                .shipmentId(7001L)
                .trackingNumber("9400111899223855555555")
                .carrierCode("stamps_com")
                .serviceCode("usps_ground_advantage")
                .shipDate(OffsetDateTime.parse("2026-08-30T12:00:00Z"))
                .voided(false)
                .build();
        Carrier usps = Carrier.builder()
                .carrierCode("USPS")
                .carrierName("United States Postal Service")
                .trackingUrlPattern("https://tools.usps.com/go/TrackConfirmAction.action?tLabels=%s")
                .build();
        when(carrierDao.findCarrierByCode("STAMPS_COM")).thenReturn(Optional.empty());
        when(carrierDao.findCarrierByCode("USPS")).thenReturn(Optional.of(usps));
        when(shipmentDao.findByPlatformAndExternalShipmentId("SHIPSTATION", "7001")).thenReturn(Optional.empty());
        when(shipmentDao.insert(any(Shipment.class))).thenAnswer(invocation -> {
            Shipment shipment = invocation.getArgument(0);
            shipment.setShipmentId(801L);
            return shipment;
        });

        CanonicalShipmentReconciliationService.CanonicalShipment result = service.persistTrackedShipment(
                canonicalOrder,
                shipStationShipment,
                ShipStationOrder.builder().orderId(42L).carrierCode("stamps_com").build()
        );

        assertThat(result.carrier()).isSameAs(usps);
        assertThat(result.shipment()).satisfies(shipment -> {
            assertThat(shipment.getShipmentId()).isEqualTo(801L);
            assertThat(shipment.getExternalShipmentId()).isEqualTo("7001");
            assertThat(shipment.getCarrierCode()).isEqualTo("USPS");
            assertThat(shipment.getShipmentTrackingNumber()).isEqualTo("9400111899223855555555");
            assertThat(shipment.getShipmentDate()).isEqualTo(java.time.LocalDate.parse("2026-08-30"));
            assertThat(shipment.getServiceCode()).isEqualTo("usps_ground_advantage");
        });
        verify(shipmentDao).linkTransactionItem(601L, 801L);
        verify(shipmentDao).linkTransactionItem(602L, 801L);
    }

    @Test
    void existingShipmentIsUpdatedByExternalShipStationId() {
        Shipment existing = Shipment.builder().shipmentId(801L).externalShipmentId("7001").build();
        com.shipstation.api.rest.model.Shipment shipStationShipment = com.shipstation.api.rest.model.Shipment.builder()
                .shipmentId(7001L)
                .trackingNumber("1Z999AA10123456784")
                .carrierCode("ups")
                .createDate(OffsetDateTime.parse("2026-08-30T12:00:00Z"))
                .voided(false)
                .build();
        Carrier ups = Carrier.builder().carrierCode("UPS").build();
        when(carrierDao.findCarrierByCode("UPS")).thenReturn(Optional.of(ups));
        when(shipmentDao.findByPlatformAndExternalShipmentId("SHIPSTATION", "7001")).thenReturn(Optional.of(existing));

        CanonicalShipmentReconciliationService.CanonicalShipment result = service.persistTrackedShipment(
                canonicalOrder(),
                shipStationShipment,
                ShipStationOrder.builder().carrierCode("ups").serviceCode("ups_ground").build()
        );

        ArgumentCaptor<Shipment> shipmentCaptor = ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentDao).upsert(shipmentCaptor.capture());
        assertThat(result.shipment().getShipmentId()).isEqualTo(801L);
        assertThat(shipmentCaptor.getValue().getServiceCode()).isEqualTo("ups_ground");
        verify(shipmentDao, never()).insert(any());
    }

    @Test
    void unmappedCarrierStopsShipmentPersistence() {
        com.shipstation.api.rest.model.Shipment shipStationShipment = com.shipstation.api.rest.model.Shipment.builder()
                .shipmentId(7001L)
                .trackingNumber("TRACK-1")
                .carrierCode("unknown_carrier")
                .voided(false)
                .build();
        when(carrierDao.findCarrierByCode("UNKNOWN_CARRIER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.persistTrackedShipment(
                canonicalOrder(),
                shipStationShipment,
                ShipStationOrder.builder().build()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not map to an authoritative carrier");

        verify(shipmentDao, never()).insert(any());
        verify(shipmentDao, never()).upsert(any());
    }

    private static MarketplaceOrder marketplaceOrder() {
        return MarketplaceOrder.builder().marketplaceOrderId(101).externalOrderId("BL-100").build();
    }

    private static MarketplaceOrderTransactionLink header(String status, Long transactionId) {
        return MarketplaceOrderTransactionLink.builder()
                .marketplaceOrderTransactionLinkId(1)
                .transactionId(transactionId)
                .linkTypeCode("ORDER")
                .linkStatusCode(status)
                .build();
    }

    private static MarketplaceOrderTransactionLink line(String status, Long transactionId, Long transactionItemId) {
        return MarketplaceOrderTransactionLink.builder()
                .marketplaceOrderTransactionLinkId(transactionItemId.intValue())
                .transactionId(transactionId)
                .transactionItemId(transactionItemId)
                .linkTypeCode("ORDER_ITEM")
                .linkStatusCode(status)
                .build();
    }

    private static CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder canonicalOrder() {
        return new CanonicalShipmentReconciliationService.CanonicalFulfillmentOrder(
                Transactions.builder().transactionId(501L).transactionOrderId("BL-100").build(),
                List.of(601L, 602L)
        );
    }
}
