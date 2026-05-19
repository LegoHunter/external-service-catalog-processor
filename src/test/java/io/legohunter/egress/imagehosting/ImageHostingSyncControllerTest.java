package io.legohunter.egress.imagehosting;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(syncService.sync(org.mockito.ArgumentMatchers.any())).thenReturn(serviceResult);

        ResponseEntity<ImageHostingSyncResult> response = controller.syncItemInventory(100, true, null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isSameAs(serviceResult);

        ArgumentCaptor<ImageHostingSyncRequest> requestCaptor = ArgumentCaptor.forClass(ImageHostingSyncRequest.class);
        verify(syncService).sync(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ImageHostingSyncRequest::getItemInventoryId,
                        ImageHostingSyncRequest::getExternalServiceId,
                        ImageHostingSyncRequest::isDryRun
                )
                .containsExactly(100, null, true);
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
        when(syncService.sync(org.mockito.ArgumentMatchers.any())).thenReturn(serviceResult);

        controller.syncItemInventory(100, false, 10);

        ArgumentCaptor<ImageHostingSyncRequest> requestCaptor = ArgumentCaptor.forClass(ImageHostingSyncRequest.class);
        verify(syncService).sync(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ImageHostingSyncRequest::getItemInventoryId,
                        ImageHostingSyncRequest::getExternalServiceId,
                        ImageHostingSyncRequest::isDryRun
                )
                .containsExactly(100, 10, false);
    }
}
