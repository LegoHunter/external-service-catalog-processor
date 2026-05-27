package io.legohunter.egress.imagehosting.remote;

import io.legohunter.imaging.model.HostedAlbum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageHostingRemoteSnapshotControllerTest {

    @Test
    void readRemoteAlbumSnapshotBuildsRequestFromPathAndQueryParameters() {
        ImageHostingRemoteSnapshotReader reader = mock(ImageHostingRemoteSnapshotReader.class);
        ImageHostingRemoteSnapshot snapshot = ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId("album-123")
                .album(HostedAlbum.builder().id("album-123").build())
                .build();
        when(reader.read(any())).thenReturn(snapshot);
        ImageHostingRemoteSnapshotController controller = new ImageHostingRemoteSnapshotController(reader);

        ResponseEntity<ImageHostingRemoteSnapshot> response = controller.readRemoteAlbumSnapshot(
                "album-123",
                "flickr",
                10,
                "user-123",
                100,
                250
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(snapshot);

        ArgumentCaptor<ImageHostingRemoteSnapshotRequest> requestCaptor =
                ArgumentCaptor.forClass(ImageHostingRemoteSnapshotRequest.class);
        verify(reader).read(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ImageHostingRemoteSnapshotRequest::getAlbumId,
                        ImageHostingRemoteSnapshotRequest::getProvider,
                        ImageHostingRemoteSnapshotRequest::getExternalServiceId,
                        ImageHostingRemoteSnapshotRequest::getUserId,
                        ImageHostingRemoteSnapshotRequest::getAlbumPageSize,
                        ImageHostingRemoteSnapshotRequest::getPhotoPageSize
                )
                .containsExactly("album-123", "flickr", 10, "user-123", 100, 250);
    }
}
