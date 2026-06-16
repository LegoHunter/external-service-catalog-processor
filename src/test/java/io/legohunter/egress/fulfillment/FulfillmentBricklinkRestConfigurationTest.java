package io.legohunter.egress.fulfillment;

import com.bricklink.api.rest.client.BricklinkRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FulfillmentBricklinkRestConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FulfillmentBricklinkRestConfiguration.class)
            .withPropertyValues(
                    "lego.fulfillment.sync.scheduled.enabled=true",
                    "bricklink.rest.uri=https://api.bricklink.com/api/store/v1",
                    "bricklink.rest.consumer.key=consumer-key",
                    "bricklink.rest.consumer.secret=consumer-secret",
                    "bricklink.rest.token.value=token-value",
                    "bricklink.rest.token.secret=token-secret"
            );

    @Test
    void enabledFulfillmentImportsBricklinkRestClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BricklinkRestClient.class);
        });
    }

    @Test
    void disabledFulfillmentDoesNotImportBricklinkRestClient() {
        contextRunner
                .withPropertyValues("lego.fulfillment.sync.scheduled.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(BricklinkRestClient.class);
                });
    }
}
