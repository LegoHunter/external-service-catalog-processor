package io.legohunter.ingress.source.bricklink.catalog.kafka;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import io.legohunter.ingress.common.storage.model.s3.minio.S3Event;
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
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class BricklinkCatalogS3EventListener {

    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${lego.kafka.topic.bricklink-catalog-entry}")
    private String topic;

    @KafkaListener(
            topics = "${lego.kafka.topic.bricklink-catalog-upload}",
            groupId = "${lego.kafka.consumer.group-id.bricklink-catalog-upload}",
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
                log.info("Processing xml input stream");
                AtomicInteger count = new AtomicInteger();

                while (parser.nextToken() != null) {
                    if (parser.currentToken() == JsonToken.FIELD_NAME && "ITEM".equals(parser.getCurrentName())) {
                        parser.nextToken();
                        CatalogEntry entry = xmlMapper.readValue(parser, CatalogEntry.class);
                        kafkaTemplate.send(topic, entry.getItemId(), objectMapper.writeValueAsString(entry));
                        count.getAndIncrement();
                    }
                }
                log.info("Processed {} Bricklink catalog entries.", count.get());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
