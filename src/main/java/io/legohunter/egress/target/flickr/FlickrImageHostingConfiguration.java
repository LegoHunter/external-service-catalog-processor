package io.legohunter.egress.target.flickr;

import io.legohunter.imaging.flickr.config.FlickrConfiguration;
import io.legohunter.imaging.flickr.config.FlickrProperties;
import io.legohunter.imaging.flickr.impl.FlickrPhotoServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@ConditionalOnProperty(prefix = "lego.image-hosting.providers.flickr", name = "enabled", havingValue = "true")
@Import({
        FlickrProperties.class,
        FlickrConfiguration.class,
        FlickrPhotoServiceImpl.class
})
public class FlickrImageHostingConfiguration {
}
