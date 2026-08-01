package io.legohunter.ingress.source.bricklink.pricing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.bricklink.marketplace-sync")
public class BricklinkMarketplaceSyncProperties {
    private boolean enabled = false;
    private String mode = BricklinkMarketplaceSyncMode.DRY_RUN.name();
    private Integer bricklinkExternalServiceId = 2;
    private int batchSize = 5;
    private Duration retryBackoff = Duration.ofHours(6);
    private String environmentCode = "local";
    private boolean production = false;
    private boolean requireSystemRemarksBlock = true;
    private boolean nonProdRequireStockRoom = true;
    private String nonProdStockRoomId = "A";
    private int remarksMaxLength = 1024;
    private Scheduled scheduled = new Scheduled();

    public BricklinkMarketplaceSyncMode effectiveMode() {
        if ("local".equals(effectiveEnvironmentCode())) {
            return BricklinkMarketplaceSyncMode.DRY_RUN;
        }
        if (mode == null || mode.isBlank()) {
            return BricklinkMarketplaceSyncMode.DRY_RUN;
        }
        return BricklinkMarketplaceSyncMode.valueOf(mode.trim().toUpperCase().replace('-', '_'));
    }

    public int effectiveBatchSize() {
        return Math.max(1, batchSize);
    }

    public Duration effectiveRetryBackoff() {
        return retryBackoff == null || retryBackoff.isNegative() ? Duration.ofHours(6) : retryBackoff;
    }

    public String effectiveEnvironmentCode() {
        if (environmentCode == null || environmentCode.isBlank()) {
            return "local";
        }
        return environmentCode.trim().toLowerCase();
    }

    public boolean nonProdEnvironment() {
        return !production;
    }

    public String effectiveNonProdStockRoomId() {
        if (nonProdStockRoomId == null || nonProdStockRoomId.isBlank()) {
            return "A";
        }
        return nonProdStockRoomId.trim().toUpperCase();
    }

    public int effectiveRemarksMaxLength() {
        return Math.max(1, remarksMaxLength);
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
