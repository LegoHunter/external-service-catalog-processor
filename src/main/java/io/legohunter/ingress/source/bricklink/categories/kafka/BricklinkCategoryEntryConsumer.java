package io.legohunter.ingress.source.bricklink.categories.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.ingress.source.bricklink.categories.model.CategoryEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lego.data.v2.dao.CategoryDao;
import net.lego.data.v2.dto.Category;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BricklinkCategoryEntryConsumer {

    private final ObjectMapper objectMapper;
    private final CategoryDao categoryDao;

    private Integer bricklinkServiceId = 2;

    @KafkaListener(
            topics = "${lego.kafka.topic.bricklink-category-entry}",
            groupId = "${lego.kafka.consumer.group-id.bricklink-category-entry}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key
    ) {
        try {
            CategoryEntry entry = objectMapper.readValue(message, CategoryEntry.class);

            // Map CategoryEntry → Category
            Category category = Category.builder()
                    .externalServiceId(bricklinkServiceId)
                    .externalCategoryId(entry.getCategory())
                    .categoryName(entry.getCategoryName())
                    .build();

            // Upsert into MySQL
            categoryDao.upsert(category);
        } catch (Exception e) {
            log.error("Failed to process CategoryEntry message: {}", message, e);
        }
    }
}