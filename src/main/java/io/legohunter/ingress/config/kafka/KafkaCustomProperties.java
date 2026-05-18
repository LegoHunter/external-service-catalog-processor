package io.legohunter.ingress.config.kafka;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Setter
@Getter
@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "kafka")
public class KafkaCustomProperties {
    private SslBundles sslBundles;
    private List<String> bootstrapServers = new ArrayList<>();
    private String clientId;
    private Map<String, String> properties = new HashMap<>();

    private KafkaProperties.Consumer consumerDefaults;
    private KafkaProperties.Producer producerDefaults;

    private Map<String, TopicConfiguration> topicConfiguration;

    private KafkaProperties.Ssl ssl = new KafkaProperties.Ssl();
    private KafkaProperties.Security security = new KafkaProperties.Security();

    public Map<String, Object> getConsumerProperties(String topicId) {
        Map<String, Object> props = new HashMap<>(buildCommonProperties());
        if (getConsumerDefaults() != null) {
            props.putAll(getConsumerDefaults().buildProperties(sslBundles));
        }
        props.putAll(getConsumerOverrides(topicId));
        return props;
    }

    public Map<String, Object> getProducerProperties(String topicId) {
        Map<String, Object> props = new HashMap<>(buildCommonProperties());
        if (getProducerDefaults() != null) {
            props.putAll(getProducerDefaults().buildProperties(sslBundles));
        }
        props.putAll(hyphenToDot(topicConfiguration.get(topicId).getProducer()));
        return props;
    }

    private Map<String, Object> getConsumerOverrides(String topicId) {
        Map<String, Object> consumerOverrides = hyphenToDot(topicConfiguration.get(topicId).getConsumer().getProperties());
        Optional.ofNullable(topicConfiguration.get(topicId).getConsumer().groupId).ifPresent(groupId -> consumerOverrides.put("group.id", groupId));
        return consumerOverrides;
    }

    private Map<String, Object> hyphenToDot(Map<String, Object> mapToChange) {
        return mapToChange
                .entrySet()
                .stream()
                .collect(Collectors.toMap(e -> e.getKey().replace('-', '.'), Map.Entry::getValue));
    }

    public Map<String, Object> buildCommonProperties() {
        Map<String, Object> properties = new HashMap<>();
        if (this.bootstrapServers != null) {
            properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServers);
        }
        if (this.clientId != null) {
            properties.put(CommonClientConfigs.CLIENT_ID_CONFIG, this.clientId);
        }
        properties.putAll(this.ssl.buildProperties(sslBundles));
        properties.putAll(this.security.buildProperties());
        if (!CollectionUtils.isEmpty(this.properties)) {
            properties.putAll(this.properties);
        }
        return properties;
    }

    @Getter
    @Setter
    public static class TopicConfiguration {
        private String topic;
        private Set<String> topics;
        private ConsumerConfiguration consumer;
        private Map<String, Object> producer;
    }

    @Getter
    @Setter
    public static class ConsumerConfiguration {
        private String groupId;
        private Map<String, Object> properties;

        public Map<String, Object> getProperties() {
            return Optional.ofNullable(properties).orElse(new HashMap<>());
        }
    }

}