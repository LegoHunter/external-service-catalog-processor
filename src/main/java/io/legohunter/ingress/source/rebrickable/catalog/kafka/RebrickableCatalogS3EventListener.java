package io.legohunter.ingress.source.rebrickable.catalog.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.common.storage.model.s3.minio.S3Event;
import io.legohunter.ingress.source.rebrickable.catalog.model.RebrickableCatalogEntry;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class RebrickableCatalogS3EventListener {

    private final MinioClient minioClient;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${lego.kafka.topic.rebrickable-catalog-entry}")
    private String topic;

    @KafkaListener(
            topics = "${lego.kafka.topic.rebrickable-catalog-upload}",
            groupId = "${lego.kafka.consumer.group-id.rebrickable-catalog-upload}",
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

            CSVParser csvParser = null;
            try {
                GZIPInputStream gzipStream = new GZIPInputStream(inputStream);
                Reader reader = new InputStreamReader(gzipStream, StandardCharsets.UTF_8);
                csvParser = new CSVParser(reader,
                        CSVFormat.DEFAULT
                                .withFirstRecordAsHeader()
                                .withIgnoreHeaderCase()
                                .withTrim());

                for (CSVRecord csvRecord : csvParser) {

                    RebrickableCatalogEntry entry = mapRecord(csvRecord);
                    kafkaTemplate.send(topic, entry.getSetNum(), objectMapper.writeValueAsString(entry));
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RebrickableCatalogEntry mapRecord(CSVRecord csvRecord) {

        RebrickableCatalogEntry entry = new RebrickableCatalogEntry();

        entry.setSetNum(csvRecord.get("SET_NUM"));
        entry.setName(csvRecord.get("NAME"));
        entry.setYear(parseInteger(csvRecord.get("YEAR")));
        entry.setThemeId(parseInteger(csvRecord.get("THEME_ID")));
        entry.setNumParts(parseInteger(csvRecord.get("NUM_PARTS")));
        entry.setImgUrl(csvRecord.get("IMG_URL"));

        return entry;
    }

    private Integer parseInteger(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Unable to parse integer: {}", value);
            return null;
        }
    }
}