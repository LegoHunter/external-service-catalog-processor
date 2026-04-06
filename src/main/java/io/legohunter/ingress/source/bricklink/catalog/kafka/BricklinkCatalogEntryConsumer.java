package io.legohunter.ingress.source.bricklink.catalog.kafka;

import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lego.data.v2.dao.ExternalItemDao;
import net.lego.data.v2.dto.ExternalItem;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BricklinkCatalogEntryConsumer {

    private final ExternalItemDao externalItemDao;

    private Integer bricklinkServiceId = 2;

    @KafkaListener(
            topics = "${kafka.topic-configuration.bricklink-catalog-entry.topic}",
            groupId = "${kafka.topic-configuration.bricklink-catalog-entry.consumer.group-id}",
            containerFactory = "bricklinkCatalogEntryContainerFactory")
    public void listen(@Payload CatalogEntry entry) {
        try {
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
            log.error("Failed to process Bricklink entry {},  message: {}", entry, e.getMessage(), e);
        }
    }

    private String buildBricklinkItemUrl(String itemId) {
        if (itemId == null) return null;
        return String.format("https://www.bricklink.com/v2/catalog/catalogitem.page?S=%s", itemId);
    }
}