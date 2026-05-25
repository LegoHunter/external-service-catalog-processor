package io.legohunter.egress.imagehosting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ImageHostingSyncMetricsService {
    private static final String PROVIDER_TAG = "provider";
    private static final String OUTCOME_TAG = "outcome";
    private static final String DRY_RUN_TAG = "dry_run";
    private static final String RESULT_TAG = "result";
    private static final String OPERATION_TAG = "operation";

    private final MeterRegistry meterRegistry;

    public void recordSync(ImageHostingSyncResult result, String providerTag, long elapsedMillis) {
        Counter.builder("image_hosting_sync")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, result.getOutcome().name().toLowerCase())
                .tag(DRY_RUN_TAG, Boolean.toString(result.isDryRun()))
                .register(meterRegistry)
                .increment();

        Timer.builder("image_hosting_sync_duration")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, result.getOutcome().name().toLowerCase())
                .tag(DRY_RUN_TAG, Boolean.toString(result.isDryRun()))
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    public void recordPhotoUpload(String providerTag, String result, double count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("image_hosting_photo_upload")
                .tag(PROVIDER_TAG, providerTag)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment(count);
    }

    public void recordAlbumOperation(String providerTag, String operation, String result) {
        Counter.builder("image_hosting_album_operation")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OPERATION_TAG, operation)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment();
    }

    public void recordScheduledSync(String providerTag, String outcome, long elapsedMillis) {
        Counter.builder("image_hosting_scheduled_sync")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .increment();

        Timer.builder("image_hosting_scheduled_sync_duration")
                .tag(PROVIDER_TAG, providerTag)
                .tag(OUTCOME_TAG, outcome)
                .register(meterRegistry)
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
    }

    public void recordScheduledSyncInventory(String providerTag, String result, double count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("image_hosting_scheduled_sync_inventory")
                .tag(PROVIDER_TAG, providerTag)
                .tag(RESULT_TAG, result)
                .register(meterRegistry)
                .increment(count);
    }
}
