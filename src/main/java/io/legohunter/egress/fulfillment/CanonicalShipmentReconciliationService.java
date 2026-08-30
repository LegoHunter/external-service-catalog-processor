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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Bridges the staged BrickLink fulfillment flow to the Phase 2 canonical transaction projection.
 *
 * <p>A ShipStation draft remains sourced from the preserved BrickLink payload because that is the
 * integration-ready document. This service makes the canonical transaction the lifecycle gate and
 * persists the confirmed shipment only after ShipStation supplies a non-voided tracked shipment.</p>
 */
@Service
@RequiredArgsConstructor
public class CanonicalShipmentReconciliationService {
    static final String ORDER_LINK = "ORDER";
    static final String ORDER_ITEM_LINK = "ORDER_ITEM";
    static final String INVOICED = "INVOICED";
    static final String ACTIVE = "ACTIVE";
    static final String SHIPSTATION = "SHIPSTATION";

    private final MarketplaceOrderTransactionLinkDao linkDao;
    private final TransactionsDao transactionsDao;
    private final ShipmentDao shipmentDao;
    private final CarrierDao carrierDao;

    /**
     * Finds the immutable Phase 2 transaction projection eligible for a ShipStation draft.
     * Open orders intentionally return empty so a buyer can continue changing the BrickLink order.
     */
    public Optional<CanonicalFulfillmentOrder> findInvoicedOrder(MarketplaceOrder marketplaceOrder) {
        if (marketplaceOrder == null || marketplaceOrder.getMarketplaceOrderId() == null) {
            throw new IllegalArgumentException("A persisted marketplace order is required for fulfillment");
        }

        Set<MarketplaceOrderTransactionLink> links = linkDao.findByMarketplaceOrderId(
                marketplaceOrder.getMarketplaceOrderId()
        );
        Optional<MarketplaceOrderTransactionLink> header = links.stream()
                .filter(link -> ORDER_LINK.equals(link.getLinkTypeCode()))
                .findFirst();
        if (header.isEmpty() || !INVOICED.equals(header.get().getLinkStatusCode())) {
            return Optional.empty();
        }

        Long transactionId = header.get().getTransactionId();
        if (transactionId == null) {
            throw new IllegalStateException("Invoiced BrickLink order link is missing its canonical transaction id");
        }
        Transactions transaction = transactionsDao.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Invoiced BrickLink order link references a missing canonical transaction: " + transactionId
                ));
        List<Long> transactionItemIds = links.stream()
                .filter(link -> ORDER_ITEM_LINK.equals(link.getLinkTypeCode()))
                .filter(link -> ACTIVE.equals(link.getLinkStatusCode()))
                .filter(link -> transactionId.equals(link.getTransactionId()))
                .map(MarketplaceOrderTransactionLink::getTransactionItemId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (transactionItemIds.isEmpty()) {
            throw new IllegalStateException(
                    "Invoiced BrickLink order has no active canonical transaction-item links: "
                            + marketplaceOrder.getMarketplaceOrderId()
            );
        }
        return Optional.of(new CanonicalFulfillmentOrder(transaction, transactionItemIds));
    }

    /**
     * Idempotently records a non-voided tracked ShipStation shipment and associates it to the order's
     * active canonical transaction items. ShipStation does not expose per-line allocation in this flow,
     * so the confirmed shipment is associated with the complete invoiced order.
     */
    @Transactional
    public CanonicalShipment persistTrackedShipment(
            CanonicalFulfillmentOrder canonicalOrder,
            com.shipstation.api.rest.model.Shipment shipStationShipment,
            ShipStationOrder shipStationOrder
    ) {
        if (canonicalOrder == null || shipStationShipment == null) {
            throw new IllegalArgumentException("Canonical order and ShipStation shipment are required");
        }
        if (Boolean.TRUE.equals(shipStationShipment.getVoided())) {
            throw new IllegalArgumentException("A voided ShipStation shipment cannot be reconciled");
        }
        String trackingNumber = required(shipStationShipment.getTrackingNumber(), "ShipStation tracking number");
        String externalShipmentId = externalShipmentId(shipStationShipment);
        Carrier carrier = resolveCarrier(shipStationShipment.getCarrierCode(),
                shipStationOrder == null ? null : shipStationOrder.getCarrierCode());

        Shipment desired = Shipment.builder()
                .externalShipmentId(externalShipmentId)
                .shipmentDate(shipmentDate(shipStationShipment))
                .shipmentTrackingNumber(trackingNumber)
                .carrierCode(carrier.getCarrierCode())
                .fulfillmentPlatformCode(SHIPSTATION)
                .serviceCode(firstPresent(
                        shipStationShipment.getServiceCode(),
                        shipStationOrder == null ? null : shipStationOrder.getServiceCode()
                ))
                .build();

        Shipment persisted = shipmentDao.findByPlatformAndExternalShipmentId(SHIPSTATION, externalShipmentId)
                .map(existing -> {
                    desired.setShipmentId(existing.getShipmentId());
                    shipmentDao.upsert(desired);
                    return desired;
                })
                .orElseGet(() -> shipmentDao.insert(desired));
        if (persisted.getShipmentId() == null) {
            throw new IllegalStateException("Canonical shipment insert did not produce a shipment id");
        }
        canonicalOrder.transactionItemIds().forEach(transactionItemId ->
                shipmentDao.linkTransactionItem(transactionItemId, persisted.getShipmentId())
        );
        return new CanonicalShipment(persisted, carrier);
    }

    private String externalShipmentId(com.shipstation.api.rest.model.Shipment shipment) {
        if (shipment.getShipmentId() == null) {
            throw new IllegalStateException("ShipStation shipment id is required for canonical reconciliation");
        }
        return shipment.getShipmentId().toString();
    }

    private LocalDate shipmentDate(com.shipstation.api.rest.model.Shipment shipment) {
        OffsetDateTime date = shipment.getShipDate() == null ? shipment.getCreateDate() : shipment.getShipDate();
        return date == null ? LocalDate.now(ZoneOffset.UTC) : date.toLocalDate();
    }

    private Carrier resolveCarrier(String shipmentCarrierCode, String orderCarrierCode) {
        String sourceCode = firstPresent(shipmentCarrierCode, orderCarrierCode);
        if (sourceCode == null) {
            throw new IllegalStateException("ShipStation did not provide a carrier code for a tracked shipment");
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String normalized = sourceCode.trim().toLowerCase(Locale.ROOT);
        candidates.add(sourceCode.trim().toUpperCase(Locale.ROOT));
        if (normalized.startsWith("usps") || normalized.startsWith("stamps")) {
            candidates.add("USPS");
        } else if (normalized.startsWith("ups")) {
            candidates.add("UPS");
        } else if (normalized.startsWith("fedex")) {
            candidates.add("FEDEX");
        } else if (normalized.startsWith("dhl")) {
            candidates.add("DHL");
        }

        return candidates.stream()
                .map(carrierDao::findCarrierByCode)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ShipStation carrier code [%s] does not map to an authoritative carrier".formatted(sourceCode)
                ));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required");
        }
        return value;
    }

    private String firstPresent(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    public record CanonicalFulfillmentOrder(Transactions transaction, List<Long> transactionItemIds) {
    }

    public record CanonicalShipment(Shipment shipment, Carrier carrier) {
    }
}
