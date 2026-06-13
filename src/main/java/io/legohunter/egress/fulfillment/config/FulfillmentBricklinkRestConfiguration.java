package io.legohunter.egress.fulfillment.config;

import com.bricklink.api.rest.configuration.BricklinkRestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@ConditionalOnProperty(prefix = "lego.fulfillment.sync.scheduled", name = "apply", havingValue = "true")
@ConditionalOnProperty(
        prefix = "bricklink.rest",
        name = {"uri", "consumer.key", "consumer.secret", "token.value", "token.secret"}
)
@Import(BricklinkRestConfiguration.class)
public class FulfillmentBricklinkRestConfiguration {
}
