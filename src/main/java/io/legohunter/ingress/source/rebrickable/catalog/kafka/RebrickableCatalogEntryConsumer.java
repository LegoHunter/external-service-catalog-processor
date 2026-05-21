package io.legohunter.ingress.source.rebrickable.catalog.kafka;

import io.legohunter.ingress.source.rebrickable.catalog.model.RebrickableCatalogEntry;
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
public class RebrickableCatalogEntryConsumer {

    private final ExternalItemDao externalItemDao;

    private Integer rebrickableServiceId = 9;

    @KafkaListener(
            topics = "${kafka.topic-configuration.rebrickable-catalog-entry.topic}",
            groupId = "${kafka.topic-configuration.rebrickable-catalog-entry.consumer.group-id}",
            containerFactory = "rebrickableCatalogEntryContainerFactory")
    public void listen(@Payload RebrickableCatalogEntry entry) {
        try {
            ExternalItem externalItem = new ExternalItem();
            externalItem.setServiceId(rebrickableServiceId);
            externalItem.setExternalNumber(entry.getSetNum());
            externalItem.setUniqueId(0L);
            externalItem.setName(entry.getName());
            externalItem.setItemType("S");
            externalItem.setCategoryId(entry.getThemeId());
            externalItem.setYearReleased(entry.getYear());
            externalItem.setUrl(String.format("https://rebrickable.com/sets/%s/", entry.getSetNum()));

            // Upsert into MySQL
            externalItemDao.upsert(externalItem);
        } catch (Exception e) {
            log.error("Failed to process Rebrickable entry {},  message: {}", entry, e.getMessage(), e);
        }
    }
}
