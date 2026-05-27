package io.legohunter.egress.imagehosting.plan;

import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshot;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshotReader;
import io.legohunter.egress.imagehosting.remote.ImageHostingRemoteSnapshotRequest;
import io.legohunter.egress.imagehosting.snapshot.DesiredImageHostingAlbum;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateReader;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateRequest;
import io.legohunter.egress.imagehosting.snapshot.ImageHostingDesiredStateSnapshot;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultImageHostingSyncPlanService implements ImageHostingSyncPlanService {
    private final ImageHostingDesiredStateReader desiredStateReader;
    private final ImageHostingRemoteSnapshotReader remoteSnapshotReader;
    private final ImageHostingReconciliationPlanner reconciliationPlanner;

    @Override
    public SyncPlan plan(ImageHostingSyncPlanRequest request) {
        if (request == null || request.getItemInventoryId() == null) {
            throw new IllegalArgumentException("itemInventoryId is required");
        }

        ImageHostingDesiredStateSnapshot desiredState = desiredStateReader.read(ImageHostingDesiredStateRequest.builder()
                .itemInventoryId(request.getItemInventoryId())
                .provider(request.getProvider())
                .externalServiceId(request.getExternalServiceId())
                .build());

        ImageHostingRemoteSnapshot remoteSnapshot = readRemoteSnapshotIfPossible(desiredState, request);
        return reconciliationPlanner.plan(desiredState, remoteSnapshot);
    }

    private ImageHostingRemoteSnapshot readRemoteSnapshotIfPossible(
            ImageHostingDesiredStateSnapshot desiredState,
            ImageHostingSyncPlanRequest request
    ) {
        String albumId = desiredState.albumOptional()
                .map(DesiredImageHostingAlbum::getExternalAlbumId)
                .filter(this::hasText)
                .orElse(null);
        if (!hasText(albumId)) {
            return ImageHostingRemoteSnapshot.builder()
                    .provider(desiredState.getProvider())
                    .externalServiceId(desiredState.getExternalServiceId())
                    .userId(request.getUserId())
                    .build();
        }

        return remoteSnapshotReader.read(ImageHostingRemoteSnapshotRequest.builder()
                .provider(desiredState.getProvider())
                .externalServiceId(desiredState.getExternalServiceId())
                .userId(request.getUserId())
                .albumId(albumId)
                .albumPageSize(request.getAlbumPageSize())
                .photoPageSize(request.getPhotoPageSize())
                .build());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
