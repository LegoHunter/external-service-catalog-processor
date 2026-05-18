package io.legohunter.ingress.source.photo.kafka;

import io.legohunter.ingress.common.kafka.event.ObjectDeletedEvent;
import io.legohunter.ingress.source.photo.service.PhotoDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PhotoDeletedS3EventListenerTest {

    @Mock
    private PhotoDeletionService photoDeletionService;

    private PhotoDeletedS3EventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PhotoDeletedS3EventListener(photoDeletionService);
    }

    @Test
    void consume_delegatesValidDeleteEvent() {
        ObjectDeletedEvent event = deleteEvent("lego-photos-sandbox", "3001/uuid/0123456789abcdef0123456789abcdef.jpg");

        listener.consume(event);

        verify(photoDeletionService)
                .process(event);
    }

    @Test
    void consume_rejectsNullEvent() {
        assertThatThrownBy(() -> listener.consume(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ObjectDeletedEvent is null");

        verifyNoInteractions(photoDeletionService);
    }

    @Test
    void consume_rejectsMissingBucket() {
        ObjectDeletedEvent event = deleteEvent(" ", "3001/uuid/0123456789abcdef0123456789abcdef.jpg");

        assertThatThrownBy(() -> listener.consume(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bucket is missing");

        verifyNoInteractions(photoDeletionService);
    }

    @Test
    void consume_rejectsMissingKey() {
        ObjectDeletedEvent event = deleteEvent("lego-photos-sandbox", " ");

        assertThatThrownBy(() -> listener.consume(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Object key is missing");

        verifyNoInteractions(photoDeletionService);
    }

    private static ObjectDeletedEvent deleteEvent(String bucket, String key) {
        return ObjectDeletedEvent.builder()
                .bucket(bucket)
                .key(key)
                .eventName("s3:ObjectRemoved:Delete")
                .currentTimeStamp(1L)
                .build();
    }
}
