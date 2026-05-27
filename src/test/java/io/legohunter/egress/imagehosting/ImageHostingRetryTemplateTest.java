package io.legohunter.egress.imagehosting;

import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ImageHostingRetryTemplateTest {

    @Test
    void executeRetriesRetryableResponsesUntilSuccess() {
        ImageHostingSyncProperties properties = retryProperties();
        ImageHostingRetryTemplate retryTemplate = new ImageHostingRetryTemplate(properties);
        AtomicInteger attempts = new AtomicInteger();

        ImageHostingRetryTemplate.AttemptedResponse<String> response = retryTemplate.execute("test", () -> {
            if (attempts.incrementAndGet() == 1) {
                return errorResponse(PhotoServiceErrorType.SERVICE_UNAVAILABLE);
            }
            return response("ok");
        });

        assertThat(response.response().isError()).isFalse();
        assertThat(response.response().get()).isEqualTo("ok");
        assertThat(response.attempts()).isEqualTo(2);
        assertThat(response.retried()).isTrue();
    }

    @Test
    void executeDoesNotRetryNonRetryableResponses() {
        ImageHostingSyncProperties properties = retryProperties();
        ImageHostingRetryTemplate retryTemplate = new ImageHostingRetryTemplate(properties);
        AtomicInteger attempts = new AtomicInteger();

        ImageHostingRetryTemplate.AttemptedResponse<String> response = retryTemplate.execute("test", () -> {
            attempts.incrementAndGet();
            return errorResponse(PhotoServiceErrorType.AUTHORIZATION_FAILED);
        });

        assertThat(response.response().isError()).isTrue();
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.retried()).isFalse();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void executeStopsAtMaxAttempts() {
        ImageHostingSyncProperties properties = retryProperties();
        ImageHostingRetryTemplate retryTemplate = new ImageHostingRetryTemplate(properties);
        AtomicInteger attempts = new AtomicInteger();

        ImageHostingRetryTemplate.AttemptedResponse<String> response = retryTemplate.execute("test", () -> {
            attempts.incrementAndGet();
            return errorResponse(PhotoServiceErrorType.NETWORK_ERROR);
        });

        assertThat(response.response().isError()).isTrue();
        assertThat(response.attempts()).isEqualTo(3);
        assertThat(response.retried()).isTrue();
        assertThat(response.retryable()).isTrue();
        assertThat(attempts).hasValue(3);
    }

    private static ImageHostingSyncProperties retryProperties() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().getRetry().setMaxAttempts(3);
        properties.getSync().getRetry().setInitialBackoffMs(0L);
        return properties;
    }

    private static <T> PhotoServiceResponse<T> response(T value) {
        return new TestPhotoServiceResponse<>(value, false, null, null, PhotoServiceErrorType.UNKNOWN);
    }

    private static <T> PhotoServiceResponse<T> errorResponse(PhotoServiceErrorType errorType) {
        return new TestPhotoServiceResponse<>(null, true, 503, errorType.name(), errorType);
    }

    private record TestPhotoServiceResponse<T>(
            T value,
            boolean error,
            Integer responseCode,
            String responseMessage,
            PhotoServiceErrorType errorType
    ) implements PhotoServiceResponse<T> {

        @Override
        public T get() {
            return value;
        }

        @Override
        public void accept(T value) {
        }

        @Override
        public boolean isError() {
            return error;
        }
    }
}
