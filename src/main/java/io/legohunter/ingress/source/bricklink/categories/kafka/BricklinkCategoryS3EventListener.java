package io.legohunter.ingress.source.bricklink.categories.kafka;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.legohunter.ingress.common.storage.model.s3.minio.S3Event;
import io.legohunter.ingress.source.bricklink.categories.model.CategoryEntry;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class BricklinkCategoryS3EventListener {

    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${lego.kafka.topic.bricklink-category-entry}")
    private String topic;

    @KafkaListener(
            topics = "${lego.kafka.topic.bricklink-category-upload}",
            groupId = "${lego.kafka.consumer.group-id.bricklink-category-upload}",
            containerFactory = "kafkaListenerContainerFactory")
    public void listen(@Payload String payload) {

        try {
            S3Event event = objectMapper.readValue(payload, S3Event.class);

            log.info("Received S3Event: {}", event);

            event.Records().stream()
                    .filter(r -> "s3:ObjectCreated:Put".equals(r.eventName()))
                    .forEach(r -> processS3Record(r));

        } catch (Exception e) {
            log.error("Failed to deserialize S3 event", e);
        }
    }

    private void processS3Record(S3Event.Record record) {
        String bucket = record.s3().bucket().name();
        String key = record.s3().object().key();

        log.info("Processing S3 PUT: bucket={}, key={}", bucket, key);

        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(key).build())) {

            try (JsonParser parser = xmlMapper.getFactory().createParser(inputStream)) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == JsonToken.FIELD_NAME && "ITEM".equals(parser.getCurrentName())) {
                        parser.nextToken();
                        CategoryEntry entry = xmlMapper.readValue(parser, CategoryEntry.class);
                        kafkaTemplate.send(topic, String.valueOf(entry.getCategory()), objectMapper.writeValueAsString(entry));
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
