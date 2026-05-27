package io.legohunter.ingress.config.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigurationTest {

    @Test
    void legoDataIngressOpenApiProvidesServiceMetadata() {
        OpenAPI openApi = new OpenApiConfiguration().legoDataIngressOpenApi(Optional.empty());

        assertThat(openApi.getInfo())
                .extracting(
                        info -> info.getTitle(),
                        info -> info.getVersion(),
                        info -> info.getDescription()
                )
                .containsExactly(
                        "Lego Data Ingress API",
                        "0.0.1-SNAPSHOT",
                        "Internal APIs for Lego collection data ingestion, photo processing, and image-hosting synchronization."
                );
    }

    @Test
    void imageHostingOpenApiGroupIsConfigured() {
        GroupedOpenApi groupedOpenApi = new OpenApiConfiguration().imageHostingOpenApi();

        assertThat(groupedOpenApi.getGroup()).isEqualTo("image-hosting");
        assertThat(groupedOpenApi.getPathsToMatch()).containsExactly("/internal/image-hosting/**");
    }
}
