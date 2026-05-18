package io.legohunter.ingress.config.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfiguration {

    private final KafkaCustomProperties kafkaCustomProperties;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> miniS3KafkaListenerFactory() {
        Map<String, Object> props = kafkaCustomProperties.getConsumerProperties("minio-s3");

        ConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(props);
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }
//
//    @Bean
//    public KafkaTemplate<String, ObjectUploadedEvent> objectUploadedEventKafkaTemplate() {
//        Map<String, Object> props = kafkaCustomProperties.getProducerProperties("bricklink-catalog");
//        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
//    }
}
