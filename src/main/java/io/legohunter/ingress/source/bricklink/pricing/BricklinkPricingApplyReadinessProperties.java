package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.apply-readiness")
public class BricklinkPricingApplyReadinessProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private String activeListingStatusCode = "ACTIVE";
    private String proposedDecisionStatusCode = BricklinkPricingDecisionService.STATUS_PROPOSED;
    private int batchSize = 25;
    private BigDecimal minimumPriceDelta = new BigDecimal("0.01");
    private Set<String> applyEligibleReasonCodes = new LinkedHashSet<>(Set.of(
            BricklinkPricingDecisionService.REASON_SINGLE_COMPARABLE_DISCOUNTED,
            BricklinkPricingDecisionService.REASON_TWO_COMPARABLES_WEIGHTED,
            BricklinkPricingDecisionService.REASON_MEAN_PLUS_STDDEV,
            BricklinkPricingDecisionService.REASON_MATCHED_LOWEST_COMPETITOR,
            BricklinkPricingDecisionService.REASON_BELOW_MIN_PRICE_CLAMPED,
            BricklinkPricingDecisionService.REASON_ABOVE_MAX_PRICE_CLAMPED
    ));
    private Scheduled scheduled = new Scheduled();

    public String effectiveActiveListingStatusCode() {
        if (activeListingStatusCode == null || activeListingStatusCode.isBlank()) {
            return "ACTIVE";
        }
        return activeListingStatusCode.trim().toUpperCase();
    }

    public String effectiveProposedDecisionStatusCode() {
        if (proposedDecisionStatusCode == null || proposedDecisionStatusCode.isBlank()) {
            return BricklinkPricingDecisionService.STATUS_PROPOSED;
        }
        return proposedDecisionStatusCode.trim().toUpperCase();
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public BigDecimal effectiveMinimumPriceDelta() {
        if (minimumPriceDelta == null || minimumPriceDelta.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return minimumPriceDelta;
    }

    public Set<String> effectiveApplyEligibleReasonCodes() {
        return applyEligibleReasonCodes == null ? Set.of() : applyEligibleReasonCodes.stream()
                .filter(reasonCode -> reasonCode != null && !reasonCode.isBlank())
                .map(reasonCode -> reasonCode.trim().toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";
    }
}
