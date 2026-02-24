package io.legohunter.ingress.source.rebrickable.categories.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.source.rebrickable.categories.model.RebrickableThemeEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lego.data.v2.dao.CategoryDao;
import net.lego.data.v2.dto.Category;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RebrickableThemeEntryConsumer {

    private final ObjectMapper objectMapper;
    private final CategoryDao categoryDao;

    private Integer rebrickableServiceId = 9;

    @KafkaListener(
            topics = "${lego.kafka.topic.rebrickable-theme-entry}",
            groupId = "${lego.kafka.consumer.group-id.rebrickable-theme-entry}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        try {
            RebrickableThemeEntry entry = objectMapper.readValue(message, RebrickableThemeEntry.class);

            // Map CatalogEntry → ExternalItem
            Category category = Category.builder()
                    .externalServiceId(rebrickableServiceId)
                    .externalCategoryId(entry.getId())
                    .categoryName(entry.getName())
                    .parentId(entry.getParentId())
                    .build();

            // Upsert into MySQL
            categoryDao.upsert(category);
        } catch (Exception e) {
            log.error("Failed to process RebrickableCatalogEntry message: {}", message, e);
        }
    }
}