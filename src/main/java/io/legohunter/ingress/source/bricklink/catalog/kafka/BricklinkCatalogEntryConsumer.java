package io.legohunter.ingress.source.bricklink.catalog.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lego.data.v2.dao.ExternalItemDao;
import net.lego.data.v2.dto.ExternalItem;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BricklinkCatalogEntryConsumer {

    private final ObjectMapper objectMapper;
    private final ExternalItemDao externalItemDao;

    private Integer bricklinkServiceId = 2;

    @KafkaListener(
            topics = "${lego.kafka.topic.bricklink-catalog-entry}",
            groupId = "${lego.kafka.consumer.group-id.bricklink-catalog-entry}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        try {
            CatalogEntry entry = objectMapper.readValue(message, CatalogEntry.class);

            // Map CatalogEntry → ExternalItem
            ExternalItem externalItem = ExternalItem.builder()
                    .serviceId(bricklinkServiceId)
                    .number(entry.getItemId())
                    .uniqueId(0L)
                    .name(entry.getItemName())
                    .itemType(entry.getItemType())
                    .categoryId(entry.getCategory())
                    .yearReleased(entry.getItemYear())
                    .url(buildBricklinkItemUrl(entry.getItemId()))
                    .build();

            // Upsert into MySQL
            externalItemDao.upsert(externalItem);
        } catch (Exception e) {
            log.error("Failed to process Bricklink CatalogEntry message: {}", message, e);
        }
    }

    private String buildBricklinkItemUrl(String itemId) {
        if (itemId == null) return null;
        return String.format("https://www.bricklink.com/v2/catalog/catalogitem.page?S=%s", itemId);
    }
}