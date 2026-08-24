package io.legohunter.ingress.source.photo.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoUploadSandboxConfigurationTest {

    @Test
    void sandboxUsesBoundedParallelPhotoConsumers() {
        Properties properties = load("application-sandbox.yml");

        assertThat(properties)
                .containsEntry("kafka.topic-configuration.upload-photo.consumer.concurrency", 4)
                .containsEntry("kafka.topic-configuration.upload-photo.consumer.properties.max-poll-records", 1)
                .containsEntry("kafka.topic-configuration.upload-photo.consumer.properties.max-poll-interval-ms", 1800000);
    }

    private static Properties load(String resourceName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resourceName));
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
