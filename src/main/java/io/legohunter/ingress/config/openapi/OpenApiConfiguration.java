package io.legohunter.ingress.config.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI legoDataIngressOpenApi(Optional<BuildProperties> buildProperties) {
        String version = buildProperties
                .map(BuildProperties::getVersion)
                .orElse("0.0.1-SNAPSHOT");
        return new OpenAPI()
                .info(new Info()
                        .title("Lego Data Ingress API")
                        .version(version)
                        .description("Internal APIs for Lego collection data ingestion, photo processing, and image-hosting synchronization."));
    }

    @Bean
    public GroupedOpenApi imageHostingOpenApi() {
        return GroupedOpenApi.builder()
                .group("image-hosting")
                .pathsToMatch("/internal/image-hosting/**")
                .build();
    }
}
