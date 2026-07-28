package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.decision")
public class BricklinkPricingDecisionProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private Set<String> priceableListingStatusCodes = Set.of("ACTIVE", "DRAFT");
    private String activeListingStatusCode = "ACTIVE";
    private int batchSize = 25;
    private boolean requireCurrentSnapshot = false;
    private String algorithmVersion = "bricklink-competitive-v1";
    private String strategyCode = "LEGACY_COMPETITIVE";
    private BigDecimal minimumPrice;
    private BigDecimal maximumPrice;
    private Scheduled scheduled = new Scheduled();

    public String effectiveActiveListingStatusCode() {
        if (activeListingStatusCode == null || activeListingStatusCode.isBlank()) {
            return "ACTIVE";
        }
        return activeListingStatusCode.trim().toUpperCase();
    }

    public Set<String> effectivePriceableListingStatusCodes() {
        if (priceableListingStatusCodes == null || priceableListingStatusCodes.isEmpty()) {
            return Set.of(effectiveActiveListingStatusCode());
        }
        Set<String> normalizedStatusCodes = priceableListingStatusCodes.stream()
                .filter(statusCode -> statusCode != null && !statusCode.isBlank())
                .map(statusCode -> statusCode.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
        return normalizedStatusCodes.isEmpty() ? Set.of(effectiveActiveListingStatusCode()) : normalizedStatusCodes;
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public String effectiveAlgorithmVersion() {
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            return "bricklink-competitive-v1";
        }
        return algorithmVersion.trim();
    }

    public String effectiveStrategyCode() {
        if (strategyCode == null || strategyCode.isBlank()) {
            return "LEGACY_COMPETITIVE";
        }
        return strategyCode.trim().toUpperCase();
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
