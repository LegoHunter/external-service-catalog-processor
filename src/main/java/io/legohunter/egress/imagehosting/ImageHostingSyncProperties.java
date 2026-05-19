package io.legohunter.egress.imagehosting;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "lego.image-hosting.sync")
public class ImageHostingSyncProperties {
    private Integer externalServiceId = 10;
    private Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"), "lego-data-ingress-image-hosting");
}
