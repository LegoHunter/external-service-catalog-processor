package io.legohunter.ingress.source.rebrickable.categories.kafka;

import io.legohunter.ingress.source.rebrickable.categories.model.RebrickableThemeEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.legohunter.data.dao.CategoryDao;
import io.legohunter.data.dto.Category;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RebrickableThemeEntryConsumer {

    private final CategoryDao categoryDao;

    private Integer rebrickableServiceId = 9;

    @KafkaListener(
            topics = "${kafka.topic-configuration.rebrickable-theme-entry.topic}",
            groupId = "${kafka.topic-configuration.rebrickable-theme-entry.consumer.group-id}",
            containerFactory = "rebrickableThemeEntryContainerFactory")
    public void listen(@Payload RebrickableThemeEntry entry) {
        try {

            // Map CatalogEntry → ExternalItem
            Category category = new Category();
            category.setExternalServiceId(rebrickableServiceId);
            category.setExternalCategoryId(entry.getId());
            category.setCategoryName(entry.getName());
            category.setParentId(entry.getParentId());

            // Upsert into MySQL
            categoryDao.upsert(category);
        } catch (Exception e) {
            log.error("Failed to process Rebrickable Theme entry {} message: {}", entry, e.getMessage(), e);
        }
    }
}
