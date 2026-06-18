package io.legohunter.egress.fulfillment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipstation.api.rest.client.DefaultShipStationRestClient;
import com.shipstation.api.rest.client.ShipStationHttpClient;
import com.shipstation.api.rest.client.ShipStationRestClient;
import com.shipstation.api.rest.support.ShipStationBasicAuthInterceptor;
import com.shipstation.api.rest.support.ShipStationObjectMapperFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@EnableConfigurationProperties(FulfillmentShipStationRestProperties.class)
@ConditionalOnProperty(prefix = "shipstation.rest", name = {"api-key", "api-secret"})
public class FulfillmentShipStationRestConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "shipStationRestObjectMapper")
    public ObjectMapper shipStationRestObjectMapper() {
        return ShipStationObjectMapperFactory.create();
    }

    @Bean
    @ConditionalOnMissingBean
    public ShipStationHttpClient shipStationHttpClient(
            FulfillmentShipStationRestProperties properties,
            @Qualifier("shipStationRestObjectMapper") ObjectMapper objectMapper
    ) {
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getUri().toString())
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                })
                .requestInterceptor(new ShipStationBasicAuthInterceptor(properties.getApiKey(), properties.getApiSecret()))
                .build();

        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        return HttpServiceProxyFactory.builderFor(adapter)
                .build()
                .createClient(ShipStationHttpClient.class);
    }

    @Bean
    @ConditionalOnMissingBean
    public ShipStationRestClient shipStationRestClient(ShipStationHttpClient shipStationHttpClient) {
        return new DefaultShipStationRestClient(shipStationHttpClient);
    }
}
