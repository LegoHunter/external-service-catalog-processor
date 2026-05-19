package io.legohunter.ingress.source.bricklink.categories.kafka;

import io.legohunter.ingress.source.bricklink.categories.model.CategoryEntry;
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
public class BricklinkCategoryEntryConsumer {

    private final CategoryDao categoryDao;

    private Integer bricklinkServiceId = 2;

    @KafkaListener(
            topics = "${kafka.topic-configuration.bricklink-category-entry.topic}",
            groupId = "${kafka.topic-configuration.bricklink-category-entry.consumer.group-id}",
            containerFactory = "bricklinkCategoryEntryContainerFactory")
    public void listen(@Payload CategoryEntry entry) {
        try {
            Category category = new Category();
            category.setExternalServiceId(bricklinkServiceId);
            category.setExternalCategoryId(entry.getCategory());
            category.setCategoryName(entry.getCategoryName());
            category.setParentId(null);

            // Upsert into MySQL
            categoryDao.upsert(category);
        } catch (Exception e) {
            log.error("Failed to process Bricklink Category entry {},  message: {}", entry, e.getMessage(), e);
        }
    }
}
