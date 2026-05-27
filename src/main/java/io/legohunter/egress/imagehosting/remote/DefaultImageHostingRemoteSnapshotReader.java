package io.legohunter.egress.imagehosting.remote;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumPage;
import io.legohunter.imaging.model.HostedAlbumPhotoSearchRequest;
import io.legohunter.imaging.model.HostedAlbumSearchRequest;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.model.HostedPhotoPage;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultImageHostingRemoteSnapshotReader implements ImageHostingRemoteSnapshotReader {
    private static final int DEFAULT_PAGE_SIZE = 500;

    private final Optional<ImageHostingService> imageHostingService;
    private final ImageHostingSyncProperties properties;

    @Override
    public ImageHostingRemoteSnapshot read(ImageHostingRemoteSnapshotRequest request) {
        if (request == null || isBlank(request.getAlbumId())) {
            throw new IllegalArgumentException("albumId is required");
        }

        ImageHostingSyncProperties.ResolvedProvider provider = properties.resolveProvider(
                request.getProvider(),
                request.getExternalServiceId()
        );
        ImageHostingRemoteSnapshot.ImageHostingRemoteSnapshotBuilder snapshot = ImageHostingRemoteSnapshot.builder()
                .provider(provider.provider())
                .externalServiceId(provider.externalServiceId())
                .userId(request.getUserId())
                .requestedAlbumId(request.getAlbumId());

        AlbumLookupResult albumLookup = findAlbum(request, snapshot);
        snapshot.albumPagesRead(albumLookup.pagesRead());
        if (albumLookup.failureMessage() != null) {
            return snapshot.failureMessage(albumLookup.failureMessage()).build();
        }
        if (albumLookup.album().isEmpty()) {
            log.info(
                    "image_hosting.remote_snapshot.album_not_found provider={} externalServiceId={} albumId={} albumPagesRead={}",
                    provider.provider(),
                    provider.externalServiceId(),
                    request.getAlbumId(),
                    albumLookup.pagesRead()
            );
            return snapshot.build();
        }

        HostedAlbum album = albumLookup.album().orElseThrow();
        snapshot.album(album);
        PhotoLookupResult photoLookup = readAlbumPhotos(request, snapshot);
        snapshot.photoPagesRead(photoLookup.pagesRead());
        snapshot.photos(photoLookup.photos());
        if (photoLookup.failureMessage() != null) {
            snapshot.failureMessage(photoLookup.failureMessage());
        }

        log.info(
                "image_hosting.remote_snapshot.completed provider={} externalServiceId={} albumId={} albumFound={} photoCount={} albumPagesRead={} photoPagesRead={} failureCount={}",
                provider.provider(),
                provider.externalServiceId(),
                request.getAlbumId(),
                true,
                photoLookup.photos().size(),
                albumLookup.pagesRead(),
                photoLookup.pagesRead(),
                photoLookup.failureMessage() == null ? 0 : 1
        );
        return snapshot.build();
    }

    private AlbumLookupResult findAlbum(
            ImageHostingRemoteSnapshotRequest request,
            ImageHostingRemoteSnapshot.ImageHostingRemoteSnapshotBuilder snapshot
    ) {
        int page = 1;
        int pagesRead = 0;
        int pageSize = effectivePageSize(request.getAlbumPageSize());
        while (true) {
            PhotoServiceResponse<HostedAlbumPage> response = imageHostingService().listAlbums(new SimplePhotoServiceRequest<>(
                    HostedAlbumSearchRequest.builder()
                            .userId(request.getUserId())
                            .page(page)
                            .perPage(pageSize)
                            .build()
            ));
            if (response.isError()) {
                return new AlbumLookupResult(
                        Optional.empty(),
                        pagesRead,
                        responseMessage(response, "Image hosting provider failed to list albums")
                );
            }

            HostedAlbumPage albumPage = response.get();
            pagesRead++;
            Optional<HostedAlbum> album = Optional.ofNullable(albumPage)
                    .map(HostedAlbumPage::getAlbums)
                    .orElse(List.of())
                    .stream()
                    .filter(candidate -> request.getAlbumId().equals(candidate.getId()))
                    .findFirst();
            if (album.isPresent()) {
                return new AlbumLookupResult(album, pagesRead, null);
            }

            int totalPages = Optional.ofNullable(albumPage)
                    .map(HostedAlbumPage::getPages)
                    .orElse(0);
            if (totalPages <= page || totalPages <= 0) {
                return new AlbumLookupResult(Optional.empty(), pagesRead, null);
            }
            page++;
        }
    }

    private PhotoLookupResult readAlbumPhotos(
            ImageHostingRemoteSnapshotRequest request,
            ImageHostingRemoteSnapshot.ImageHostingRemoteSnapshotBuilder snapshot
    ) {
        int page = 1;
        int pagesRead = 0;
        int pageSize = effectivePageSize(request.getPhotoPageSize());
        List<HostedPhoto> photos = new ArrayList<>();
        while (true) {
            PhotoServiceResponse<HostedPhotoPage> response = imageHostingService().listAlbumPhotos(new SimplePhotoServiceRequest<>(
                    HostedAlbumPhotoSearchRequest.builder()
                            .albumId(request.getAlbumId())
                            .page(page)
                            .perPage(pageSize)
                            .build()
            ));
            if (response.isError()) {
                return new PhotoLookupResult(
                        photos,
                        pagesRead,
                        responseMessage(response, "Image hosting provider failed to list album photos")
                );
            }

            HostedPhotoPage photoPage = response.get();
            pagesRead++;
            photos.addAll(Optional.ofNullable(photoPage)
                    .map(HostedPhotoPage::getPhotos)
                    .orElse(List.of()));

            int totalPages = Optional.ofNullable(photoPage)
                    .map(HostedPhotoPage::getPages)
                    .orElse(0);
            if (totalPages <= page || totalPages <= 0) {
                return new PhotoLookupResult(photos, pagesRead, null);
            }
            page++;
        }
    }

    private ImageHostingService imageHostingService() {
        return imageHostingService.orElseThrow(() -> new IllegalStateException("No ImageHostingService bean is configured"));
    }

    private int effectivePageSize(int pageSize) {
        return pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
    }

    private String responseMessage(PhotoServiceResponse<?> response, String defaultMessage) {
        if (!isBlank(response.responseMessage())) {
            return response.responseMessage();
        }
        if (response.responseCode() != null) {
            return "%s; responseCode=[%s]".formatted(defaultMessage, response.responseCode());
        }
        return defaultMessage;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record AlbumLookupResult(Optional<HostedAlbum> album, int pagesRead, String failureMessage) {
    }

    private record PhotoLookupResult(List<HostedPhoto> photos, int pagesRead, String failureMessage) {
    }
}
