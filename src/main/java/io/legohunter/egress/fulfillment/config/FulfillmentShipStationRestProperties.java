package io.legohunter.egress.fulfillment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@Getter
@Setter
@ConfigurationProperties(prefix = "shipstation.rest")
public class FulfillmentShipStationRestProperties {
    private URI uri = URI.create("https://ssapi.shipstation.com");
    private String apiKey;
    private String apiSecret;
}
