package io.legohunter.ingress.source.bricklink.catalog.kafka;

import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.legohunter.data.dao.ExternalItemDao;
import io.legohunter.data.dto.ExternalItem;
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
            ExternalItem externalItem = new ExternalItem();
            externalItem.setServiceId(bricklinkServiceId);
            externalItem.setExternalNumber(entry.getItemId());
            externalItem.setUniqueId(0L);
            externalItem.setName(entry.getItemName());
            externalItem.setItemType(entry.getItemType());
            externalItem.setCategoryId(entry.getCategory());
            externalItem.setYearReleased(entry.getItemYear());
            externalItem.setUrl(buildBricklinkItemUrl(entry.getItemId()));

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
