package io.legohunter.ingress.source.photo.kafka;

import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.common.logging.LoggingContext;
import io.legohunter.ingress.source.photo.service.PhotoDeletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoDeletedS3EventListener {

    private final PhotoDeletionService photoDeletionService;

    @KafkaListener(
            topics = "${kafka.topic-configuration.delete-photo.topic}",
            groupId = "${kafka.topic-configuration.delete-photo.consumer.group-id}",
            containerFactory = "deletePhotoContainerFactory",
            concurrency = "6"
    )
    public void consume(ObjectDeletedEvent event) {

        long start = System.currentTimeMillis();

        LoggingContext.init();

        try {
            validate(event);

            MDC.put("bucket", event.getBucket());
            MDC.put("key", event.getKey());

            log.info(
                    "photo.delete.kafka.consume.start bucket={} key={}",
                    event.getBucket(),
                    event.getKey()
            );

            photoDeletionService.process(event);

            log.info(
                    "photo.delete.kafka.consume.success bucket={} key={}",
                    event.getBucket(),
                    event.getKey()
            );

        } finally {

            long duration = System.currentTimeMillis() - start;

            log.info(
                    "photo.delete.kafka.consume.complete durationMs={}",
                    duration
            );

            LoggingContext.clear();
        }
    }

    private void validate(ObjectDeletedEvent event) {

        if (event == null) {
            throw new IllegalArgumentException("ObjectDeletedEvent is null");
        }

        if (event.getBucket() == null || event.getBucket().isBlank()) {
            throw new IllegalArgumentException("Bucket is missing");
        }

        if (event.getKey() == null || event.getKey().isBlank()) {
            throw new IllegalArgumentException("Object key is missing");
        }
    }
}
