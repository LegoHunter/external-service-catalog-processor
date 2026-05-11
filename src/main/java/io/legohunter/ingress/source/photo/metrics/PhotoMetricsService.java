package io.legohunter.ingress.source.photo.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PhotoMetricsService {

    private final MeterRegistry meterRegistry;

    // =========================
    // Counters
    // =========================

    public void incrementProcessed(String mode) {
        Counter.builder("photo.processed")
                .tag("mode", mode) // event | batch
                .register(meterRegistry)
                .increment();
    }

    public void incrementFailed(String mode) {
        Counter.builder("photo.failed")
                .tag("mode", mode)
                .register(meterRegistry)
                .increment();
    }

    public void incrementDuplicate(String mode) {
        Counter.builder("photo.duplicate")
                .tag("mode", mode)
                .register(meterRegistry)
                .increment();
    }

    // =========================
    // Timer
    // =========================

    public void recordProcessingTime(long millis, String mode) {
        Timer.builder("photo.processing.time")
                .tag("mode", mode)
                .register(meterRegistry)
                .record(millis, TimeUnit.MILLISECONDS);
    }
}