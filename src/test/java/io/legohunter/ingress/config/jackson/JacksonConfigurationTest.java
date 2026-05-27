package io.legohunter.ingress.config.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigurationTest {

    @Test
    void objectMapperSerializesSyncPlanJavaTimeFields() throws Exception {
        ObjectMapper mapper = new JacksonConfiguration().objectMapper();

        String json = mapper.writeValueAsString(SyncPlan.builder()
                .planId("plan-1")
                .createdAt(LocalDateTime.of(2026, 5, 27, 14, 22, 16))
                .build());

        assertThat(json)
                .contains("\"planId\":\"plan-1\"")
                .contains("\"createdAt\":\"2026-05-27T14:22:16\"");
    }
}
