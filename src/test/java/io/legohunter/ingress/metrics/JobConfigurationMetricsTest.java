package io.legohunter.ingress.metrics;

import io.legohunter.egress.fulfillment.FulfillmentSyncProperties;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.ingress.source.bricklink.orders.BricklinkOrderSyncProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobConfigurationMetricsTest {
    private static final String METRIC_NAME = "lego_data_ingress_scheduled_job_config_info";

    @Test
    void registersConfiguredScheduledJobStates() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        BricklinkOrderSyncProperties bricklinkProperties = new BricklinkOrderSyncProperties();
        bricklinkProperties.getScheduled().setEnabled(true);
        bricklinkProperties.getScheduled().setApply(true);
        ImageHostingSyncProperties imageHostingProperties = new ImageHostingSyncProperties();
        imageHostingProperties.getSync().getScheduled().setEnabled(true);
        imageHostingProperties.getSync().getScheduled().setApply(false);
        FulfillmentSyncProperties fulfillmentProperties = new FulfillmentSyncProperties();
        fulfillmentProperties.getSync().getScheduled().setEnabled(false);
        fulfillmentProperties.getSync().getScheduled().setApply(false);

        new JobConfigurationMetrics(
                meterRegistry,
                bricklinkProperties,
                imageHostingProperties,
                fulfillmentProperties
        );

        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "bricklink_order_sync")
                .tag("display_name", "BrickLink Order Sync")
                .tag("enabled", "true")
                .tag("apply", "true")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "image_hosting_sync")
                .tag("display_name", "Image Hosting Sync")
                .tag("enabled", "true")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
        assertThat(meterRegistry.get(METRIC_NAME)
                .tag("scheduled_job", "fulfillment_sync")
                .tag("display_name", "Fulfillment Sync")
                .tag("enabled", "false")
                .tag("apply", "false")
                .gauge()
                .value()).isEqualTo(1.0d);
    }
}
