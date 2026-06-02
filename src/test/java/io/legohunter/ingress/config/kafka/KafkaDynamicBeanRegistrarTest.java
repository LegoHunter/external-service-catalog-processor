package io.legohunter.ingress.config.kafka;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaDynamicBeanRegistrarTest {

    @Test
    void sanitizedPropertiesMasksSensitiveKafkaCredentials() {
        Map<String, Object> properties = Map.of(
                "bootstrap.servers", "redpanda-dev-0:9092",
                "sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username='devproducer' password='secret';",
                "ssl.key.password", "key-secret",
                "ssl.keystore.password", "keystore-secret",
                "ssl.truststore.password", "truststore-secret");

        Map<String, Object> sanitized = KafkaDynamicBeanRegistrar.sanitizedProperties(properties);

        assertThat(sanitized)
                .containsEntry("bootstrap.servers", "redpanda-dev-0:9092")
                .containsEntry("sasl.jaas.config", "[masked]")
                .containsEntry("ssl.key.password", "[masked]")
                .containsEntry("ssl.keystore.password", "[masked]")
                .containsEntry("ssl.truststore.password", "[masked]");
    }

    @Test
    void sanitizedPropertiesDoesNotMutateOriginalProperties() {
        Map<String, Object> properties = Map.of(
                "sasl.jaas.config", "plain-secret",
                "security.protocol", "SASL_PLAINTEXT");

        KafkaDynamicBeanRegistrar.sanitizedProperties(properties);

        assertThat(properties)
                .containsEntry("sasl.jaas.config", "plain-secret")
                .containsEntry("security.protocol", "SASL_PLAINTEXT");
    }
}
