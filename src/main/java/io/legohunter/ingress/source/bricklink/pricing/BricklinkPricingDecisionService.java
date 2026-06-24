package io.legohunter.ingress.source.bricklink.pricing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.data.dao.ConditionDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.MarketplaceListingDao;
import io.legohunter.data.dao.PricingDecisionDao;
import io.legohunter.data.dao.PricingSnapshotDao;
import io.legohunter.data.dao.PricingSnapshotListingDao;
import io.legohunter.data.dto.Condition;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.MarketplaceListing;
import io.legohunter.data.dto.PricingDecision;
import io.legohunter.data.dto.PricingSnapshot;
import io.legohunter.data.dto.PricingSnapshotListing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "lego.bricklink.pricing.decision", name = "enabled", havingValue = "true")
public class BricklinkPricingDecisionService {
    static final String STATUS_PROPOSED = "PROPOSED";
    static final String STATUS_SKIPPED = "SKIPPED";
    static final String STATUS_FAILED = "FAILED";

    static final String REASON_FIXED_PRICE_OVERRIDE = "FIXED_PRICE_OVERRIDE";
    static final String REASON_MATCHED_LOWEST_COMPETITOR = "MATCHED_LOWEST_COMPETITOR";
    static final String REASON_SINGLE_COMPARABLE_DISCOUNTED = "SINGLE_COMPARABLE_DISCOUNTED";
    static final String REASON_TWO_COMPARABLES_WEIGHTED = "TWO_COMPARABLES_WEIGHTED";
    static final String REASON_MEAN_PLUS_STDDEV = "MEAN_PLUS_STDDEV";
    static final String REASON_NO_CURRENT_SNAPSHOT = "NO_CURRENT_SNAPSHOT";
    static final String REASON_NO_EXACT_COMPARABLES = "NO_EXACT_COMPARABLES";
    static final String REASON_MISSING_INVENTORY = "MISSING_INVENTORY";
    static final String REASON_MISSING_CONDITION = "MISSING_CONDITION";
    static final String REASON_MISSING_COMPLETENESS = "MISSING_COMPLETENESS";
    static final String REASON_OUTLIER_SPREAD_TOO_HIGH = "OUTLIER_SPREAD_TOO_HIGH";
    static final String REASON_BELOW_MIN_PRICE_CLAMPED = "BELOW_MIN_PRICE_CLAMPED";
    static final String REASON_ABOVE_MAX_PRICE_CLAMPED = "ABOVE_MAX_PRICE_CLAMPED";

    private static final Map<String, BigDecimal> INSTRUCTIONS_ADJUSTMENTS = Map.of(
            "M", bd("1.2"),
            "E", bd("1.0"),
            "VG", bd("0.95"),
            "G", bd("0.9"),
            "F", bd("0.8"),
            "P", bd("0.7"),
            "CC", bd("0.5"),
            "BW", bd("0.45"),
            "MS", bd("0.4"),
            "NA", bd("1.0")
    );
    private static final Map<String, BigDecimal> BOX_ADJUSTMENTS = Map.of(
            "SL", bd("1.3"),
            "M", bd("1.2"),
            "E", bd("1.0"),
            "VG", bd("0.95"),
            "G", bd("0.9"),
            "F", bd("0.85"),
            "P", bd("0.70"),
            "MS", bd("0.5"),
            "NA", bd("1.0")
    );

    private final BricklinkPricingDecisionProperties properties;
    private final MarketplaceListingDao marketplaceListingDao;
    private final ItemInventoryDao itemInventoryDao;
    private final ConditionDao conditionDao;
    private final PricingSnapshotDao pricingSnapshotDao;
    private final PricingSnapshotListingDao pricingSnapshotListingDao;
    private final PricingDecisionDao pricingDecisionDao;
    private final ObjectMapper objectMapper;

    public BricklinkPricingDecisionResult runOnce() {
        long start = System.currentTimeMillis();
        Set<MarketplaceListing> listings = marketplaceListingDao.findPricingDecisionCandidatesByListingExternalServiceIdAndListingStatusCode(
                properties.getBricklinkExternalServiceId(),
                properties.effectiveActiveListingStatusCode(),
                properties.effectiveBatchSize(),
                properties.isRequireCurrentSnapshot()
        );

        if (listings.isEmpty()) {
            return BricklinkPricingDecisionResult.noWork(elapsedMillis(start));
        }

        DecisionCounters counters = new DecisionCounters(listings.size());
        for (MarketplaceListing listing : listings) {
            PricingDecision decision = processListing(listing);
            pricingDecisionDao.insert(decision);
            counters.record(decision);
        }

        String outcome = counters.failedDecisions > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
        return new BricklinkPricingDecisionResult(
                outcome,
                counters.listingsSelected,
                counters.decisionsWritten,
                counters.proposedDecisions,
                counters.skippedDecisions,
                counters.failedDecisions,
                elapsedMillis(start)
        );
    }

