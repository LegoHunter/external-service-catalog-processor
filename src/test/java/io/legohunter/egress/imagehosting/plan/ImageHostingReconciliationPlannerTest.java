package io.legohunter.egress.imagehosting.plan;

import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshot;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingPhoto;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedPhoto;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncPlanMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class ImageHostingReconciliationPlannerTest {
    private final ImageHostingReconciliationPlanner planner = new ImageHostingReconciliationPlanner(
            new ImageHostingPublishingPolicy(new ImageHostingSyncProperties())
    );

    @Test
    void planCreatesAlbumAndUploadsPhotosWhenDbStateHasNoExternalIds() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album(null),
                List.of(desiredPhoto(11, true, null, null, null))
        );

        SyncPlan plan = planner.plan(desiredState, null);

        assertThat(plan.getMode()).isEqualTo(SyncPlanMode.DRY_RUN);
        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPLOAD_PHOTO, SyncActionType.CREATE_ALBUM);
        assertThat(plan.getActions())
                .extracting(SyncAction::getSafety)
                .containsExactly(SyncActionSafety.SAFE_AUTOMATIC, SyncActionSafety.SAFE_AUTOMATIC);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("itemInventoryPhotoId", "11")
                .containsEntry("primary", "true");
    }

    @Test
    void planUpdatesAlbumAndPhotoMetadataWhenDbDesiredMetadataChanged() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-new", "metadata-old"))
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Old title", "Old description", "photo-11"),
                List.of(hostedPhoto("photo-11"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPDATE_PHOTO_METADATA, SyncActionType.UPDATE_ALBUM_METADATA);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("metadataHash", "metadata-new")
                .containsEntry("metadataHashAtSync", "metadata-old")
                .containsEntry("desiredTitle", "Caption 11")
                .containsEntry("desiredDescription", "Caption 11");
    }

    @Test
    void planRequiresReviewWhenDbAlbumIdIsMissingRemotely() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );

        SyncPlan plan = planner.plan(desiredState, ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId("album-100")
                .build());

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.REPAIR_ALBUM_ID);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.REQUIRES_REVIEW);
    }

    @Test
    void planUpdatesAlbumMembershipWhenDbPhotoIdIsMissingFromRemoteAlbum() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", null),
                List.of()
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPDATE_ALBUM_MEMBERSHIP);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.SAFE_AUTOMATIC);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("desiredOnlyPhotoIds", "photo-11")
                .containsEntry("remoteOnlyPhotoIds", "");
    }

    @Test
    void planRequiresReviewForMembershipUpdateThatWouldRemoveRemoteOnlyPhotos() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", "photo-remote-only"),
                List.of(hostedPhoto("photo-remote-only"), hostedPhoto("photo-11"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.UPDATE_ALBUM_MEMBERSHIP);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.REQUIRES_REVIEW);
        assertThat(plan.getActions().getFirst().getAttributes())
                .containsEntry("desiredPhotoIds", "photo-11")
                .containsEntry("remotePhotoIds", "photo-remote-only,photo-11")
                .containsEntry("remoteOnlyPhotoIds", "photo-remote-only");
    }

    @Test
    void planFixesPrimaryPhotoWhenMembershipAlreadyMatches() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(
                        desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"),
                        desiredPhoto(12, false, "photo-12", "metadata-12", "metadata-12")
                )
        );
        ImageHostingRemoteSnapshot remoteSnapshot = remoteSnapshot(
                hostedAlbum("album-100", "Desired album", "Inventory item [inventory-uuid]", "photo-12"),
                List.of(hostedPhoto("photo-11"), hostedPhoto("photo-12"))
        );

        SyncPlan plan = planner.plan(desiredState, remoteSnapshot);

        assertThat(plan.getActions())
                .extracting(SyncAction::getType)
                .containsExactly(SyncActionType.FIX_PRIMARY_PHOTO);
        assertThat(plan.getActions().getFirst().getSafety()).isEqualTo(SyncActionSafety.SAFE_AUTOMATIC);
    }

    @Test
    void planBlocksWhenRemoteSnapshotFailed() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState(
                album("album-100"),
                List.of(desiredPhoto(11, true, "photo-11", "metadata-11", "metadata-11"))
        );

        SyncPlan plan = planner.plan(desiredState, ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId("album-100")
                .failureMessage("Flickr unavailable")
                .build());

        assertThat(plan.getActions())
                .extracting(SyncAction::getType, SyncAction::getSafety)
                .containsExactly(tuple(
                        SyncActionType.REPAIR_ALBUM_ID,
                        SyncActionSafety.BLOCKED
                ));
    }

    @Test
    void planRejectsMissingDesiredState() {
        assertThatThrownBy(() -> planner.plan(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desiredState is required");
    }

    private static ImageHostingDesiredStateSnapshot desiredState(
            DesiredImageHostingAlbum album,
            List<DesiredImageHostingPhoto> photos
    ) {
        return ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .inventory(inventory())
                .album(album)
                .photos(photos)
                .build();
    }

    private static DesiredImageHostingAlbum album(String externalAlbumId) {
        ExternalImageAlbum externalAlbum = externalAlbumId == null
                ? null
                : ExternalImageAlbum.builder()
                        .externalImageAlbumId(301L)
                        .externalAlbumId(externalAlbumId)
                        .build();
        return DesiredImageHostingAlbum.builder()
                .desiredTitle("Desired album")
                .desiredDescription("Inventory item [inventory-uuid]")
                .externalAlbum(externalAlbum)
                .build();
    }

    private static DesiredImageHostingPhoto desiredPhoto(
            Integer itemInventoryPhotoId,
            boolean primary,
            String externalServiceImageId,
            String metadataHash,
            String metadataHashAtSync
    ) {
        ExternalImage externalImage = externalServiceImageId == null
                ? null
                : ExternalImage.builder()
                        .externalImageId(200L + itemInventoryPhotoId)
                        .externalServiceImageId(externalServiceImageId)
                        .metadataHashAtSync(metadataHashAtSync)
                        .build();
        ExternalImageAlbumImage membership = externalImage == null
                ? null
                : ExternalImageAlbumImage.builder()
                        .externalImageAlbumId(301L)
                        .externalImageId(externalImage.getExternalImageId())
                        .sortOrder(itemInventoryPhotoId)
                        .primary(primary)
                        .build();
        return DesiredImageHostingPhoto.builder()
                .inventoryPhoto(ItemInventoryPhoto.builder()
                        .itemInventoryPhotoId(itemInventoryPhotoId)
                        .itemInventoryId(100)
                        .fileName("photo-" + itemInventoryPhotoId + ".jpg")
                        .metadataHash(metadataHash)
                        .primary(primary)
                        .caption("Caption " + itemInventoryPhotoId)
                        .build())
                .externalImage(externalImage)
                .albumMembership(membership)
                .build();
    }

    private static ImageHostingRemoteSnapshot remoteSnapshot(HostedAlbum album, List<HostedPhoto> photos) {
        return ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId(album.getId())
                .album(album)
                .photos(photos)
                .build();
    }

    private static HostedAlbum hostedAlbum(String id, String title, String description, String primaryPhotoId) {
        return HostedAlbum.builder()
                .id(id)
                .title(title)
                .description(description)
                .primaryPhotoId(primaryPhotoId)
                .build();
    }

    private static HostedPhoto hostedPhoto(String id) {
        return HostedPhoto.builder()
                .id(id)
                .title("Hosted " + id)
                .description("Hosted description " + id)
                .build();
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        return inventory;
    }
}
