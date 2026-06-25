package io.legohunter.ingress.metrics;

import io.legohunter.egress.fulfillment.FulfillmentSyncProperties;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.ingress.source.bricklink.orders.BricklinkOrderSyncProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class JobConfigurationMetrics {
    private static final String METRIC_NAME = "lego_data_ingress_scheduled_job_config_info";

    public JobConfigurationMetrics(
            MeterRegistry meterRegistry,
            BricklinkOrderSyncProperties bricklinkOrderSyncProperties,
            ImageHostingSyncProperties imageHostingSyncProperties,
            FulfillmentSyncProperties fulfillmentSyncProperties
    ) {
        register(
                meterRegistry,
                "bricklink_order_sync",
                "BrickLink Order Sync",
                bricklinkOrderSyncProperties.getScheduled().isEnabled(),
                bricklinkOrderSyncProperties.getScheduled().isApply()
        );
        register(
                meterRegistry,
                "image_hosting_sync",
                "Image Hosting Sync",
                imageHostingSyncProperties.getSync().getScheduled().isEnabled(),
                imageHostingSyncProperties.getSync().getScheduled().isApply()
        );
        register(
                meterRegistry,
                "fulfillment_sync",
                "Fulfillment Sync",
                fulfillmentSyncProperties.getSync().getScheduled().isEnabled(),
                fulfillmentSyncProperties.getSync().getScheduled().isApply()
        );
    }

    private void register(
            MeterRegistry meterRegistry,
            String scheduledJob,
            String displayName,
            boolean enabled,
            boolean apply
    ) {
        Gauge.builder(METRIC_NAME, this, ignored -> 1.0d)
                .description("Configured scheduled job state for Lego Data Ingress. Labels carry enabled and apply values.")
                .tag("scheduled_job", scheduledJob)
                .tag("display_name", displayName)
                .tag("enabled", Boolean.toString(enabled))
                .tag("apply", Boolean.toString(apply))
                .strongReference(true)
                .register(meterRegistry);
    }
}
