package io.legohunter.ingress.common.kafka.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.common.kafka.event.UploadObjectEvent;
import io.legohunter.ingress.common.storage.model.s3.minio.Record;
import io.legohunter.ingress.common.storage.model.s3.minio.S3Entity;
import io.legohunter.ingress.common.storage.model.s3.minio.S3EventNotification;
import io.legohunter.ingress.upload.router.UploadRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
public class MinioS3EventListener {

    private final UploadRouter uploadRouter;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${kafka.topic-configuration.minio-s3.topics}",
            groupId = "${kafka.topic-configuration.minio-s3.consumer.group-id}",
            containerFactory = "minioS3ContainerFactory"
    )
    public void handleS3Event(@Payload String payload) {
        try {
            S3EventNotification event = objectMapper.readValue(payload, S3EventNotification.class);

            if (event.EventName().equals("s3:ObjectCreated:Put")) {
                log.info("Received S3Event: {}", event);

                for (Record record : event.Records()) {

                    String eventName = record.eventName();
                    S3Entity s3Entity = record.s3();
                    String bucketName = s3Entity.bucket().name();
                    String key = URLDecoder.decode(s3Entity.object().key(), StandardCharsets.UTF_8);

                    UploadObjectEvent uploadEvent = UploadObjectEvent.builder()
                            .bucket(bucketName)
                            .key(key)
                            .eventName(eventName)
                            .currentTimeStamp(System.currentTimeMillis())
                            .build();

                    uploadRouter.routeEvent(uploadEvent);
                }
            } else {
                log.warn("Unhandled S3Event: {}", event);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
