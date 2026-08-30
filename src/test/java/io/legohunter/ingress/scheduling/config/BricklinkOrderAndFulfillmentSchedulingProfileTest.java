package io.legohunter.ingress.scheduling.config;

import io.legohunter.egress.fulfillment.FulfillmentSyncProperties;
import io.legohunter.ingress.source.bricklink.orders.BricklinkOrderSyncProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Arrays;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BricklinkOrderAndFulfillmentSchedulingProfileTest {
    private static final String ORDER_SCHEDULE = "lego.bricklink.orders.sync.scheduled.";
    private static final String FULFILLMENT_SCHEDULE = "lego.fulfillment.sync.scheduled.";

    @Test
    void unboundPropertiesUseSafeHourlyDefaults() {
        BricklinkOrderSyncProperties orderProperties = new BricklinkOrderSyncProperties();
        FulfillmentSyncProperties fulfillmentProperties = new FulfillmentSyncProperties();

        assertThat(orderProperties.getScheduled().getFixedDelayMs()).isEqualTo(3_600_000L);
        assertThat(orderProperties.getScheduled().isEnabled()).isFalse();
        assertThat(fulfillmentProperties.getSync().getScheduled().getFixedDelayMs()).isEqualTo(3_600_000L);
        assertThat(fulfillmentProperties.getSync().getScheduled().isEnabled()).isFalse();
    }

    @Test
    void baseConfigurationIsProductionSafe() {
        EffectiveSchedule schedule = effectiveSchedule();

        assertThat(schedule.orderFixedDelayMs()).isEqualTo(3_600_000L);
        assertThat(schedule.fulfillmentFixedDelayMs()).isEqualTo(3_600_000L);
        assertThat(schedule.orderEnabled()).isFalse();
        assertThat(schedule.fulfillmentEnabled()).isFalse();
    }

    @Test
    void defaultLocalProfilesResolveToOneMinuteForBothJobs() {
        Properties base = load("application.yml");
        String[] defaultProfiles = Arrays.stream(required(base, "spring.profiles.default").split(","))
                .map(String::trim)
                .toArray(String[]::new);

        assertThat(defaultProfiles).containsExactly("sandbox", "local");
        assertCadence(effectiveSchedule(defaultProfiles), 60_000L);
    }

    @Test
    void sandboxAndDevProfilesResolveToFiveMinutesForBothJobs() {
        assertCadence(effectiveSchedule("sandbox", "kubernetes"), 300_000L);
        assertCadence(effectiveSchedule("dev", "kubernetes"), 300_000L);
    }

    @Test
    void productionProfileResolvesToOneHourForBothJobs() {
        assertCadence(effectiveSchedule("prod", "kubernetes"), 3_600_000L);
    }

    private void assertCadence(EffectiveSchedule schedule, long expectedDelayMs) {
        assertThat(schedule.orderFixedDelayMs()).isEqualTo(expectedDelayMs);
        assertThat(schedule.fulfillmentFixedDelayMs()).isEqualTo(expectedDelayMs);
        assertThat(schedule.orderEnabled()).isTrue();
        assertThat(schedule.orderApply()).isTrue();
        assertThat(schedule.fulfillmentEnabled()).isTrue();
        assertThat(schedule.fulfillmentApply()).isTrue();
    }

    private EffectiveSchedule effectiveSchedule(String... profiles) {
        Properties effective = new Properties();
        overlay(effective, load("application.yml"));
        for (String profile : profiles) {
            overlay(effective, load("application-%s.yml".formatted(profile)));
        }
        return new EffectiveSchedule(
                Long.parseLong(required(effective, ORDER_SCHEDULE + "fixed-delay-ms")),
                Long.parseLong(required(effective, FULFILLMENT_SCHEDULE + "fixed-delay-ms")),
                Boolean.parseBoolean(required(effective, ORDER_SCHEDULE + "enabled")),
                Boolean.parseBoolean(required(effective, ORDER_SCHEDULE + "apply")),
                Boolean.parseBoolean(required(effective, FULFILLMENT_SCHEDULE + "enabled")),
                Boolean.parseBoolean(required(effective, FULFILLMENT_SCHEDULE + "apply"))
        );
    }

    private void overlay(Properties target, Properties source) {
        source.forEach((key, value) -> target.put(key, value));
    }

    private Properties load(String filename) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/" + filename));
        yaml.afterPropertiesSet();
        return yaml.getObject();
    }

    private String required(Properties properties, String key) {
        Object rawValue = properties.get(key);
        if (rawValue == null) {
            throw new IllegalStateException("Missing required scheduling property: " + key);
        }
        String value = rawValue.toString();
        if (value.isBlank()) {
            throw new IllegalStateException("Missing required scheduling property: " + key);
        }
        return value;
    }

    private record EffectiveSchedule(
            long orderFixedDelayMs,
            long fulfillmentFixedDelayMs,
            boolean orderEnabled,
            boolean orderApply,
            boolean fulfillmentEnabled,
            boolean fulfillmentApply
    ) {
    }
}
