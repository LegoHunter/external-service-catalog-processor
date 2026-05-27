package io.legohunter.egress.imagehosting.remote;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedAlbumPhotoSearchRequest;
import io.legohunter.imaging.model.HostedAlbumSearchRequest;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.PhotoServiceErrorType;
import io.legohunter.imaging.model.PhotoServiceRequest;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultImageHostingRemoteSnapshotReaderTest {
    private static final int FLICKR_SERVICE_ID = 10;

    @Mock
    private ImageHostingService imageHostingService;

    private DefaultImageHostingRemoteSnapshotReader reader;

    @BeforeEach
    void setUp() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getSync().setExternalServiceId(FLICKR_SERVICE_ID);
        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setEnabled(true);
        flickr.setExternalServiceId(FLICKR_SERVICE_ID);
        flickr.setMetricsTag("flickr");
        properties.getProviders().put("flickr", flickr);

        reader = new DefaultImageHostingRemoteSnapshotReader(Optional.of(imageHostingService), properties);
    }

    @Test
    void readFindsAlbumAndPagesThroughPhotos() {
        when(imageHostingService.listAlbums(any()))
                .thenReturn(response(HostedAlbumPage.builder()
                        .album(album("other-album"))
                        .page(1)
                        .pages(2)
                        .perPage(1)
                        .total(2)
                        .build()))
                .thenReturn(response(HostedAlbumPage.builder()
                        .album(album("album-123"))
                        .page(2)
                        .pages(2)
                        .perPage(1)
                        .total(2)
                        .build()));
        when(imageHostingService.listAlbumPhotos(any()))
                .thenReturn(response(HostedPhotoPage.builder()
                        .photo(photo("photo-1", true))
                        .page(1)
                        .pages(2)
                        .perPage(1)
                        .total(2)
                        .build()))
                .thenReturn(response(HostedPhotoPage.builder()
                        .photo(photo("photo-2", false))
                        .page(2)
                        .pages(2)
                        .perPage(1)
                        .total(2)
                        .build()));

        ImageHostingRemoteSnapshot snapshot = reader.read(ImageHostingRemoteSnapshotRequest.builder()
                .albumId("album-123")
                .userId("user-123")
                .albumPageSize(1)
                .photoPageSize(1)
                .build());

        assertThat(snapshot.isSuccessful()).isTrue();
        assertThat(snapshot.isAlbumFound()).isTrue();
        assertThat(snapshot.getProvider()).isEqualTo("flickr");
        assertThat(snapshot.getExternalServiceId()).isEqualTo(FLICKR_SERVICE_ID);
        assertThat(snapshot.getRequestedAlbumId()).isEqualTo("album-123");
        assertThat(snapshot.getAlbum().getId()).isEqualTo("album-123");
        assertThat(snapshot.getAlbumPagesRead()).isEqualTo(2);
        assertThat(snapshot.getPhotoPagesRead()).isEqualTo(2);
        assertThat(snapshot.getPhotos())
                .extracting(HostedPhoto::getId)
                .containsExactly("photo-1", "photo-2");

        ArgumentCaptor<PhotoServiceRequest<HostedAlbumSearchRequest>> albumRequestCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService, times(2)).listAlbums(albumRequestCaptor.capture());
        assertThat(albumRequestCaptor.getAllValues())
                .extracting(request -> request.get().getPage())
                .containsExactly(1, 2);
        assertThat(albumRequestCaptor.getAllValues().getFirst().get())
                .extracting(
                        HostedAlbumSearchRequest::getUserId,
                        HostedAlbumSearchRequest::getPage,
                        HostedAlbumSearchRequest::getPerPage
                )
                .containsExactly("user-123", 1, 1);

        ArgumentCaptor<PhotoServiceRequest<HostedAlbumPhotoSearchRequest>> photoRequestCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService, times(2)).listAlbumPhotos(photoRequestCaptor.capture());
        assertThat(photoRequestCaptor.getAllValues())
                .extracting(request -> request.get().getPage())
                .containsExactly(1, 2);
        assertThat(photoRequestCaptor.getAllValues().getFirst().get())
                .extracting(
                        HostedAlbumPhotoSearchRequest::getAlbumId,
                        HostedAlbumPhotoSearchRequest::getPage,
                        HostedAlbumPhotoSearchRequest::getPerPage
                )
                .containsExactly("album-123", 1, 1);
    }

    @Test
    void readReturnsNotFoundSnapshotWithoutReadingPhotos() {
        when(imageHostingService.listAlbums(any())).thenReturn(response(HostedAlbumPage.builder()
                .album(album("other-album"))
                .page(1)
                .pages(1)
                .perPage(500)
                .total(1)
                .build()));

        ImageHostingRemoteSnapshot snapshot = reader.read(ImageHostingRemoteSnapshotRequest.builder()
                .albumId("missing-album")
                .build());

        assertThat(snapshot.isSuccessful()).isTrue();
        assertThat(snapshot.isAlbumFound()).isFalse();
        assertThat(snapshot.getPhotos()).isEmpty();
        assertThat(snapshot.getAlbumPagesRead()).isEqualTo(1);
        assertThat(snapshot.getPhotoPagesRead()).isZero();
        verify(imageHostingService, never()).listAlbumPhotos(any());
    }

    @Test
    void readCapturesAlbumListProviderFailure() {
        when(imageHostingService.listAlbums(any()))
                .thenReturn(errorResponse(99, "provider rejected album list"));

        ImageHostingRemoteSnapshot snapshot = reader.read(ImageHostingRemoteSnapshotRequest.builder()
                .albumId("album-123")
                .build());

        assertThat(snapshot.isSuccessful()).isFalse();
        assertThat(snapshot.isAlbumFound()).isFalse();
        assertThat(snapshot.getFailureMessages()).containsExactly("provider rejected album list");
        verify(imageHostingService, never()).listAlbumPhotos(any());
    }

    @Test
    void readCapturesPartialPhotoListProviderFailure() {
        when(imageHostingService.listAlbums(any())).thenReturn(response(HostedAlbumPage.builder()
                .album(album("album-123"))
                .page(1)
                .pages(1)
                .build()));
        when(imageHostingService.listAlbumPhotos(any()))
                .thenReturn(response(HostedPhotoPage.builder()
                        .photo(photo("photo-1", true))
                        .page(1)
                        .pages(2)
                        .build()))
                .thenReturn(errorResponse(98, "provider rejected photo list"));

        ImageHostingRemoteSnapshot snapshot = reader.read(ImageHostingRemoteSnapshotRequest.builder()
                .albumId("album-123")
                .build());

        assertThat(snapshot.isSuccessful()).isFalse();
        assertThat(snapshot.isAlbumFound()).isTrue();
        assertThat(snapshot.getPhotos())
                .extracting(HostedPhoto::getId)
                .containsExactly("photo-1");
        assertThat(snapshot.getFailureMessages()).containsExactly("provider rejected photo list");
        assertThat(snapshot.getPhotoPagesRead()).isEqualTo(1);
    }

    @Test
    void readRequiresAlbumId() {
        assertThatThrownBy(() -> reader.read(ImageHostingRemoteSnapshotRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("albumId is required");
    }

    private static HostedAlbum album(String id) {
        return HostedAlbum.builder()
                .id(id)
                .url("https://flickr.example/albums/%s".formatted(id))
                .title("Album " + id)
                .description("Description " + id)
                .primaryPhotoId("photo-1")
                .photoCount(2)
                .build();
    }

    private static HostedPhoto photo(String id, boolean primary) {
        return HostedPhoto.builder()
                .id(id)
                .title("Photo " + id)
                .description("Description " + id)
                .url("https://flickr.example/photos/%s".formatted(id))
                .primary(primary)
                .build();
    }

    private static <T> PhotoServiceResponse<T> response(T value) {
        return new TestPhotoServiceResponse<>(value, false, null, null, PhotoServiceErrorType.UNKNOWN);
    }

    private static <T> PhotoServiceResponse<T> errorResponse(Integer responseCode, String responseMessage) {
        return new TestPhotoServiceResponse<>(null, true, responseCode, responseMessage, PhotoServiceErrorType.UNKNOWN);
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
