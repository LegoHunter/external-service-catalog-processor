package io.legohunter.egress.fulfillment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.fulfillment")
public class FulfillmentSyncProperties {
    private String marketplaceCode = "BRICKLINK";
    private String metricsTag = "fulfillment";
    private List<String> statuses = new ArrayList<>(List.of(
            "PENDING",
            "UPDATED",
            "READY",
            "PROCESSING",
            "PAID",
            "PACKED"
    ));
    private Sync sync = new Sync();
    private Shipstation shipstation = new Shipstation();

    public String effectiveMarketplaceCode() {
        if (marketplaceCode == null || marketplaceCode.isBlank()) {
            return "BRICKLINK";
        }
        return marketplaceCode.trim().toUpperCase();
    }

    public String effectiveMetricsTag() {
        if (metricsTag == null || metricsTag.isBlank()) {
            return "fulfillment";
        }
        return metricsTag.trim();
    }

    public List<String> effectiveStatuses() {
        return statuses.stream()
                .filter(status -> status != null && !status.isBlank())
                .map(status -> status.trim().toUpperCase())
                .distinct()
                .toList();
    }

    @Getter
    @Setter
    public static class Sync {
        private Scheduled scheduled = new Scheduled();
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean apply = false;
        private boolean enabled = false;
        private int batchSize = 25;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";

        public int effectiveBatchSize() {
            return Math.max(batchSize, 1);
        }
    }

    @Getter
    @Setter
    public static class Shipstation {
        private String orderNumberPrefix = "BL-";
        private String domesticCountryCode = "US";
        private String carrierCode = "stamps_com";
        private String domesticServiceCode = "usps_priority_mail";
        private String internationalServiceCode = "usps_priority_mail_international";
        private String packageCode = "package";

        public String effectiveOrderNumberPrefix() {
            if (orderNumberPrefix == null || orderNumberPrefix.isBlank()) {
                return "BL-";
            }
            return orderNumberPrefix.trim();
        }

        public String effectiveDomesticCountryCode() {
            if (domesticCountryCode == null || domesticCountryCode.isBlank()) {
                return "US";
            }
            return domesticCountryCode.trim().toUpperCase();
        }
    }
}
