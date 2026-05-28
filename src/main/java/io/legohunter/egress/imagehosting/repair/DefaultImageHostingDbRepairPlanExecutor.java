package io.legohunter.egress.imagehosting.repair;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionResult;
import io.legohunter.imaging.service.sync.model.SyncActionStatus;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncPlanMode;
import io.legohunter.imaging.service.sync.model.SyncReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DefaultImageHostingDbRepairPlanExecutor implements ImageHostingDbRepairPlanExecutor {
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalImageDao externalImageDao;
    private final ExternalImageAlbumDao externalImageAlbumDao;
    private final ExternalImageAlbumImageDao externalImageAlbumImageDao;

    @Override
    public SyncReport execute(SyncPlan plan, boolean allowReviewRequired) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is required");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        List<SyncActionResult> results = new ArrayList<>();
        for (SyncAction action : plan.getActions()) {
            results.add(executeAction(action, allowReviewRequired));
        }

        return SyncReport.builder()
                .reportId("image-hosting-repair-report-%s".formatted(UUID.randomUUID()))
                .planId(plan.getPlanId())
                .mode(SyncPlanMode.APPLY)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .results(results)
                .build();
    }

    private SyncActionResult executeAction(SyncAction action, boolean allowReviewRequired) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (action == null) {
            return result(null, SyncActionStatus.FAILED, "Action is required", startedAt, null, null);
        }
        if (action.isBlocked()) {
            return result(action, SyncActionStatus.BLOCKED, "Action is blocked by the repair plan", startedAt, null, null);
        }
        if (action.requiresReview() && !allowReviewRequired) {
            return result(action, SyncActionStatus.BLOCKED, "Action requires review before apply", startedAt, null, null);
        }

        try {
            return switch (action.getType()) {
                case REPAIR_ALBUM_ID -> repairAlbumId(action, startedAt);
                case REPAIR_PHOTO_ID -> repairPhotoId(action, startedAt);
                case UPDATE_ALBUM_MEMBERSHIP, FIX_PRIMARY_PHOTO -> repairAlbumMembership(action, startedAt);
                default -> result(
                        action,
                        SyncActionStatus.SKIPPED,
                        "Action type [%s] is not supported by the DB repair executor".formatted(action.getType()),
                        startedAt,
                        null,
                        null
                );
            };
        } catch (RuntimeException e) {
            return result(action, SyncActionStatus.FAILED, e.getMessage(), startedAt, null, null);
        }
    }

    private SyncActionResult repairAlbumId(SyncAction action, LocalDateTime startedAt) {
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        Integer itemInventoryId = requiredInteger(action, "itemInventoryId");
        String remoteAlbumId = requiredAttribute(action, "remoteAlbumId");

        Optional<ExternalImageAlbum> existingRemoteOwner =
                externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(externalServiceId, remoteAlbumId);
        if (existingRemoteOwner.isPresent()
                && !itemInventoryId.equals(existingRemoteOwner.get().getItemInventoryId())) {
            return result(
                    action,
                    SyncActionStatus.FAILED,
                    "Remote album id [%s] is already linked to item inventory [%s]".formatted(
                            remoteAlbumId,
                            existingRemoteOwner.get().getItemInventoryId()
                    ),
                    startedAt,
                    remoteAlbumId,
                    null
            );
        }

        ExternalImageAlbum album = findOrCreateAlbum(action, externalServiceId, itemInventoryId);
        album.setExternalAlbumId(remoteAlbumId);
        attribute(action, "remoteAlbumUrl").filter(this::hasText).ifPresent(album::setAlbumUrl);
        attribute(action, "remoteTitle").filter(this::hasText).ifPresent(album::setTitle);
        album.setSyncStatus(ExternalSyncStatus.SYNCED);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.upsert(album);
        return result(action, SyncActionStatus.SUCCEEDED, "Repaired DB Flickr album linkage", startedAt, remoteAlbumId, null);
    }

    private SyncActionResult repairPhotoId(SyncAction action, LocalDateTime startedAt) {
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        Integer itemInventoryPhotoId = requiredInteger(action, "itemInventoryPhotoId");
        String remotePhotoId = requiredAttribute(action, "remotePhotoId");

        Optional<ExternalImage> existingRemoteOwner =
                externalImageDao.findByExternalServiceIdAndExternalServiceImageId(externalServiceId, remotePhotoId);
        if (existingRemoteOwner.isPresent()
                && !itemInventoryPhotoId.equals(existingRemoteOwner.get().getItemInventoryPhotoId())) {
            return result(
                    action,
                    SyncActionStatus.FAILED,
                    "Remote photo id [%s] is already linked to item inventory photo [%s]".formatted(
                            remotePhotoId,
                            existingRemoteOwner.get().getItemInventoryPhotoId()
                    ),
                    startedAt,
                    null,
                    remotePhotoId
            );
        }

        ItemInventoryPhoto inventoryPhoto = itemInventoryPhotoDao.findByItemInventoryPhotoId(itemInventoryPhotoId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No item inventory photo found for id [%s]".formatted(itemInventoryPhotoId)
                ));
        ExternalImage image = findOrCreateImage(action, externalServiceId, itemInventoryPhotoId);
        image.setExternalServiceImageId(remotePhotoId);
        attribute(action, "remoteTitle").filter(this::hasText).ifPresent(image::setTitle);
        attribute(action, "remotePhotoUrl").filter(this::hasText).ifPresent(image::setImageUrl);
        image.setMd5AtUpload(inventoryPhoto.getMd5());
        image.setMetadataHashAtSync(inventoryPhoto.getMetadataHash());
        image.setSyncStatus(ExternalSyncStatus.SYNCED);
        image.setErrorMessage(null);
        image.setUploadedAt(Optional.ofNullable(image.getUploadedAt()).orElse(ZonedDateTime.now()));
        image.setLastSyncedAt(ZonedDateTime.now());
        externalImageDao.upsert(image);
        return result(action, SyncActionStatus.SUCCEEDED, "Repaired DB Flickr photo linkage", startedAt, null, remotePhotoId);
    }

    private SyncActionResult repairAlbumMembership(SyncAction action, LocalDateTime startedAt) {
        Integer externalServiceId = requiredInteger(action, "externalServiceId");
        String remoteAlbumId = requiredAttribute(action, "externalAlbumId");
        Set<String> remotePhotoIds = csvAttribute(action, "remotePhotoIds");
        if (remotePhotoIds.isEmpty()) {
            return result(action, SyncActionStatus.FAILED, "remotePhotoIds is required", startedAt, remoteAlbumId, null);
        }

        ExternalImageAlbum album = findAlbum(action, externalServiceId, remoteAlbumId);
        String remotePrimaryPhotoId = attribute(action, "remotePrimaryPhotoId")
                .filter(this::hasText)
                .orElseGet(() -> remotePhotoIds.stream().findFirst().orElse(null));
        externalImageAlbumImageDao.deleteByExternalImageAlbumId(album.getExternalImageAlbumId());

        int sortOrder = 1;
        for (String remotePhotoId : remotePhotoIds) {
            ExternalImage image = externalImageDao.findByExternalServiceIdAndExternalServiceImageId(externalServiceId, remotePhotoId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No external image row found for Flickr photo id [%s]".formatted(remotePhotoId)
                    ));
            externalImageAlbumImageDao.upsert(ExternalImageAlbumImage.builder()
                    .externalImageAlbumId(album.getExternalImageAlbumId())
                    .externalImageId(image.getExternalImageId())
                    .sortOrder(sortOrder++)
                    .primary(remotePhotoId.equals(remotePrimaryPhotoId))
                    .build());
        }

        album.setSyncStatus(ExternalSyncStatus.SYNCED);
        album.setErrorMessage(null);
        album.setLastSyncedAt(ZonedDateTime.now());
        externalImageAlbumDao.update(album);
        return result(action, SyncActionStatus.SUCCEEDED, "Repaired DB Flickr album membership rows", startedAt, remoteAlbumId, remotePrimaryPhotoId);
    }

    private ExternalImageAlbum findOrCreateAlbum(SyncAction action, Integer externalServiceId, Integer itemInventoryId) {
        Optional<Long> externalImageAlbumId = longAttribute(action, "externalImageAlbumId");
        if (externalImageAlbumId.isPresent()) {
            return externalImageAlbumDao.findByExternalImageAlbumId(externalImageAlbumId.get())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No external image album found for id [%s]".formatted(externalImageAlbumId.get())
                    ));
        }

        return externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(externalServiceId, itemInventoryId)
                .orElseGet(() -> externalImageAlbumDao.insert(ExternalImageAlbum.builder()
                        .externalServiceId(externalServiceId)
                        .itemInventoryId(itemInventoryId)
                        .title(attribute(action, "desiredTitle").orElse(null))
                        .syncStatus(ExternalSyncStatus.PENDING)
                        .build()));
    }

    private ExternalImageAlbum findAlbum(SyncAction action, Integer externalServiceId, String remoteAlbumId) {
        Optional<Long> externalImageAlbumId = longAttribute(action, "externalImageAlbumId");
        if (externalImageAlbumId.isPresent()) {
            return externalImageAlbumDao.findByExternalImageAlbumId(externalImageAlbumId.get())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No external image album found for id [%s]".formatted(externalImageAlbumId.get())
                    ));
        }

        return externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(externalServiceId, remoteAlbumId)
                .or(() -> externalImageAlbumDao.findByExternalServiceIdAndItemInventoryId(
                        externalServiceId,
                        requiredInteger(action, "itemInventoryId")
                ))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No external image album found for Flickr album id [%s]".formatted(remoteAlbumId)
                ));
    }

    private ExternalImage findOrCreateImage(SyncAction action, Integer externalServiceId, Integer itemInventoryPhotoId) {
        Optional<Long> externalImageId = longAttribute(action, "externalImageId");
        if (externalImageId.isPresent()) {
            return externalImageDao.findByExternalImageId(externalImageId.get())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No external image found for id [%s]".formatted(externalImageId.get())
                    ));
        }

        return externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(externalServiceId, itemInventoryPhotoId)
                .orElseGet(() -> ExternalImage.builder()
                        .externalServiceId(externalServiceId)
                        .itemInventoryPhotoId(itemInventoryPhotoId)
                        .build());
    }

    private SyncActionResult result(
            SyncAction action,
            SyncActionStatus status,
            String message,
            LocalDateTime startedAt,
            String albumId,
            String photoId
    ) {
        return SyncActionResult.builder()
                .actionId(action == null ? null : action.getActionId())
                .type(action == null ? null : action.getType())
                .status(status)
                .albumId(albumId == null && action != null ? action.getAlbumId() : albumId)
                .photoId(photoId == null && action != null ? action.getPhotoId() : photoId)
                .message(message)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    private Integer requiredInteger(SyncAction action, String attributeName) {
        return attribute(action, attributeName)
                .filter(this::hasText)
                .map(Integer::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("%s is required".formatted(attributeName)));
    }

    private Optional<Long> longAttribute(SyncAction action, String attributeName) {
        return attribute(action, attributeName)
                .filter(this::hasText)
                .map(Long::valueOf);
    }

    private String requiredAttribute(SyncAction action, String attributeName) {
        return attribute(action, attributeName)
                .filter(this::hasText)
                .orElseThrow(() -> new IllegalArgumentException("%s is required".formatted(attributeName)));
    }

    private Optional<String> attribute(SyncAction action, String attributeName) {
        return Optional.ofNullable(action)
                .map(SyncAction::getAttributes)
                .map(attributes -> attributes.get(attributeName));
    }

    private Set<String> csvAttribute(SyncAction action, String attributeName) {
        return attribute(action, attributeName)
                .stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
