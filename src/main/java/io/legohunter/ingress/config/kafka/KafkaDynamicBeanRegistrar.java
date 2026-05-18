package io.legohunter.ingress.config.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.legohunter.ingress.common.util.KafkaNamingUtil.kebabToCamel;

@Slf4j
@RequiredArgsConstructor
@Configuration
public class KafkaDynamicBeanRegistrar {

    private static final String CONSUMER_FACTORY_BEAN_NAME_TEMPLATE = "%sConsumerFactory";
    private static final String PRODUCER_FACTORY_BEAN_NAME_TEMPLATE = "%sProducerFactory";
    private static final String KAFKA_TEMPLATE_BEAN_NAME_TEMPLATE = "%sKafkaTemplate";
    private static final String CONTAINER_FACTORY_BEAN_NAME_TEMPLATE = "%sContainerFactory";

    @Bean
    public static BeanDefinitionRegistryPostProcessor beanDefinitionRegistryPostProcessor(Environment environment) {
        KafkaCustomProperties kafkaCustomProperties = Binder.get(environment).bind("kafka", KafkaCustomProperties.class).orElse(null);
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
                registerConsumers(registry, kafkaCustomProperties);
                registerProducers(registry, kafkaCustomProperties);
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                BeanDefinitionRegistryPostProcessor.super.postProcessBeanFactory(beanFactory);
            }
        };
    }

    public static Map<String, Map<String, Object>> consumers(KafkaCustomProperties kafkaCustomProperties) {
        return kafkaCustomProperties.getTopicConfiguration()
                .entrySet()
                .stream()
                .filter(e -> Optional.ofNullable(e.getValue().getConsumer()).isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, v -> v.getValue().getConsumer().getProperties()));
    }

    public static Map<String, Map<String, Object>> producers(KafkaCustomProperties kafkaCustomProperties) {
        return kafkaCustomProperties.getTopicConfiguration()
                .entrySet()
                .stream()
                .filter(e -> Optional.ofNullable(e.getValue().getProducer()).isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, v -> v.getValue().getProducer()));
    }

    private static void registerConsumers(BeanDefinitionRegistry registry, KafkaCustomProperties kafkaCustomProperties) {
        Map<String, Map<String, Object>> consumers = consumers(kafkaCustomProperties);

        consumers.forEach((topicConfigurationName, consumer) -> {
            String beanName = beanName(CONSUMER_FACTORY_BEAN_NAME_TEMPLATE, topicConfigurationName);
            Map<String, Object> properties = kafkaCustomProperties.getConsumerProperties(topicConfigurationName);
            log.info("Registering consumer bean [{}] for topic {} with properties {}", beanName, topicConfigurationName, properties);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(DefaultKafkaConsumerFactory.class);
            builder.addConstructorArgValue(properties);
            registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
            registerContainerFactory(registry, topicConfigurationName, beanName);
        });
    }

    private static void registerProducers(BeanDefinitionRegistry registry, KafkaCustomProperties kafkaCustomProperties) {
        Map<String, Map<String, Object>> producers = producers(kafkaCustomProperties);

        producers.forEach((topicConfigurationName, producer) -> {
            String beanName = beanName(PRODUCER_FACTORY_BEAN_NAME_TEMPLATE, topicConfigurationName);
            Map<String, Object> properties = kafkaCustomProperties.getProducerProperties(topicConfigurationName);
            log.info("Registering producer bean [{}] for topic {} with properties {}", beanName, topicConfigurationName, properties);
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(DefaultKafkaProducerFactory.class);
            builder.addConstructorArgValue(properties);
            registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
            registerKafkaTemplate(registry, topicConfigurationName, beanName);
        });
    }

    private static void registerKafkaTemplate(
            BeanDefinitionRegistry registry,
            String topic,
            String producerFactoryBeanName) {

        String beanName = beanName(KAFKA_TEMPLATE_BEAN_NAME_TEMPLATE, topic);
        log.info("Registering Kafka Template bean [{}] for topic {}", beanName, topic);

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(KafkaTemplate.class);
        builder.addConstructorArgReference(producerFactoryBeanName);
        registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
    }

    private static void registerContainerFactory(
            BeanDefinitionRegistry registry,
            String topic,
            String consumerFactoryBeanName) {

        String beanName = beanName(CONTAINER_FACTORY_BEAN_NAME_TEMPLATE, topic);
        log.info("Registering Concurrent Kafka Listener Container Factory bean [{}] for topic {}", beanName, topic);

        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(ConcurrentKafkaListenerContainerFactory.class);
        builder.addPropertyReference("consumerFactory", consumerFactoryBeanName);
        registry.registerBeanDefinition(beanName, builder.getBeanDefinition());
    }

    private static String beanName(String template, String topic) {
        return String.format(template, kebabToCamel(topic));
    }
}
