package io.legohunter.egress.imagehosting;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.imaging.model.AlbumManifest;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumMembershipRequest;
import io.legohunter.imaging.model.PhotoMetaDataV1;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.model.SimplePhotoServiceRequest;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.ingress.s3.api.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.legohunter.data.enums.ExternalSyncStatus.FAILED;
import static io.legohunter.data.enums.ExternalSyncStatus.PENDING;
import static io.legohunter.data.enums.ExternalSyncStatus.SYNCED;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultImageHostingSyncService implements ImageHostingSyncService {
    private static final String TEMP_FILE_SUFFIX = ".jpg";

    private final Optional<ImageHostingService> imageHostingService;
    private final MinioService minioService;
    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalImageDao externalImageDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ExternalImageAlbumImageDao externalImageAlbumImageDao;
    private final ImageHostingSyncProperties properties;

    @Override
    public ImageHostingSyncResult syncItemInventory(Integer itemInventoryId) {
        return sync(ImageHostingSyncRequest.builder()
                .itemInventoryId(itemInventoryId)
                .build());
    }

    @Override
    public ImageHostingSyncResult sync(ImageHostingSyncRequest request) {
        if (request == null || request.getItemInventoryId() == null) {
            throw new IllegalArgumentException("itemInventoryId is required");
        }

        Integer externalServiceId = Optional.ofNullable(request.getExternalServiceId())
                .orElse(properties.getExternalServiceId());
        ItemInventory inventory = itemInventoryDao.findByItemInventoryId(request.getItemInventoryId())
                .orElseThrow(() -> new IllegalArgumentException("No item inventory found for id [%s]".formatted(request.getItemInventoryId())));
        List<ItemInventoryPhoto> photos = sortedPhotos(itemInventoryPhotoDao.findByItemInventoryId(inventory.getItemInventoryId()));
        SyncAccumulator accumulator = new SyncAccumulator(inventory.getItemInventoryId(), externalServiceId, request.isDryRun(), photos.size());

        if (photos.isEmpty()) {
            log.info("image_hosting.sync.no_photos itemInventoryId={} externalServiceId={}", inventory.getItemInventoryId(), externalServiceId);
            return accumulator.toResult();
        }

        if (request.isDryRun()) {
            log.info(
                    "image_hosting.sync.dry_run itemInventoryId={} externalServiceId={} photosDiscovered={}",
                    inventory.getItemInventoryId(),
                    externalServiceId,
                    photos.size()
            );
            return accumulator.toResult();
        }

        ExternalImageAlbum album = externalImageAlbumDao.findOrCreateForItem(ExternalImageAlbum.builder()
                .externalServiceId(externalServiceId)
                .itemInventoryId(inventory.getItemInventoryId())
                .title(albumTitle(inventory))
                .syncStatus(PENDING)
                .build());

        List<SyncedPhoto> syncedPhotos = new ArrayList<>();
        for (ItemInventoryPhoto photo : photos) {
            syncPhoto(externalServiceId, photo, accumulator).ifPresent(syncedPhotos::add);
        }

        if (syncedPhotos.isEmpty()) {
            markAlbumFailed(album, "No photos were available to link after upload sync");
            return accumulator.toResult();
        }

        if (isBlank(album.getExternalAlbumId())) {
            createAlbum(album, inventory, syncedPhotos, accumulator);
        }

        if (!isBlank(album.getExternalAlbumId())) {
            updateAlbumMembership(album, syncedPhotos, accumulator);
        }

        return accumulator.toResult();
    }

    private Optional<SyncedPhoto> syncPhoto(Integer externalServiceId, ItemInventoryPhoto photo, SyncAccumulator accumulator) {
        Optional<ExternalImage> existing = externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(
                externalServiceId,
                photo.getItemInventoryPhotoId()
        );

        if (existing.map(ExternalImage::getExternalServiceImageId).filter(id -> !isBlank(id)).isPresent()) {
            accumulator.photosSkipped++;
            return existing.map(externalImage -> new SyncedPhoto(photo, externalImage));
        }

        try {
            PhotoMetaDataV1 photoMetaData = loadPhotoMetaData(photo);
            try {
                PhotoServiceResponse<String> response = imageHostingService().uploadPhoto(new SimplePhotoServiceRequest<>(photoMetaData));
                if (response.isError()) {
                    String message = responseMessage(response);
                    saveFailedImage(externalServiceId, photo, existing, message);
                    accumulator.recordPhotoFailure(photo, message);
                    return Optional.empty();
                }

                ExternalImage externalImage = saveSyncedImage(externalServiceId, photo, existing, response.get());
                accumulator.photosUploaded++;
                return Optional.of(new SyncedPhoto(photo, externalImage));
            } finally {
                Files.deleteIfExists(photoMetaData.getAbsolutePath());
            }
        } catch (Exception e) {
            saveFailedImage(externalServiceId, photo, existing, e.getMessage());
            accumulator.recordPhotoFailure(photo, e.getMessage());
            return Optional.empty();
        }
    }

    private PhotoMetaDataV1 loadPhotoMetaData(ItemInventoryPhoto photo) throws IOException {
        Files.createDirectories(properties.getTempDirectory());
        Path tempFile = Files.createTempFile(properties.getTempDirectory(), "photo-%s-".formatted(photo.getItemInventoryPhotoId()), TEMP_FILE_SUFFIX);
        try {
            // lego-imaging currently uploads from files; keep this as the only temp-file adapter point.
            try (InputStream inputStream = minioService.getObject(photo.getS3Bucket(), photo.getS3Key())) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }

        PhotoMetaDataV1 photoMetaData = new PhotoMetaDataV1(tempFile);
        photoMetaData.setMd5(photo.getMd5());
        return photoMetaData;
    }

    private ExternalImage saveSyncedImage(
            Integer externalServiceId,
            ItemInventoryPhoto photo,
            Optional<ExternalImage> existing,
            String externalServiceImageId
    ) {
        ZonedDateTime now = ZonedDateTime.now();
        ExternalImage externalImage = existing.orElseGet(() -> ExternalImage.builder()
                .externalServiceId(externalServiceId)
                .itemInventoryPhotoId(photo.getItemInventoryPhotoId())
                .build());

        externalImage.setExternalServiceImageId(externalServiceImageId);
        externalImage.setTitle(photoTitle(photo));
        externalImage.setMd5AtUpload(photo.getMd5());
        externalImage.setSyncStatus(SYNCED);
        externalImage.setErrorMessage(null);
        externalImage.setUploadedAt(Optional.ofNullable(externalImage.getUploadedAt()).orElse(now));
        externalImage.setLastSyncedAt(now);
        return Optional.ofNullable(externalImageDao.upsert(externalImage)).orElse(externalImage);
    }

    private void saveFailedImage(
            Integer externalServiceId,
            ItemInventoryPhoto photo,
            Optional<ExternalImage> existing,
            String errorMessage
    ) {
        ExternalImage externalImage = existing.orElseGet(() -> ExternalImage.builder()
                .externalServiceId(externalServiceId)
                .itemInventoryPhotoId(photo.getItemInventoryPhotoId())
                .build());
        externalImage.setTitle(photoTitle(photo));
        externalImage.setMd5AtUpload(photo.getMd5());
        externalImage.setSyncStatus(FAILED);
        externalImage.setErrorMessage(errorMessage);
        externalImage.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.upsert(externalImage);
    }

    private void createAlbum(
            ExternalImageAlbum album,
            ItemInventory inventory,
            List<SyncedPhoto> syncedPhotos,
            SyncAccumulator accumulator
    ) {
        try {
            AlbumManifest manifest = new AlbumManifest();
            manifest.setUuid(inventory.getUuid());
            manifest.setTitle(albumTitle(inventory));
            manifest.setDescription(albumDescription(inventory));
            manifest.setPhotos(syncedPhotos.stream()
                    .map(this::toManifestPhoto)
                    .toList());

            PhotoServiceResponse<HostedAlbum> response = imageHostingService().createAlbum(new SimplePhotoServiceRequest<>(manifest));
            if (response.isError()) {
                String message = responseMessage(response);
                markAlbumFailed(album, message);
                accumulator.failureMessages.add(message);
                return;
            }

            HostedAlbum hostedAlbum = response.get();
            album.setExternalAlbumId(hostedAlbum.getId());
            album.setAlbumUrl(hostedAlbum.getUrl());
            album.setSyncStatus(SYNCED);
            album.setErrorMessage(null);
            album.setLastSyncedAt(ZonedDateTime.now());
            externalImageAlbumDao.update(album);
            accumulator.albumCreated = true;
        } catch (Exception e) {
            markAlbumFailed(album, e.getMessage());
            accumulator.failureMessages.add(e.getMessage());
        }
    }

    private void updateAlbumMembership(
            ExternalImageAlbum album,
            List<SyncedPhoto> syncedPhotos,
            SyncAccumulator accumulator
    ) {
        Set<String> photoIds = syncedPhotos.stream()
                .map(SyncedPhoto::externalImage)
                .map(ExternalImage::getExternalServiceImageId)
                .filter(id -> !isBlank(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String primaryPhotoId = primaryPhoto(syncedPhotos)
                .map(SyncedPhoto::externalImage)
                .map(ExternalImage::getExternalServiceImageId)
                .orElseGet(() -> photoIds.stream().findFirst().orElseThrow());

        try {
            HostedAlbumMembershipRequest membershipRequest = HostedAlbumMembershipRequest.builder()
                    .albumId(album.getExternalAlbumId())
                    .primaryPhotoId(primaryPhotoId)
                    .photoIds(photoIds)
                    .build();
            PhotoServiceResponse<Void> response = imageHostingService().updateAlbumMembership(new SimplePhotoServiceRequest<>(membershipRequest));
            if (response.isError()) {
                String message = responseMessage(response);
                markAlbumFailed(album, message);
                accumulator.failureMessages.add(message);
                return;
            }

            persistAlbumMembership(album, syncedPhotos, primaryPhotoId, accumulator);
            album.setSyncStatus(SYNCED);
            album.setErrorMessage(null);
            album.setLastSyncedAt(ZonedDateTime.now());
            externalImageAlbumDao.update(album);
            accumulator.membershipUpdated = true;
        } catch (Exception e) {
            markAlbumFailed(album, e.getMessage());
            accumulator.failureMessages.add(e.getMessage());
        }
    }

    private void persistAlbumMembership(
            ExternalImageAlbum album,
            List<SyncedPhoto> syncedPhotos,
            String primaryPhotoId,
            SyncAccumulator accumulator
    ) {
        for (int i = 0; i < syncedPhotos.size(); i++) {
            SyncedPhoto syncedPhoto = syncedPhotos.get(i);
            Long externalImageId = syncedPhoto.externalImage().getExternalImageId();
            if (externalImageId == null) {
                accumulator.failureMessages.add("Cannot persist album membership for photo [%s] because externalImageId is missing"
                        .formatted(syncedPhoto.photo().getItemInventoryPhotoId()));
                continue;
            }

            externalImageAlbumImageDao.upsert(ExternalImageAlbumImage.builder()
                    .externalImageAlbumId(album.getExternalImageAlbumId())
                    .externalImageId(externalImageId)
                    .sortOrder(i + 1)
                    .primary(primaryPhotoId.equals(syncedPhoto.externalImage().getExternalServiceImageId()))
                    .build());
        }
    }

    private void markAlbumFailed(ExternalImageAlbum album, String message) {
        album.setSyncStatus(FAILED);
        album.setErrorMessage(message);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
    }

    private PhotoMetaDataV1 toManifestPhoto(SyncedPhoto syncedPhoto) {
        PhotoMetaDataV1 photoMetaData = new PhotoMetaDataV1(Path.of(photoTitle(syncedPhoto.photo())));
        photoMetaData.setPhotoId(syncedPhoto.externalImage().getExternalServiceImageId());
        photoMetaData.setPrimary(Boolean.TRUE.equals(syncedPhoto.photo().getPrimary()));
        photoMetaData.setMd5(syncedPhoto.photo().getMd5());
        return photoMetaData;
    }

    private Optional<SyncedPhoto> primaryPhoto(List<SyncedPhoto> syncedPhotos) {
        return syncedPhotos.stream()
                .filter(syncedPhoto -> Boolean.TRUE.equals(syncedPhoto.photo().getPrimary()))
                .findFirst()
                .or(() -> syncedPhotos.stream().findFirst());
    }

    private List<ItemInventoryPhoto> sortedPhotos(Set<ItemInventoryPhoto> photos) {
        return photos.stream()
                .sorted(Comparator.comparing(ItemInventoryPhoto::getItemInventoryPhotoId, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    private String albumTitle(ItemInventory inventory) {
        if (!isBlank(inventory.getDescription())) {
            return inventory.getDescription();
        }
        return "Inventory %s".formatted(inventory.getUuid());
    }

    private String albumDescription(ItemInventory inventory) {
        return "Inventory item [%s]".formatted(inventory.getUuid());
    }

    private String photoTitle(ItemInventoryPhoto photo) {
        if (!isBlank(photo.getCaption())) {
            return photo.getCaption();
        }
        if (!isBlank(photo.getFileName())) {
            return photo.getFileName();
        }
        return "photo-%s.jpg".formatted(photo.getItemInventoryPhotoId());
    }

    private String responseMessage(PhotoServiceResponse<?> response) {
        if (!isBlank(response.responseMessage())) {
            return response.responseMessage();
        }
        return "Image hosting provider returned response code [%s]".formatted(response.responseCode());
    }

    private ImageHostingService imageHostingService() {
        return imageHostingService.orElseThrow(() -> new IllegalStateException("No ImageHostingService bean is configured"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SyncedPhoto(ItemInventoryPhoto photo, ExternalImage externalImage) {
    }

    private static class SyncAccumulator {
        private final Integer itemInventoryId;
        private final Integer externalServiceId;
        private final boolean dryRun;
        private final int photosDiscovered;
        private final List<String> failureMessages = new ArrayList<>();
        private int photosUploaded;
        private int photosSkipped;
        private int photosFailed;
        private boolean albumCreated;
        private boolean membershipUpdated;

        private SyncAccumulator(Integer itemInventoryId, Integer externalServiceId, boolean dryRun, int photosDiscovered) {
            this.itemInventoryId = itemInventoryId;
            this.externalServiceId = externalServiceId;
            this.dryRun = dryRun;
            this.photosDiscovered = photosDiscovered;
        }

        private void recordPhotoFailure(ItemInventoryPhoto photo, String message) {
            photosFailed++;
            failureMessages.add("Photo [%s] failed: %s".formatted(photo.getItemInventoryPhotoId(), message));
        }

        private ImageHostingSyncResult toResult() {
            return ImageHostingSyncResult.builder()
                    .itemInventoryId(itemInventoryId)
                    .externalServiceId(externalServiceId)
                    .dryRun(dryRun)
                    .photosDiscovered(photosDiscovered)
                    .photosUploaded(photosUploaded)
                    .photosSkipped(photosSkipped)
                    .photosFailed(photosFailed)
                    .albumCreated(albumCreated)
                    .membershipUpdated(membershipUpdated)
                    .failureMessages(failureMessages)
                    .build();
        }
    }
}
