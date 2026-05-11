package io.legohunter.ingress.source.photo.kafka;

import io.legohunter.ingress.common.kafka.event.UploadObjectEvent;
import io.legohunter.ingress.common.logging.LoggingContext;
import io.legohunter.ingress.source.photo.model.PhotoUploadEvent;
import io.legohunter.ingress.source.photo.service.PhotoProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoUploadS3EventListener {

    private final PhotoProcessingService photoProcessingService;

//    @RetryableTopic(
//            attempts = "3",
//            backoff = @Backoff(
//                    delay = 5000,
//                    multiplier = 2.0
//            ),
//            dltStrategy = DltStrategy.FAIL_ON_ERROR,
//            dltTopicSuffix = ".dlt"
//    )
    @KafkaListener(
            topics = "${kafka.topic-configuration.upload-photo.topic}",
            groupId = "${kafka.topic-configuration.upload-photo.consumer.group-id}",
            containerFactory = "uploadPhotoContainerFactory")
    public void consume(UploadObjectEvent event) {

        long start = System.currentTimeMillis();

        LoggingContext.init();

        try {

            MDC.put("bucket", event.getBucket());
            MDC.put("key", event.getKey());

            log.info(
                    "photo.kafka.consume.start bucket={} key={}",
                    event.getBucket(),
                    event.getKey()
            );

            validate(event);

            PhotoUploadEvent photoEvent = new PhotoUploadEvent(event.getBucket(), event.getKey());

            photoProcessingService.process(photoEvent);

            log.info(
                    "photo.kafka.consume.success bucket={} key={}",
                    event.getBucket(),
                    event.getKey()
            );

        } finally {

            long duration = System.currentTimeMillis() - start;

            log.info(
                    "photo.kafka.consume.complete durationMs={}",
                    duration
            );

            LoggingContext.clear();
        }
    }

    private void validate(UploadObjectEvent event) {

        if (event == null) {
            throw new IllegalArgumentException("UploadObjectEvent is null");
        }

        if (event.getBucket() == null || event.getBucket().isBlank()) {
            throw new IllegalArgumentException("Bucket is missing");
        }

        if (event.getKey() == null || event.getKey().isBlank()) {
            throw new IllegalArgumentException("Object key is missing");
        }

        if (!event.getKey().startsWith("photos/")) {
            throw new IllegalArgumentException(
                    "Invalid photo upload key [%s]"
                            .formatted(event.getKey())
            );
        }
    }
}