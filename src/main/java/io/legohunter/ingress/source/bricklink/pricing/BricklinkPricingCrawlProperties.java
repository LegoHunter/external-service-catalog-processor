package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.pricing.crawl")
public class BricklinkPricingCrawlProperties {
    private boolean enabled = false;
    private Integer bricklinkExternalServiceId = 2;
    private String activeListingStatusCode = "ACTIVE";
    private String catalogItemType = "S";
    private int batchSize = 25;
    private int resultsPerPage = 500;
    private int maxAttempts = 3;
    private Scheduled scheduled = new Scheduled();

    public String effectiveActiveListingStatusCode() {
        if (activeListingStatusCode == null || activeListingStatusCode.isBlank()) {
            return "ACTIVE";
        }
        return activeListingStatusCode.trim().toUpperCase();
    }

    public String effectiveCatalogItemType() {
        if (catalogItemType == null || catalogItemType.isBlank()) {
            return "S";
        }
        return catalogItemType.trim().toUpperCase();
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public int effectiveResultsPerPage() {
        return Math.max(1, resultsPerPage);
    }

    public int effectiveMaxAttempts() {
        return Math.max(1, maxAttempts);
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "30m";
        private String lockAtLeastFor = "0s";
    }
}
