package io.legohunter.egress.imagehosting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ImageHostingKubernetesConfigurationTest {

    @Test
    void kubernetesProfileEnablesScheduledSyncInDryRunMode() {
        Properties properties = load("application-kubernetes.yml");

        assertThat(properties)
                .containsEntry("lego.image-hosting.sync.scheduled.enabled", true)
                .containsEntry("lego.image-hosting.sync.scheduled.apply", false)
                .containsEntry("lego.image-hosting.sync.scheduled.batch-size", 25)
                .containsEntry("lego.image-hosting.sync.scheduled.concurrency", 2)
                .containsEntry("lego.image-hosting.sync.scheduled.retry-failed", true)
                .containsEntry("lego.image-hosting.readiness.require-scheduled-sync-enabled", true);
    }

    @Test
    void sandboxProfileImportsOptionalBitlyConfiguration() {
        Properties properties = load("application-sandbox.yml");

        assertThat(properties.getProperty("spring.config.import"))
                .contains("optional:file:${import-path}/bitly-configuration.yml");
    }

    private static Properties load(String resourceName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resourceName));
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
