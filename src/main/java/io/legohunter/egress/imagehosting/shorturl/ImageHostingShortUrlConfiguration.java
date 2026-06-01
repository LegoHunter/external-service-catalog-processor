package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.imaging.bitly.config.BitlyConfiguration;
import io.legohunter.imaging.bitly.config.BitlyProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
        BitlyProperties.class,
        BitlyConfiguration.class
})
public class ImageHostingShortUrlConfiguration {
}