    private PricingDecision processListing(MarketplaceListing listing) {
        if (Boolean.TRUE.equals(listing.getFixedPrice())) {
            return decision(
                    listing,
                    null,
                    STATUS_SKIPPED,
                    REASON_FIXED_PRICE_OVERRIDE,
                    null,
                    money(listing.getUnitPrice()),
                    0,
                    bd("1.00"),
                    summary(Map.of("fixedPrice", true)),
                    "Marketplace listing is marked fixed price; preserving current listing price."
            );
        }

        Optional<ItemInventory> inventory = itemInventoryDao.findByItemInventoryId(listing.getItemInventoryId());
        if (inventory.isEmpty()) {
            return failed(listing, null, REASON_MISSING_INVENTORY, "No item_inventory row found for marketplace listing.");
        }

        String conditionCode = BricklinkPricingCodeNormalizer.condition(inventory.get().getNewOrUsed());
        if (conditionCode == null) {
            return failed(listing, null, REASON_MISSING_CONDITION, "Missing or unsupported item_inventory.new_or_used.");
        }

        String completenessCode = BricklinkPricingCodeNormalizer.completeness(inventory.get().getCompleteness());
        if (completenessCode == null) {
            return failed(listing, null, REASON_MISSING_COMPLETENESS, "Missing item_inventory.completeness.");
        }

        Optional<PricingSnapshot> snapshot = pricingSnapshotDao.findLatestByMarketplaceListingIdAndConditionAndCompleteness(
                listing.getMarketplaceListingId(),
                conditionCode,
                completenessCode
        );
        if (snapshot.isEmpty()) {
            return failed(listing, null, REASON_NO_CURRENT_SNAPSHOT, "No pricing snapshot exists for listing condition and completeness.");
        }

        List<PricingSnapshotListing> exactComparables = pricingSnapshotListingDao.findExactComparablesByPricingSnapshotId(snapshot.get().getPricingSnapshotId())
                .stream()
                .filter(comparable -> comparable.getUnitPrice() != null)
                .filter(comparable -> !isOwnListing(listing, comparable))
                .sorted(Comparator.comparing(PricingSnapshotListing::getUnitPrice))
                .toList();

        if (exactComparables.isEmpty()) {
            return failed(
                    listing,
                    snapshot.get(),
                    REASON_NO_EXACT_COMPARABLES,
                    "No exact comparable rows remain after condition/completeness filtering and own-listing exclusion."
            );
        }

        try {
            Calculation calculation = calculate(conditionCode, inventory.get(), exactComparables);
            PriceClamp clamp = clamp(calculation.price());
            String reasonCode = clamp.reasonCode().orElse(calculation.reasonCode());
            return decision(
                    listing,
                    snapshot.get(),
                    STATUS_PROPOSED,
                    reasonCode,
                    calculation.price(),
                    clamp.price(),
                    exactComparables.size(),
                    confidence(exactComparables.size()),
                    sourceSummary(snapshot.get(), exactComparables, calculation.reasonCode(), reasonCode),
                    null
            );
        } catch (PriceNotCalculableException e) {
            return decision(
                    listing,
                    snapshot.get(),
                    STATUS_FAILED,
                    e.reasonCode(),
                    null,
                    null,
                    exactComparables.size(),
                    bd("0.00"),
                    sourceSummary(snapshot.get(), exactComparables, e.reasonCode(), e.reasonCode()),
                    e.getMessage()
            );
        }
    }

    private PricingDecision failed(MarketplaceListing listing, PricingSnapshot snapshot, String reasonCode, String notes) {
        return decision(
                listing,
                snapshot,
                STATUS_FAILED,
                reasonCode,
                null,
                null,
                0,
                bd("0.00"),
                snapshot == null ? null : sourceSummary(snapshot, List.of(), reasonCode, reasonCode),
                notes
        );
    }

