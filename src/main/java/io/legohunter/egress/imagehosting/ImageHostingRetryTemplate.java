package io.legohunter.egress.imagehosting;

import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageHostingRetryTemplate {
    private final ImageHostingSyncProperties properties;

    public <T> AttemptedResponse<T> execute(String operation, Supplier<PhotoServiceResponse<T>> supplier) {
        ImageHostingSyncProperties.Retry retry = properties.getSync().getRetry();
        int maxAttempts = retry.effectiveMaxAttempts();
        long backoffMs = retry.effectiveInitialBackoffMs();
        PhotoServiceResponse<T> response = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            response = supplier.get();
            if (!shouldRetry(response, attempt, maxAttempts)) {
                return new AttemptedResponse<>(response, attempt);
            }

            log.warn(
                    "image_hosting.provider.retry_scheduled operation={} attempt={} maxAttempts={} errorType={} responseCode={} message={} backoffMs={}",
                    operation,
                    attempt,
                    maxAttempts,
                    response.errorType(),
                    response.responseCode(),
                    response.responseMessage(),
                    backoffMs
            );
            if (!sleep(backoffMs)) {
                return new AttemptedResponse<>(response, attempt);
            }
            backoffMs = nextBackoffMs(backoffMs, retry);
        }

        return new AttemptedResponse<>(response, maxAttempts);
    }

    private boolean shouldRetry(PhotoServiceResponse<?> response, int attempt, int maxAttempts) {
        return response != null
                && response.isRetryable()
                && attempt < maxAttempts;
    }

    private boolean sleep(long backoffMs) {
        if (backoffMs <= 0L) {
            return true;
        }
        try {
            Thread.sleep(backoffMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long nextBackoffMs(long currentBackoffMs, ImageHostingSyncProperties.Retry retry) {
        long next = Math.round(currentBackoffMs * retry.effectiveBackoffMultiplier());
        return Math.min(Math.max(next, currentBackoffMs), retry.effectiveMaxBackoffMs());
    }

    public record AttemptedResponse<T>(PhotoServiceResponse<T> response, int attempts) {
        public boolean retried() {
            return attempts > 1;
        }

        public boolean retryable() {
            return response != null
                    && response.isError()
                    && response.errorType() != null
                    && response.errorType().isRetryable();
        }

        public PhotoServiceErrorType errorType() {
            if (response == null || !response.isError()) {
                return null;
            }
            return response.errorType();
        }
    }
}
