package io.legohunter.ingress.source.bricklink.catalog.kafka;

import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.ingress.source.bricklink.catalog.model.CatalogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BricklinkCatalogEntryConsumer {

    private final ExternalCatalogItemDao externalCatalogItemDao;

    private Integer bricklinkServiceId = 2;

    @KafkaListener(
            topics = "${kafka.topic-configuration.bricklink-catalog-entry.topic}",
            groupId = "${kafka.topic-configuration.bricklink-catalog-entry.consumer.group-id}",
            containerFactory = "bricklinkCatalogEntryContainerFactory")
    public void listen(@Payload CatalogEntry entry) {
        try {
            ExternalCatalogItem externalCatalogItem = ExternalCatalogItem.builder()
                    .externalServiceId(bricklinkServiceId)
                    .externalItemKey(entry.getItemId())
                    .externalUniqueKey(null)
                    .itemName(entry.getItemName())
                    .itemTypeCode(entry.getItemType())
                    .yearReleased(entry.getItemYear())
                    .itemUrl(buildBricklinkItemUrl(entry.getItemId()))
                    .build();

            externalCatalogItemDao.upsert(externalCatalogItem);
        } catch (Exception e) {
            log.error("Failed to process Bricklink entry {},  message: {}", entry, e.getMessage(), e);
        }
    }

    private String buildBricklinkItemUrl(String itemId) {
        if (itemId == null) return null;
        return String.format("https://www.bricklink.com/v2/catalog/catalogitem.page?S=%s", itemId);
    }
}
