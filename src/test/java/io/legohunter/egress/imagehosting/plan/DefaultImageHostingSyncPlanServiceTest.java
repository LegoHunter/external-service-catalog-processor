package io.legohunter.egress.imagehosting.plan;

import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.egress.imagehosting.publishing.ImageHostingPublishingPolicy;
import io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairPlanRequest;
import io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairPlanService;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshot;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshotReader;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshotRequest;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateRequest;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_NOT_FOUND;
import static io.legohunter.egress.imagehosting.repair.ImageHostingDbRepairAttributes.REMOTE_ADOPTION_STATUS;

@ExtendWith(MockitoExtension.class)
class DefaultImageHostingSyncPlanServiceTest {
    @Mock
    private ImageHostingDesiredStateReader desiredStateReader;

    @Mock
    private ImageHostingRemoteSnapshotReader remoteSnapshotReader;

    @Mock
    private ImageHostingDbRepairPlanService dbRepairPlanService;

    private DefaultImageHostingSyncPlanService service;

    @BeforeEach
    void setUp() {
        service = new DefaultImageHostingSyncPlanService(
                desiredStateReader,
                remoteSnapshotReader,
                dbRepairPlanService,
                new ImageHostingReconciliationPlanner(new ImageHostingPublishingPolicy(new ImageHostingSyncProperties()))
        );
    }

    @Test
    void planReadsDesiredStateAndRemoteSnapshotThenBuildsPlan() {
        ImageHostingDesiredStateSnapshot desiredState = desiredState("album-100");
        when(desiredStateReader.read(any())).thenReturn(desiredState);
        when(remoteSnapshotReader.read(any())).thenReturn(ImageHostingRemoteSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .requestedAlbumId("album-100")
                .album(HostedAlbum.builder()
                        .id("album-100")
                        .title("Desired album")
                        .description("Inventory item [inventory-uuid]")
                        .build())
                .build());

        SyncPlan plan = service.plan(ImageHostingSyncPlanRequest.builder()
                .itemInventoryId(100)
                .provider("flickr")
                .externalServiceId(10)
                .userId("user-123")
                .albumPageSize(100)
                .photoPageSize(250)
                .build());

        assertThat(plan.hasActions()).isFalse();

        ArgumentCaptor<ImageHostingDesiredStateRequest> desiredRequestCaptor =
                ArgumentCaptor.forClass(ImageHostingDesiredStateRequest.class);
        verify(desiredStateReader).read(desiredRequestCaptor.capture());
        assertThat(desiredRequestCaptor.getValue())
                .extracting(
                        ImageHostingDesiredStateRequest::getItemInventoryId,
                        ImageHostingDesiredStateRequest::getProvider,
                        ImageHostingDesiredStateRequest::getExternalServiceId
                )
                .containsExactly(100, "flickr", 10);

        ArgumentCaptor<ImageHostingRemoteSnapshotRequest> remoteRequestCaptor =
                ArgumentCaptor.forClass(ImageHostingRemoteSnapshotRequest.class);
        verify(remoteSnapshotReader).read(remoteRequestCaptor.capture());
        assertThat(remoteRequestCaptor.getValue())
                .extracting(
                        ImageHostingRemoteSnapshotRequest::getAlbumId,
                        ImageHostingRemoteSnapshotRequest::getProvider,
                        ImageHostingRemoteSnapshotRequest::getExternalServiceId,
                        ImageHostingRemoteSnapshotRequest::getUserId,
                        ImageHostingRemoteSnapshotRequest::getAlbumPageSize,
                        ImageHostingRemoteSnapshotRequest::getPhotoPageSize
                )
                .containsExactly("album-100", "flickr", 10, "user-123", 100, 250);
    }

    @Test
    void planAdoptsExistingRemoteAlbumWhenDesiredAlbumHasNoRemoteId() {
        when(desiredStateReader.read(any())).thenReturn(desiredState(null));
        SyncPlan repairPlan = SyncPlan.builder()
                .planId("image-hosting-repair-flickr-100-uuid")
                .action(SyncAction.builder()
                        .actionId("001-repair-album-id")
                        .type(SyncActionType.REPAIR_ALBUM_ID)
                        .safety(SyncActionSafety.SAFE_AUTOMATIC)
                        .attribute("remoteAlbumId", "album-100")
                        .build())
                .build();
        when(dbRepairPlanService.plan(any())).thenReturn(repairPlan);

        SyncPlan plan = service.plan(ImageHostingSyncPlanRequest.builder()
                .itemInventoryId(100)
                .provider("flickr")
                .externalServiceId(10)
                .userId("user-123")
                .build());

        assertThat(plan).isSameAs(repairPlan);
        verify(remoteSnapshotReader, never()).read(any());

        ArgumentCaptor<ImageHostingDbRepairPlanRequest> repairRequestCaptor =
                ArgumentCaptor.forClass(ImageHostingDbRepairPlanRequest.class);
        verify(dbRepairPlanService).plan(repairRequestCaptor.capture());
        assertThat(repairRequestCaptor.getValue())
                .extracting(
                        ImageHostingDbRepairPlanRequest::getItemInventoryId,
                        ImageHostingDbRepairPlanRequest::getProvider,
                        ImageHostingDbRepairPlanRequest::getExternalServiceId,
                        ImageHostingDbRepairPlanRequest::getUserId
                )
                .containsExactly(100, "flickr", 10, "user-123");
    }

    @Test
    void planFallsBackToCreateAlbumWhenRemoteAdoptionFindsNoMatch() {
        when(desiredStateReader.read(any())).thenReturn(desiredState(null));
        when(dbRepairPlanService.plan(any())).thenReturn(SyncPlan.builder()
                .planId("image-hosting-repair-flickr-100-uuid")
                .action(SyncAction.builder()
                        .actionId("001-repair-album-id")
                        .type(SyncActionType.REPAIR_ALBUM_ID)
                        .safety(SyncActionSafety.BLOCKED)
                        .attribute(REMOTE_ADOPTION_STATUS, REMOTE_ADOPTION_NOT_FOUND)
                        .build())
                .build());

        SyncPlan plan = service.plan(ImageHostingSyncPlanRequest.builder()
                .itemInventoryId(100)
                .build());

        assertThat(plan.getActions())
                .extracting(action -> action.getType())
                .containsExactly(SyncActionType.CREATE_ALBUM);
        verify(remoteSnapshotReader, never()).read(any());
    }

    @Test
    void planRejectsMissingItemInventoryId() {
        assertThatThrownBy(() -> service.plan(ImageHostingSyncPlanRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itemInventoryId is required");
    }

    private static ImageHostingDesiredStateSnapshot desiredState(String externalAlbumId) {
        ExternalImageAlbum externalAlbum = externalAlbumId == null
                ? null
                : ExternalImageAlbum.builder()
                        .externalImageAlbumId(301L)
                        .externalAlbumId(externalAlbumId)
                        .build();
        return ImageHostingDesiredStateSnapshot.builder()
                .provider("flickr")
                .externalServiceId(10)
                .inventory(inventory())
                .album(DesiredImageHostingAlbum.builder()
                        .desiredTitle("Desired album")
                        .desiredDescription("Inventory item [inventory-uuid]")
                        .externalAlbum(externalAlbum)
                        .build())
                .build();
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        return inventory;
    }
}
