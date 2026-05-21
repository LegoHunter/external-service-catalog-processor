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
import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedAlbumMembershipRequest;
import io.legohunter.imaging.model.PhotoServiceRequest;
import io.legohunter.imaging.model.PhotoServiceResponse;
import io.legohunter.imaging.service.hosting.api.ImageHostingService;
import io.legohunter.ingress.s3.api.MinioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static io.legohunter.data.enums.ExternalSyncStatus.SYNCED;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.DRY_RUN;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.FAILED;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.PARTIAL_FAILURE;
import static io.legohunter.egress.imagehosting.ImageHostingSyncOutcome.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultImageHostingSyncServiceTest {
    private static final int FLICKR_SERVICE_ID = 10;

    @Mock
    private ImageHostingService imageHostingService;

    @Mock
    private MinioService minioService;

    @Mock
    private ItemInventoryDao itemInventoryDao;

    @Mock
    private ItemInventoryPhotoDao itemInventoryPhotoDao;

    @Mock
    private ExternalImageDao externalImageDao;

    @Mock
    private ExternalImageAlbumDao externalImageAlbumDao;

    @Mock
    private ExternalImageAlbumImageDao externalImageAlbumImageDao;

    @TempDir
    private Path tempDirectory;

    private DefaultImageHostingSyncService service;

    @BeforeEach
    void setUp() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.setExternalServiceId(FLICKR_SERVICE_ID);
        properties.setTempDirectory(tempDirectory);

        service = new DefaultImageHostingSyncService(
                Optional.of(imageHostingService),
                minioService,
                itemInventoryDao,
                itemInventoryPhotoDao,
                externalImageDao,
                externalImageAlbumDao,
                externalImageAlbumImageDao,
                properties
        );
    }

    @Test
    void syncItemInventory_uploadsPhotoCreatesAlbumAndPersistsMembership() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(minioService.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> {
            ExternalImage externalImage = invocation.getArgument(0);
            externalImage.setExternalImageId(201L);
            return externalImage;
        });
        when(imageHostingService.createAlbum(any())).thenReturn(response(HostedAlbum.builder()
                .id("flickr-album-100")
                .url("https://flickr.example/albums/100")
                .build()));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosDiscovered()).isEqualTo(1);
        assertThat(result.getPhotosUploaded()).isEqualTo(1);
        assertThat(result.isAlbumCreated()).isTrue();
        assertThat(result.isMembershipUpdated()).isTrue();

        ArgumentCaptor<ExternalImage> imageCaptor = ArgumentCaptor.forClass(ExternalImage.class);
        verify(externalImageDao).upsert(imageCaptor.capture());
        assertThat(imageCaptor.getValue())
                .extracting(
                        ExternalImage::getExternalServiceId,
                        ExternalImage::getItemInventoryPhotoId,
                        ExternalImage::getExternalServiceImageId,
                        ExternalImage::getMd5AtUpload,
                        ExternalImage::getSyncStatus
                )
                .containsExactly(FLICKR_SERVICE_ID, 11, "flickr-photo-11", "md5-11", SYNCED);

        ArgumentCaptor<PhotoServiceRequest<HostedAlbumMembershipRequest>> membershipCaptor =
                ArgumentCaptor.forClass(PhotoServiceRequest.class);
        verify(imageHostingService).updateAlbumMembership(membershipCaptor.capture());
        HostedAlbumMembershipRequest membershipRequest = membershipCaptor.getValue().get();
        assertThat(membershipRequest.getAlbumId()).isEqualTo("flickr-album-100");
        assertThat(membershipRequest.getPrimaryPhotoId()).isEqualTo("flickr-photo-11");
        assertThat(membershipRequest.getPhotoIds()).containsExactly("flickr-photo-11");

        ArgumentCaptor<ExternalImageAlbumImage> membershipRowCaptor =
                ArgumentCaptor.forClass(ExternalImageAlbumImage.class);
        verify(externalImageAlbumImageDao).upsert(membershipRowCaptor.capture());
        assertThat(membershipRowCaptor.getValue())
                .extracting(
                        ExternalImageAlbumImage::getExternalImageAlbumId,
                        ExternalImageAlbumImage::getExternalImageId,
                        ExternalImageAlbumImage::getSortOrder,
                        ExternalImageAlbumImage::getPrimary
                )
                .containsExactly(301L, 201L, 1, true);
    }

    @Test
    void syncItemInventory_reusesExistingExternalImageWithoutUploadingAgain() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImage existingImage = ExternalImage.builder()
                .externalImageId(201L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryPhotoId(11)
                .externalServiceImageId("flickr-photo-11")
                .syncStatus(SYNCED)
                .build();
        ExternalImageAlbum album = album("flickr-album-100");

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.of(existingImage));
        when(imageHostingService.updateAlbumMembership(any())).thenReturn(response(null));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosSkipped()).isEqualTo(1);
        assertThat(result.getPhotosUploaded()).isZero();
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isTrue();

        verify(imageHostingService, never()).uploadPhoto(any());
        verify(imageHostingService, never()).createAlbum(any());
        verify(externalImageDao, never()).upsert(any());
    }

    @Test
    void syncItemInventory_mapsUploadProviderErrorToFailedExternalImage() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(minioService.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(errorResponse(99, "provider rejected upload"));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(FAILED);
        assertThat(result.getPhotosFailed()).isEqualTo(1);
        assertThat(result.getFailureMessages()).containsExactly("Photo [11] failed: provider rejected upload");
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        ArgumentCaptor<ExternalImage> imageCaptor = ArgumentCaptor.forClass(ExternalImage.class);
        verify(externalImageDao).upsert(imageCaptor.capture());
        assertThat(imageCaptor.getValue())
                .extracting(ExternalImage::getSyncStatus, ExternalImage::getErrorMessage)
                .containsExactly(ExternalSyncStatus.FAILED, "provider rejected upload");

        verify(imageHostingService, never()).createAlbum(any());
        verify(imageHostingService, never()).updateAlbumMembership(any());
        verify(externalImageAlbumDao).update(album);
        assertThat(album.getSyncStatus()).isEqualTo(ExternalSyncStatus.FAILED);
    }

    @Test
    void syncItemInventory_reportsPartialFailureWhenAlbumCreationFailsAfterUpload() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);
        ExternalImageAlbum album = album(null);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));
        when(externalImageAlbumDao.findOrCreateForItem(any())).thenReturn(album);
        when(externalImageDao.findByExternalServiceIdAndItemInventoryPhotoId(FLICKR_SERVICE_ID, 11))
                .thenReturn(Optional.empty());
        when(minioService.getObject("photos", "100/front.jpg"))
                .thenReturn(new ByteArrayInputStream("image".getBytes()));
        when(imageHostingService.uploadPhoto(any())).thenReturn(response("flickr-photo-11"));
        when(externalImageDao.upsert(any())).thenAnswer(invocation -> {
            ExternalImage externalImage = invocation.getArgument(0);
            externalImage.setExternalImageId(201L);
            return externalImage;
        });
        when(imageHostingService.createAlbum(any())).thenReturn(errorResponse(98, "provider rejected album"));

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(PARTIAL_FAILURE);
        assertThat(result.getPhotosUploaded()).isEqualTo(1);
        assertThat(result.getFailureMessages()).containsExactly("provider rejected album");
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        verify(imageHostingService, never()).updateAlbumMembership(any());
    }

    @Test
    void syncItemInventory_noPhotosDoesNotTouchProviderOrExternalAlbumTables() {
        ItemInventory inventory = inventory();

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of());

        ImageHostingSyncResult result = service.syncItemInventory(100);

        assertThat(result.getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getPhotosDiscovered()).isZero();
        assertThat(result.getPhotosUploaded()).isZero();
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        verifyNoInteractions(imageHostingService, externalImageDao, externalImageAlbumDao, externalImageAlbumImageDao);
    }

    @Test
    void sync_dryRunDiscoversPhotosWithoutProviderCallsOrExternalImageWrites() {
        ItemInventory inventory = inventory();
        ItemInventoryPhoto photo = photo(11, true);

        when(itemInventoryDao.findByItemInventoryId(100)).thenReturn(Optional.of(inventory));
        when(itemInventoryPhotoDao.findByItemInventoryId(100)).thenReturn(Set.of(photo));

        ImageHostingSyncResult result = service.sync(ImageHostingSyncRequest.builder()
                .itemInventoryId(100)
                .dryRun(true)
                .build());

        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getOutcome()).isEqualTo(DRY_RUN);
        assertThat(result.getPhotosDiscovered()).isEqualTo(1);
        assertThat(result.getPhotosUploaded()).isZero();
        assertThat(result.isAlbumCreated()).isFalse();
        assertThat(result.isMembershipUpdated()).isFalse();

        verifyNoInteractions(imageHostingService, minioService, externalImageDao, externalImageAlbumDao, externalImageAlbumImageDao);
    }

    private static ItemInventory inventory() {
        ItemInventory inventory = new ItemInventory();
        inventory.setItemInventoryId(100);
        inventory.setUuid("inventory-uuid");
        inventory.setDescription("Inventory album");
        return inventory;
    }

    private static ItemInventoryPhoto photo(Integer itemInventoryPhotoId, boolean primary) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(100)
                .s3Bucket("photos")
                .s3Key("100/front.jpg")
                .md5("md5-" + itemInventoryPhotoId)
                .fileName("front.jpg")
                .primary(primary)
                .build();
    }

    private static ExternalImageAlbum album(String externalAlbumId) {
        return ExternalImageAlbum.builder()
                .externalImageAlbumId(301L)
                .externalServiceId(FLICKR_SERVICE_ID)
                .itemInventoryId(100)
                .externalAlbumId(externalAlbumId)
                .title("Inventory album")
                .syncStatus(ExternalSyncStatus.PENDING)
                .build();
    }

    private static <T> PhotoServiceResponse<T> response(T value) {
        return new TestPhotoServiceResponse<>(value, false, null, null);
    }

    private static <T> PhotoServiceResponse<T> errorResponse(Integer responseCode, String responseMessage) {
        return new TestPhotoServiceResponse<>(null, true, responseCode, responseMessage);
    }

    private record TestPhotoServiceResponse<T>(
            T value,
            boolean error,
            Integer responseCode,
            String responseMessage
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
