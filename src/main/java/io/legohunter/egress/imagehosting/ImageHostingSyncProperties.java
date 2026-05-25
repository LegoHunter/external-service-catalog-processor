package io.legohunter.egress.imagehosting;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.image-hosting")
public class ImageHostingSyncProperties {
    private String defaultProvider = "flickr";
    private Sync sync = new Sync();
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public ResolvedProvider resolveProvider(String requestedProvider, Integer requestedExternalServiceId) {
        String providerKey = Optional.ofNullable(requestedProvider)
                .filter(value -> !value.isBlank())
                .orElse(defaultProvider);
        Provider provider = providers.get(providerKey);
        Integer externalServiceId = Optional.ofNullable(requestedExternalServiceId)
                .or(() -> Optional.ofNullable(provider).map(Provider::getExternalServiceId))
                .orElse(sync.getExternalServiceId());

        if (externalServiceId == null) {
            throw new IllegalArgumentException("externalServiceId is required when no configured image-hosting provider is selected");
        }

        String metricsTag = Optional.ofNullable(provider)
                .map(Provider::getMetricsTag)
                .filter(value -> !value.isBlank())
                .orElse(providerKey);
        String displayName = Optional.ofNullable(provider)
                .map(Provider::getDisplayName)
                .filter(value -> !value.isBlank())
                .orElse(providerKey);
        return new ResolvedProvider(providerKey, displayName, metricsTag, externalServiceId);
    }

    public Path getTempDirectory() {
        return sync.getTempDirectory();
    }

    @Getter
    @Setter
    public static class Sync {
        private Integer externalServiceId = 10;
        private Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"), "lego-data-ingress-image-hosting");
        private Scheduled scheduled = new Scheduled();
    }

    @Getter
    @Setter
    public static class Scheduled {
        private boolean enabled = false;
        private int batchSize = 25;
        private int concurrency = 2;
        private boolean retryFailed = false;
        private long fixedDelayMs = 300_000L;
        private long initialDelayMs = 30_000L;
        private String lockAtMostFor = "10m";
        private String lockAtLeastFor = "0s";

        public int effectiveBatchSize() {
            return Math.max(1, batchSize);
        }

        public int effectiveConcurrency() {
            return Math.max(1, concurrency);
        }
    }

    @Getter
    @Setter
    public static class Provider {
        private Boolean enabled = false;
        private Integer externalServiceId;
        private String displayName;
        private String metricsTag;
    }

    public record ResolvedProvider(String provider, String displayName, String metricsTag, Integer externalServiceId) {
    }
}
