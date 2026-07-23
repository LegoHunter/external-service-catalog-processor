package io.legohunter.egress.imagehosting.repair;

import io.legohunter.data.dao.ExternalImageAlbumDao;
import io.legohunter.data.dao.ExternalImageAlbumImageDao;
import io.legohunter.data.dao.ExternalImageDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbum;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.ExternalSyncStatus;
import io.legohunter.imaging.service.sync.model.SyncAction;
import io.legohunter.imaging.service.sync.model.SyncActionSafety;
import io.legohunter.imaging.service.sync.model.SyncActionStatus;
import io.legohunter.imaging.service.sync.model.SyncActionType;
import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultImageHostingDbRepairPlanExecutorTest {
    private static final int FLICKR_SERVICE_ID = 10;

    private ItemInventoryPhotoDao itemInventoryPhotoDao;
    private ExternalImageDao externalImageDao;
    private ExternalImageAlbumDao externalImageAlbumDao;
    private ExternalImageAlbumImageDao externalImageAlbumImageDao;
    private DefaultImageHostingDbRepairPlanExecutor executor;

    @BeforeEach
    void setUp() {
        itemInventoryPhotoDao = mock(ItemInventoryPhotoDao.class);
        externalImageDao = mock(ExternalImageDao.class);
        externalImageAlbumDao = mock(ExternalImageAlbumDao.class);
        externalImageAlbumImageDao = mock(ExternalImageAlbumImageDao.class);
        executor = new DefaultImageHostingDbRepairPlanExecutor(
                itemInventoryPhotoDao,
                externalImageDao,
                externalImageAlbumDao,
                externalImageAlbumImageDao
        );
    }

    @Test
    void executeRepairsAlbumPhotoIdsAndMembershipRows() {
        ExternalImageAlbum album = ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryId(100)
                .syncStatus(ExternalSyncStatus.PENDING)
                .build();
        ExternalImage frontImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .build();
        ExternalImage detailImage = ExternalImage.builder()
                .externalImageId(202L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(12)
                .build();

        when(externalImageAlbumDao.findByExternalServiceIdAndExternalAlbumId(FLICKR_SERVICE_ID, "album-100"))
                .thenReturn(Optional.empty(), Optional.of(album));
        when(externalImageAlbumDao.findByExternalImageAlbumId(301L)).thenReturn(Optional.of(album));
        when(itemInventoryPhotoDao.findByItemInventoryPhotoId(11)).thenReturn(Optional.of(photo(11, "md5-11", "hash-11")));
        when(itemInventoryPhotoDao.findByItemInventoryPhotoId(12)).thenReturn(Optional.of(photo(12, "md5-12", "hash-12")));
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR_SERVICE_ID, "photo-11"))
                .thenReturn(Optional.empty(), Optional.of(frontImage));
        when(externalImageDao.findByExternalServiceIdAndExternalServiceImageId(FLICKR_SERVICE_ID, "photo-12"))
                .thenReturn(Optional.empty(), Optional.of(detailImage));
        when(externalImageDao.findByExternalImageId(201L)).thenReturn(Optional.of(frontImage));
        when(externalImageDao.findByExternalImageId(202L)).thenReturn(Optional.of(detailImage));

        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("repair-plan-1")
                .action(repairAlbumAction())
                .action(repairPhotoAction("002-repair-photo-id", 11, 201L, "photo-11"))
                .action(repairPhotoAction("003-repair-photo-id", 12, 202L, "photo-12"))
                .action(repairMembershipAction())
                .build(), false);

        assertThat(report.getResults())
                .extracting(result -> result.getStatus())
                .containsExactly(
                        SyncActionStatus.SUCCEEDED,
                        SyncActionStatus.SUCCEEDED,
                        SyncActionStatus.SUCCEEDED,
                        SyncActionStatus.SUCCEEDED
                );
        assertThat(album.getExternalAlbumId()).isEqualTo("album-100");
        assertThat(album.getAlbumUrl()).isEqualTo("https://flickr.example/albums/album-100");
        assertThat(frontImage.getExternalServiceImageId()).isEqualTo("photo-11");
        assertThat(frontImage.getMetadataHashAtSync()).isEqualTo("hash-11");
        verify(externalImageAlbumDao).upsert(album);
        verify(externalImageDao).upsert(frontImage);
        verify(externalImageDao).upsert(detailImage);
        verify(externalImageAlbumImageDao).deleteByExternalImageAlbumId(301L);
        verify(externalImageAlbumImageDao).upsert(argThat(membership ->
                membership.getExternalImageAlbumId().equals(301L)
                        && membership.getExternalImageId().equals(201L)
                        && membership.getSortOrder().equals(1)
                        && Boolean.TRUE.equals(membership.getPrimary())
        ));
        verify(externalImageAlbumImageDao).upsert(argThat(membership ->
                membership.getExternalImageAlbumId().equals(301L)
                        && membership.getExternalImageId().equals(202L)
                        && membership.getSortOrder().equals(2)
                        && (membership.getPrimary() == null || Boolean.FALSE.equals(membership.getPrimary()))
        ));
    }

    @Test
    void executeBlocksReviewRequiredActionUnlessAllowed() {
        SyncReport report = executor.execute(SyncPlan.builder()
                .planId("repair-plan-1")
                .action(SyncAction.builder()
                        .actionId("001-repair-album-id")
                        .type(SyncActionType.REPAIR_ALBUM_ID)
                        .safety(SyncActionSafety.REQUIRES_REVIEW)
                        .attribute("externalServiceId", "10")
                        .attribute("itemInventoryId", "100")
                        .attribute("remoteAlbumId", "album-100")
                        .build())
                .build(), false);

        assertThat(report.getResults().getFirst())
                .extracting(result -> result.getStatus(), result -> result.getMessage())
                .containsExactly(SyncActionStatus.BLOCKED, "Action requires review before apply");
    }

    private static SyncAction repairAlbumAction() {
        return SyncAction.builder()
                .actionId("001-repair-album-id")
                .type(SyncActionType.REPAIR_ALBUM_ID)
                .safety(SyncActionSafety.SAFE_AUTOMATIC)
                .albumId("album-100")
                .attribute("externalServiceId", "10")
                .attribute("itemInventoryId", "100")
                .attribute("externalImageAlbumId", "301")
                .attribute("remoteAlbumId", "album-100")
                .attribute("remoteAlbumUrl", "https://flickr.example/albums/album-100")
                .attribute("remoteTitle", "4558-1 - Metroliner")
                .build();
    }

    private static SyncAction repairPhotoAction(
            String actionId,
            Integer itemInventoryPhotoId,
            Long externalImageId,
            String remotePhotoId
    ) {
        return SyncAction.builder()
                .actionId(actionId)
                .type(SyncActionType.REPAIR_PHOTO_ID)
                .safety(SyncActionSafety.SAFE_AUTOMATIC)
                .attribute("externalServiceId", "10")
                .attribute("itemInventoryPhotoId", itemInventoryPhotoId.toString())
                .attribute("externalImageId", externalImageId.toString())
                .attribute("remotePhotoId", remotePhotoId)
                .attribute("remotePhotoUrl", "https://flickr.example/photos/" + remotePhotoId)
                .attribute("remoteTitle", "Caption")
                .build();
    }

    private static SyncAction repairMembershipAction() {
        return SyncAction.builder()
                .actionId("004-update-album-membership")
                .type(SyncActionType.UPDATE_ALBUM_MEMBERSHIP)
                .safety(SyncActionSafety.SAFE_AUTOMATIC)
                .albumId("album-100")
                .attribute("externalServiceId", "10")
                .attribute("itemInventoryId", "100")
                .attribute("externalImageAlbumId", "301")
                .attribute("externalAlbumId", "album-100")
                .attribute("remotePhotoIds", "photo-11,photo-12")
                .attribute("remotePrimaryPhotoId", "photo-11")
                .build();
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, String md5, String metadataHash) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .md5(md5)
                .metadataHash(metadataHash)
                .build();
    }
}
