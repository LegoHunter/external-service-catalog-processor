package io.legohunter.ingress.upload.router;

import io.legohunter.ingress.common.kafka.event.UploadObjectEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UploadRouter {

    private final KafkaTemplate<String, UploadObjectEvent> uploadMinioS3KafkaTemplate;

    // ---- Topic Names (move to config later if desired)
    @Value("${kafka.topic-configuration.upload-photo.topic}")
    private String PHOTO_TOPIC;

    @Value("${kafka.topic-configuration.upload-bricklink-catalog.topic}")
    private String BRICKLINK_CATALOG_TOPIC;

    @Value("${kafka.topic-configuration.upload-bricklink-category.topic}")
    private String BRICKLINK_CATEGORY_TOPIC;

    @Value("${kafka.topic-configuration.upload-rebrickable-catalog.topic}")
    private String REBRICKABLE_CATALOG_TOPIC;

    @Value("${kafka.topic-configuration.upload-rebrickable-theme.topic}")
    private String REBRICKABLE_THEME_TOPIC;

    public void routeEvent(UploadObjectEvent event) {

        String bucket = event.getBucket();
        String key = event.getKey();

        if (key.startsWith("photos/")) {
            routeMediaUpload(event);
            return;
        }

        if (key.startsWith("bricklink/")) {
            routeDataUpload(event);
            return;
        }

        if (key.startsWith("rebrickable/")) {
            routeDataUpload(event);
            return;
        }

        throw new IllegalArgumentException("Unsupported bucket [%s] and key [%s] for upload routing".formatted(bucket, key));
    }

    private void routeMediaUpload(UploadObjectEvent event) {

        String bucket = event.getBucket();
        String key = event.getKey();

        if (key.startsWith("photos/")) {
            log.info("Routing event {} with key {} to {}", event, key, PHOTO_TOPIC);
            uploadMinioS3KafkaTemplate.send(PHOTO_TOPIC, key, event);
            return;
        }

        throw new IllegalArgumentException("Unsupported bucket [%s] and key [%s] for upload routing".formatted(bucket, key));
    }

    private void routeDataUpload(UploadObjectEvent event) {

        String bucket = event.getBucket();
        String key = event.getKey();

        if (key.startsWith("bricklink/catalog/")) {
            log.info("Routing event {} with key {} to {}", event, key, BRICKLINK_CATALOG_TOPIC);
            uploadMinioS3KafkaTemplate.send(BRICKLINK_CATALOG_TOPIC, key, event);
            return;
        }

        if (key.startsWith("bricklink/category/")) {
            log.info("Routing event {} with key {} to {}", event, key, BRICKLINK_CATEGORY_TOPIC);
            uploadMinioS3KafkaTemplate.send(BRICKLINK_CATEGORY_TOPIC, key, event);
            return;
        }

        if (key.startsWith("rebrickable/catalog/")) {
            log.info("Routing event {} with key {} to {}", event, key, REBRICKABLE_CATALOG_TOPIC);
            uploadMinioS3KafkaTemplate.send(REBRICKABLE_CATALOG_TOPIC, key, event);
            return;
        }

        if (key.startsWith("rebrickable/category/")) {
            log.info("Routing event {} with key {} to {}", event, key, REBRICKABLE_THEME_TOPIC);
            uploadMinioS3KafkaTemplate.send(REBRICKABLE_THEME_TOPIC, key, event);
            return;
        }

        throw new IllegalArgumentException("Unsupported bucket [%s] and key [%s] for upload routing".formatted(bucket, key));
    }
}