    private PricingDecision decision(
            MarketplaceListing listing,
            PricingSnapshot snapshot,
            String statusCode,
            String reasonCode,
            BigDecimal computedPrice,
            BigDecimal finalPrice,
            int comparableCount,
            BigDecimal confidence,
            String sourceSummaryJson,
            String notes
    ) {
        return PricingDecision.builder()
                .marketplaceListingId(listing.getMarketplaceListingId())
                .pricingSnapshotId(snapshot == null ? null : snapshot.getPricingSnapshotId())
                .algorithmVersion(properties.effectiveAlgorithmVersion())
                .decisionStatusCode(statusCode)
                .reasonCode(reasonCode)
                .strategyCode(properties.effectiveStrategyCode())
                .computedPrice(money(computedPrice))
                .finalPrice(money(finalPrice))
                .previousPrice(money(listing.getUnitPrice()))
                .currencyCode(currencyCode(listing))
                .comparableCount(comparableCount)
                .confidence(confidence)
                .sourceSummaryJson(sourceSummaryJson)
                .notes(notes)
                .appliedAt(null)
                .createdAt(now())
                .build();
    }

    private Calculation calculate(String conditionCode, ItemInventory inventory, List<PricingSnapshotListing> comparables) {
        BigDecimal basePrice;
        String reasonCode;
        if ("N".equals(conditionCode) && comparables.size() > 2) {
            return calculateNewWithMoreThanTwoPrices(inventory, comparables);
        } else if (comparables.size() > 2) {
            basePrice = calculateUsingMoreThanTwoPrices(comparables);
            reasonCode = REASON_MEAN_PLUS_STDDEV;
        } else if (comparables.size() > 1) {
            basePrice = calculateUsingTwoPrices(comparables);
            reasonCode = REASON_TWO_COMPARABLES_WEIGHTED;
        } else {
            basePrice = calculateUsingOnePrice(comparables.getFirst().getUnitPrice());
            reasonCode = REASON_SINGLE_COMPARABLE_DISCOUNTED;
        }
        return new Calculation(adjustPrice(inventory, basePrice), reasonCode);
    }

    private Calculation calculateNewWithMoreThanTwoPrices(ItemInventory inventory, List<PricingSnapshotListing> comparables) {
        List<PricingSnapshotListing> usComparables = comparables.stream()
                .filter(comparable -> "US".equalsIgnoreCase(clean(comparable.getSellerCountryCode())))
                .toList();
        if (!usComparables.isEmpty()) {
            BigDecimal basePrice = calculateUsingOnePrice(usComparables.getFirst().getUnitPrice());
            return new Calculation(adjustPrice(inventory, basePrice), REASON_MATCHED_LOWEST_COMPETITOR);
        }
        return new Calculation(adjustPrice(inventory, calculateUsingMoreThanTwoPrices(comparables)), REASON_MEAN_PLUS_STDDEV);
    }

    private BigDecimal calculateUsingTwoPrices(List<PricingSnapshotListing> comparables) {
        BigDecimal lowPrice = comparables.get(0).getUnitPrice();
        BigDecimal highPrice = comparables.get(1).getUnitPrice();
        return lowPrice.add(highPrice.subtract(lowPrice).multiply(bd("0.75")));
    }

    private BigDecimal calculateUsingOnePrice(BigDecimal price) {
        BigDecimal priceMinusThreePercentUpToTenDollars = price.subtract(price.multiply(bd("0.03")).min(bd("10")));
        BigDecimal priceMinusOneDollar = price.subtract(BigDecimal.ONE).max(BigDecimal.ONE);
        return priceMinusThreePercentUpToTenDollars.min(priceMinusOneDollar);
    }

    private BigDecimal calculateUsingMoreThanTwoPrices(List<PricingSnapshotListing> comparables) {
        double[] prices = comparables.stream().map(PricingSnapshotListing::getUnitPrice).mapToDouble(BigDecimal::doubleValue).toArray();
        double highest = prices[prices.length - 1];
        double secondHighest = prices[prices.length - 2];
        if (secondHighest > 0 && highest / secondHighest > 3.0d) {
            throw new PriceNotCalculableException(
                    REASON_OUTLIER_SPREAD_TOO_HIGH,
                    "Delta between highest two comparable prices is too high [%s], [%s].".formatted(highest, secondHighest)
            );
        }
        double mean = 0.0d;
        for (double price : prices) {
            mean += price;
        }
        mean = mean / prices.length;

        double variance = 0.0d;
        for (double price : prices) {
            variance += Math.pow(price - mean, 2);
        }
        double standardDeviation = Math.sqrt(variance / (prices.length - 1));
        return BigDecimal.valueOf(mean + standardDeviation);
    }

    private BigDecimal adjustPrice(ItemInventory inventory, BigDecimal price) {
        return price.multiply(priceAdjustment(inventory));
    }

