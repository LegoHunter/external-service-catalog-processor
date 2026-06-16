package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.configuration.BricklinkRestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "lego.fulfillment.sync.scheduled", name = "enabled", havingValue = "true")
@Import(BricklinkRestConfiguration.class)
public class FulfillmentBricklinkRestConfiguration {
}
