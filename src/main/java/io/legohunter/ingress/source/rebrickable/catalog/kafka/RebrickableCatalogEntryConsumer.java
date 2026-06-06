package io.legohunter.ingress.source.rebrickable.catalog.kafka;

import io.legohunter.data.dao.ExternalCatalogItemDao;
import io.legohunter.data.dto.ExternalCatalogItem;
import io.legohunter.ingress.source.rebrickable.catalog.model.RebrickableCatalogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RebrickableCatalogEntryConsumer {

    private final ExternalCatalogItemDao externalCatalogItemDao;

    private Integer rebrickableServiceId = 9;

    @KafkaListener(
            topics = "${kafka.topic-configuration.rebrickable-catalog-entry.topic}",
            groupId = "${kafka.topic-configuration.rebrickable-catalog-entry.consumer.group-id}",
            containerFactory = "rebrickableCatalogEntryContainerFactory")
    public void listen(@Payload RebrickableCatalogEntry entry) {
        try {
            ExternalCatalogItem externalCatalogItem = ExternalCatalogItem.builder()
                    .externalServiceId(rebrickableServiceId)
                    .externalItemKey(entry.getSetNum())
                    .externalUniqueKey(null)
                    .itemName(entry.getName())
                    .itemTypeCode("S")
                    .yearReleased(entry.getYear())
                    .itemUrl(String.format("https://rebrickable.com/sets/%s/", entry.getSetNum()))
                    .build();

            externalCatalogItemDao.upsert(externalCatalogItem);
        } catch (Exception e) {
            log.error("Failed to process Rebrickable entry {},  message: {}", entry, e.getMessage(), e);
        }
    }
}