    private BigDecimal priceAdjustment(ItemInventory inventory) {
        BigDecimal instructions = conditionCode(inventory.getInstructionsConditionId())
                .map(code -> INSTRUCTIONS_ADJUSTMENTS.getOrDefault(code, BigDecimal.ONE))
                .orElse(BigDecimal.ONE);
        BigDecimal box = conditionCode(inventory.getBoxConditionId())
                .map(code -> BOX_ADJUSTMENTS.getOrDefault(code, BigDecimal.ONE))
                .orElse(BigDecimal.ONE);
        return instructions.add(box).divide(bd("2.0"), 8, RoundingMode.HALF_UP);
    }

    private Optional<String> conditionCode(Integer conditionId) {
        if (conditionId == null) {
            return Optional.empty();
        }
        return conditionDao.findConditionById(conditionId)
                .map(Condition::getConditionCode)
                .map(String::trim)
                .map(String::toUpperCase);
    }

    private PriceClamp clamp(BigDecimal computedPrice) {
        BigDecimal price = money(computedPrice);
        BigDecimal minimumPrice = properties.getMinimumPrice();
        if (minimumPrice != null && price.compareTo(minimumPrice) < 0) {
            return new PriceClamp(money(minimumPrice), Optional.of(REASON_BELOW_MIN_PRICE_CLAMPED));
        }
        BigDecimal maximumPrice = properties.getMaximumPrice();
        if (maximumPrice != null && price.compareTo(maximumPrice) > 0) {
            return new PriceClamp(money(maximumPrice), Optional.of(REASON_ABOVE_MAX_PRICE_CLAMPED));
        }
        return new PriceClamp(price, Optional.empty());
    }

    private BigDecimal confidence(int comparableCount) {
        if (comparableCount >= 5) {
            return bd("0.90");
        }
        if (comparableCount >= 3) {
            return bd("0.75");
        }
        if (comparableCount == 2) {
            return bd("0.60");
        }
        if (comparableCount == 1) {
            return bd("0.40");
        }
        return bd("0.00");
    }

    private String sourceSummary(PricingSnapshot snapshot, List<PricingSnapshotListing> comparables, String baseReasonCode, String finalReasonCode) {
        BigDecimal lowestPrice = comparables.isEmpty() ? null : money(comparables.getFirst().getUnitPrice());
        BigDecimal highestPrice = comparables.isEmpty() ? null : money(comparables.getLast().getUnitPrice());
        return summary(Map.of(
                "pricingSnapshotId", snapshot.getPricingSnapshotId(),
                "sourceItemKey", nullToEmpty(snapshot.getSourceItemKey()),
                "sourceUniqueKey", nullToEmpty(snapshot.getSourceUniqueKey()),
                "targetConditionCode", nullToEmpty(snapshot.getItemConditionCode()),
                "targetCompletenessCode", nullToEmpty(snapshot.getCompletenessCode()),
                "exactComparableCount", comparables.size(),
                "lowestComparablePrice", nullToEmpty(lowestPrice),
                "highestComparablePrice", nullToEmpty(highestPrice),
                "baseReasonCode", baseReasonCode,
                "finalReasonCode", finalReasonCode
        ));
    }

    private String summary(Map<String, ?> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize pricing decision source summary", e);
        }
    }

    private String currencyCode(MarketplaceListing listing) {
        return clean(listing.getCurrencyCode()) == null ? "USD" : listing.getCurrencyCode().trim().toUpperCase();
    }

    private boolean isOwnListing(MarketplaceListing listing, PricingSnapshotListing comparable) {
        String listingExternalId = clean(listing.getExternalListingId());
        return listingExternalId != null && Objects.equals(listingExternalId, clean(comparable.getExternalListingId()));
    }

    private Object nullToEmpty(Object value) {
        return value == null ? "" : value;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private long elapsedMillis(long start) {
        return System.currentTimeMillis() - start;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record Calculation(BigDecimal price, String reasonCode) {
    }

    private record PriceClamp(BigDecimal price, Optional<String> reasonCode) {
    }

    private static final class DecisionCounters {
        private final int listingsSelected;
        private int decisionsWritten;
        private int proposedDecisions;
        private int skippedDecisions;
        private int failedDecisions;

        private DecisionCounters(int listingsSelected) {
            this.listingsSelected = listingsSelected;
        }

        private void record(PricingDecision decision) {
            decisionsWritten++;
            if (STATUS_PROPOSED.equals(decision.getDecisionStatusCode())) {
                proposedDecisions++;
            } else if (STATUS_SKIPPED.equals(decision.getDecisionStatusCode())) {
                skippedDecisions++;
            } else if (STATUS_FAILED.equals(decision.getDecisionStatusCode())) {
                failedDecisions++;
            }
        }
    }

    private static class PriceNotCalculableException extends RuntimeException {
        private final String reasonCode;

        private PriceNotCalculableException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        private String reasonCode() {
            return reasonCode;
        }
    }
}
