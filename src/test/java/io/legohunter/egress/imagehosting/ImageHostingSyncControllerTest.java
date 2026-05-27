package io.legohunter.egress.imagehosting;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageHostingSyncControllerTest {

    @Test
    void syncItemInventoryBuildsSingleItemDryRunRequestByDefault() {
        ImageHostingSyncService syncService = mock(ImageHostingSyncService.class);
        ImageHostingSyncController controller = new ImageHostingSyncController(syncService);
        ImageHostingSyncResult serviceResult = ImageHostingSyncResult.builder()
                .itemInventoryId(100)
                .externalServiceId(10)
                .dryRun(true)
                .photosDiscovered(2)
                .build();
        when(syncService.sync(any())).thenReturn(serviceResult);

        ResponseEntity<ImageHostingSyncResult> response = controller.syncItemInventory(100, true, null, null, false);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isSameAs(serviceResult);

        ArgumentCaptor<ImageHostingSyncRequest> requestCaptor = ArgumentCaptor.forClass(ImageHostingSyncRequest.class);
        verify(syncService).sync(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ImageHostingSyncRequest::getItemInventoryId,
                        ImageHostingSyncRequest::getProvider,
                        ImageHostingSyncRequest::getExternalServiceId,
                        ImageHostingSyncRequest::isDryRun,
                        ImageHostingSyncRequest::isRetryFailed
                )
                .containsExactly(100, null, null, true, false);
    }

    @Test
    void syncItemInventoryCanOptIntoRealProviderSync() {
        ImageHostingSyncService syncService = mock(ImageHostingSyncService.class);
        ImageHostingSyncController controller = new ImageHostingSyncController(syncService);
        ImageHostingSyncResult serviceResult = ImageHostingSyncResult.builder()
                .itemInventoryId(100)
                .externalServiceId(10)
                .dryRun(false)
                .build();
        when(syncService.sync(any())).thenReturn(serviceResult);

        controller.syncItemInventory(100, false, "flickr", 10, true);

        ArgumentCaptor<ImageHostingSyncRequest> requestCaptor = ArgumentCaptor.forClass(ImageHostingSyncRequest.class);
        verify(syncService).sync(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ImageHostingSyncRequest::getItemInventoryId,
                        ImageHostingSyncRequest::getProvider,
                        ImageHostingSyncRequest::getExternalServiceId,
                        ImageHostingSyncRequest::isDryRun,
                        ImageHostingSyncRequest::isRetryFailed
                )
                .containsExactly(100, "flickr", 10, false, true);
    }
}
