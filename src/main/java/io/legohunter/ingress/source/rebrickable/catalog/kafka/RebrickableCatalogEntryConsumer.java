package io.legohunter.ingress.source.rebrickable.catalog.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.source.rebrickable.catalog.model.RebrickableCatalogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lego.data.v2.dao.ExternalItemDao;
import net.lego.data.v2.dto.ExternalItem;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RebrickableCatalogEntryConsumer {

    private final ObjectMapper objectMapper;
    private final ExternalItemDao externalItemDao;

    private Integer rebrickableServiceId = 9;

    @KafkaListener(
            topics = "${lego.kafka.topic.rebrickable-catalog-entry}",
            groupId = "${lego.kafka.consumer.group-id.rebrickable-catalog-entry}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        try {
            RebrickableCatalogEntry entry = objectMapper.readValue(message, RebrickableCatalogEntry.class);

            // Map CatalogEntry → ExternalItem
            ExternalItem externalItem = mapToExternalItem(entry);

            // Upsert into MySQL
            externalItemDao.upsert(externalItem);
        } catch (Exception e) {
            log.error("Failed to process RebrickableCatalogEntry message: {}", message, e);
        }
    }

    private ExternalItem mapToExternalItem(RebrickableCatalogEntry entry) {

        return ExternalItem.builder()
                .number(entry.getSetNum())
                .uniqueId(0L)
                .name(entry.getName())
                .itemType("S")
                .url("https://rebrickable.com/sets/" + entry.getSetNum() + "/")
                .categoryId(entry.getThemeId())   // or whatever matches your CSV
                .yearReleased(entry.getYear())
                .serviceId(rebrickableServiceId)
                .build();
    }
}