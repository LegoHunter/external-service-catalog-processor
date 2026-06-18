package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.client.BricklinkRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import static org.assertj.core.api.Assertions.assertThat;

class FulfillmentBricklinkRestConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FulfillmentBricklinkRestConfiguration.class)
            .withPropertyValues(
                    "lego.fulfillment.sync.scheduled.enabled=true",
                    "bricklink.rest.uri=https://api.bricklink.com/api/store/v1",
                    "bricklink.rest.consumer.key=consumer-key",
                    "bricklink.rest.consumer.secret=consumer-secret",
                    "bricklink.rest.token.value=token-value",
                    "bricklink.rest.token.secret=token-secret"
            );

    @Test
    void enabledFulfillmentImportsBricklinkRestClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BricklinkRestClient.class);
        });
    }

    @Test
    void disabledFulfillmentDoesNotImportBricklinkRestClient() {
        contextRunner
                .withPropertyValues("lego.fulfillment.sync.scheduled.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(BricklinkRestClient.class);
                });
    }

    @Test
    void fulfillmentConfigurationBeanNamesAreUnique() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));
        AnnotationBeanNameGenerator beanNameGenerator = new AnnotationBeanNameGenerator();
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

        Map<String, List<String>> beanClassNamesByName = scanner.findCandidateComponents("io.legohunter.egress.fulfillment")
                .stream()
                .collect(groupingBy(
                        beanDefinition -> beanNameGenerator.generateBeanName(beanDefinition, registry),
                        mapping(BeanDefinition::getBeanClassName, toList())
                ));

        assertThat(beanClassNamesByName)
                .allSatisfy((beanName, beanClassNames) -> assertThat(beanClassNames)
                        .as("configuration bean name [%s]", beanName)
                        .hasSize(1));
    }
}
