package io.legohunter.ingress.source.photo.kafka;

import io.legohunter.ingress.common.kafka.event.ObjectUploadedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoUploadS3EventListenerTest {

    @Test
    void consumeUsesConfigurableKafkaListenerConcurrency() throws NoSuchMethodException {
        Method consume = PhotoUploadS3EventListener.class.getMethod(
                "consume",
                ObjectUploadedEvent.class);

        KafkaListener kafkaListener = consume.getAnnotation(KafkaListener.class);

        assertThat(kafkaListener.concurrency())
                .isEqualTo("${kafka.topic-configuration.upload-photo.consumer.concurrency:1}");
    }
}
