package io.legohunter.ingress.source.photo.service;

import io.legohunter.imaging.exception.PhotoProcessingException;
import io.legohunter.imaging.metadata.MetadataFingerprintService;
import io.legohunter.imaging.metadata.impl.MetadataExtractorService;
import io.legohunter.imaging.metadata.model.ConditionEnum;
import io.legohunter.imaging.metadata.model.ImageMetadata;
import io.legohunter.imaging.scaling.ImageScalingService;
import io.legohunter.ingress.s3.api.MinioService;
import io.legohunter.ingress.source.photo.metrics.PhotoMetricsService;
import io.legohunter.ingress.source.photo.model.PhotoUploadEvent;
import io.legohunter.data.dao.ExternalItemDao;
import io.legohunter.data.dao.ItemInventoryDao;
import io.legohunter.data.dao.ItemInventoryPhotoDao;
import io.legohunter.data.dto.ExternalItem;
import io.legohunter.data.dto.ItemInventory;
import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.data.enums.PhotoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

import static io.legohunter.data.dto.ExternalService.ExternalServiceType.BRICKLINK;
import static io.legohunter.data.enums.PhotoStatus.PROCESSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoProcessingServiceTest {

    @Mock
    private MinioService minioService;

    @Mock
    private ImageScalingService imageScalingService;

    @Mock
    private MetadataExtractorService metadataExtractorService;

    @Mock
    private MetadataFingerprintService metadataFingerprintService;

    @Mock
    private PhotoMetricsService photoMetricsService;

    @Mock
    private ItemInventoryDao itemInventoryDao;

    @Mock
    private ItemInventoryPhotoDao itemInventoryPhotoDao;

    @Mock
    private ExternalItemDao externalItemDao;

    @InjectMocks
    private PhotoProcessingService service;

    private byte[] originalBytes;
    private byte[] scaledBytes;

    @BeforeEach
    void setUp() {
        originalBytes = "original-image".getBytes();
        scaledBytes = "scaled-image".getBytes();
        lenient().when(metadataFingerprintService.calculateHash(any())).thenReturn("metadata-hash");
    }

    @Test
    void process_event_shouldProcessPhotoSuccessfully() {
        PhotoUploadEvent event = new PhotoUploadEvent("lego-uploads-sandbox", "photos/test.jpg");
        ImageMetadata metadata = metadata(
                "uuid-1",
                "3001",
                true,
                true,
                false,
                "Front view"
        );
        ExternalItem externalItem = externalItem(123);

        when(minioService.getObject("lego-uploads-sandbox", "photos/test.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata);
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("md5-1");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByMd5("md5-1"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3001"))
                .thenReturn(Optional.of(externalItem));
        setGeneratedItemInventoryId(999);

        service.process(event);

        ArgumentCaptor<ItemInventory> inventoryCaptor = ArgumentCaptor.forClass(ItemInventory.class);
        verify(itemInventoryDao).upsert(inventoryCaptor.capture());

        assertThat(inventoryCaptor.getValue())
                .extracting(
                        ItemInventory::getUuid,
                        ItemInventory::getDescription,
                        ItemInventory::getSealed,
                        ItemInventory::getBuiltOnce,
                        ItemInventory::getActive,
                        ItemInventory::getBoxConditionId,
                        ItemInventory::getInstructionsConditionId,
                        ItemInventory::getItemConditionId
                )
                .containsExactly("uuid-1", "Front view", true, false, true, 1, 2, 4);

        verify(minioService).putObject(
                eq("lego-photos-sandbox"),
                eq("3001/uuid-1/md5-1.jpg"),
                any(InputStream.class),
                eq((long) scaledBytes.length),
                eq("image/jpeg")
        );
        verify(itemInventoryPhotoDao).insertPhoto(
                999,
                "md5-1",
                "metadata-hash",
                "test.jpg",
                "lego-photos-sandbox",
                "3001/uuid-1/md5-1.jpg",
                scaledBytes.length,
                true,
                "Front view",
                PROCESSED
        );
        verify(itemInventoryPhotoDao).setPrimaryPhoto(999, "md5-1");
        verify(minioService).deleteObject("lego-uploads-sandbox", "photos/test.jpg");
        verify(photoMetricsService).incrementProcessed("event");
    }

    @Test
    void process_batch_shouldNotDeleteSourceObject() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "C:\\lightroom\\exports\\photo.jpg",
                "image/jpeg",
                originalBytes
        );

        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-2", "3002", null, null, null, null));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("md5-2");
        when(itemInventoryDao.findByUuid("uuid-2"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByMd5("md5-2"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3002"))
                .thenReturn(Optional.of(externalItem(100)));
        setGeneratedItemInventoryId(200);

        service.process(file);

        verify(minioService, never()).deleteObject(anyString(), anyString());
        verify(itemInventoryPhotoDao).insertPhoto(
                200,
                "md5-2",
                "metadata-hash",
                "photo.jpg",
                "lego-photos-sandbox",
                "3002/uuid-2/md5-2.jpg",
                scaledBytes.length,
                false,
                null,
                PROCESSED
        );
        verify(photoMetricsService).incrementProcessed("batch");
    }

    @Test
    void process_replacesExistingPhotoWhenNormalizedFilenameMatchesAndMd5Changes() {
        PhotoUploadEvent event = new PhotoUploadEvent("lego-uploads-sandbox", "photos/nested/test.jpg");
        ItemInventory existingInventory = itemInventory(999, "uuid-1");
        ItemInventoryPhoto existingPhoto = photo(
                10,
                999,
                "old-md5",
                "test.jpg",
                "lego-photos-sandbox",
                "3001/uuid-1/old-md5.jpg"
        );

        when(minioService.getObject("lego-uploads-sandbox", "photos/nested/test.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-1", "3001", true, false, true, "Updated caption"));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("new-md5");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.of(existingInventory));
        when(itemInventoryPhotoDao.findByItemInventoryIdAndFileName(999, "test.jpg"))
                .thenReturn(Optional.of(existingPhoto));
        when(itemInventoryPhotoDao.findByMd5("new-md5"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3001"))
                .thenReturn(Optional.of(externalItem(123)));
        when(itemInventoryPhotoDao.replaceStoredObject(
                10,
                "test.jpg",
                "new-md5",
                "metadata-hash",
                "lego-photos-sandbox",
                "3001/uuid-1/new-md5.jpg",
                scaledBytes.length,
                true,
                "Updated caption",
                PROCESSED
        )).thenReturn(1);

        service.process(event);

        ArgumentCaptor<ItemInventory> inventoryCaptor = ArgumentCaptor.forClass(ItemInventory.class);
        verify(itemInventoryDao).upsert(inventoryCaptor.capture());

        assertThat(inventoryCaptor.getValue())
                .extracting(
                        ItemInventory::getItemInventoryId,
                        ItemInventory::getUuid,
                        ItemInventory::getDescription,
                        ItemInventory::getSealed,
                        ItemInventory::getBuiltOnce
                )
                .containsExactly(999, "uuid-1", "Updated caption", false, true);

        verify(minioService).putObject(
                eq("lego-photos-sandbox"),
                eq("3001/uuid-1/new-md5.jpg"),
                any(InputStream.class),
                eq((long) scaledBytes.length),
                eq("image/jpeg")
        );
        verify(itemInventoryPhotoDao).replaceStoredObject(
                10,
                "test.jpg",
                "new-md5",
                "metadata-hash",
                "lego-photos-sandbox",
                "3001/uuid-1/new-md5.jpg",
                scaledBytes.length,
                true,
                "Updated caption",
                PROCESSED
        );
        verify(itemInventoryPhotoDao).setPrimaryPhoto(999, "new-md5");
        verify(minioService).deleteObject("lego-photos-sandbox", "3001/uuid-1/old-md5.jpg");
        verify(minioService).deleteObject("lego-uploads-sandbox", "photos/nested/test.jpg");
        verify(itemInventoryPhotoDao, never()).insertPhoto(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyLong(),
                anyBoolean(),
                any(),
                any(PhotoStatus.class)
        );
    }

    @Test
    void process_updatesExistingPhotoMetadataWithoutUploadingWhenMd5IsUnchanged() {
        PhotoUploadEvent event = new PhotoUploadEvent("lego-uploads-sandbox", "photos/test.jpg");
        ItemInventory existingInventory = itemInventory(999, "uuid-1");
        ItemInventoryPhoto existingPhoto = photo(
                10,
                999,
                "same-md5",
                "test.jpg",
                "lego-photos-sandbox",
                "3001/uuid-1/same-md5.jpg"
        );

        when(minioService.getObject("lego-uploads-sandbox", "photos/test.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-1", "3001", false, true, true, "Metadata-only caption"));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("same-md5");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.of(existingInventory));
        when(itemInventoryPhotoDao.findByItemInventoryIdAndFileName(999, "test.jpg"))
                .thenReturn(Optional.of(existingPhoto));
        when(itemInventoryPhotoDao.findByMd5("same-md5"))
                .thenReturn(Optional.of(existingPhoto));
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3001"))
                .thenReturn(Optional.of(externalItem(123)));
        when(itemInventoryPhotoDao.updateMetadata(10, "test.jpg", "metadata-hash", false, "Metadata-only caption", PROCESSED))
                .thenReturn(1);

        service.process(event);

        verify(minioService, never()).putObject(any(), any(), any(), anyLong(), any());
        verify(itemInventoryPhotoDao).updateMetadata(10, "test.jpg", "metadata-hash", false, "Metadata-only caption", PROCESSED);
        verify(itemInventoryPhotoDao, never()).replaceStoredObject(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyLong(),
                anyBoolean(),
                any(),
                any(PhotoStatus.class)
        );
        verify(itemInventoryPhotoDao, never()).setPrimaryPhoto(any(), any());
        verify(minioService, never()).deleteObject("lego-photos-sandbox", "3001/uuid-1/same-md5.jpg");
        verify(minioService).deleteObject("lego-uploads-sandbox", "photos/test.jpg");
    }

    @Test
    void process_preservesExistingInventoryValuesWhenMetadataTagsAreMissing() {
        PhotoUploadEvent event = new PhotoUploadEvent("lego-uploads-sandbox", "photos/test.jpg");
        ItemInventory existingInventory = itemInventory(999, "uuid-1");
        existingInventory.setDescription("Existing description");
        existingInventory.setSealed(true);
        existingInventory.setBuiltOnce(false);
        existingInventory.setBoxConditionId(8);
        existingInventory.setInstructionsConditionId(9);
        existingInventory.setItemConditionId(10);

        ItemInventoryPhoto existingPhoto = photo(
                10,
                999,
                "same-md5",
                "test.jpg",
                "lego-photos-sandbox",
                "3001/uuid-1/same-md5.jpg"
        );

        when(minioService.getObject("lego-uploads-sandbox", "photos/test.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata(
                        "uuid-1",
                        "3001",
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("new-md5");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.of(existingInventory));
        when(itemInventoryPhotoDao.findByItemInventoryIdAndFileName(999, "test.jpg"))
                .thenReturn(Optional.of(existingPhoto));
        when(itemInventoryPhotoDao.findByMd5("new-md5"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3001"))
                .thenReturn(Optional.of(externalItem(123)));
        when(itemInventoryPhotoDao.replaceStoredObject(
                10,
                "test.jpg",
                "new-md5",
                "metadata-hash",
                "lego-photos-sandbox",
                "3001/uuid-1/new-md5.jpg",
                scaledBytes.length,
                false,
                null,
                PROCESSED
        ))
                .thenReturn(1);

        service.process(event);

        ArgumentCaptor<ItemInventory> inventoryCaptor = ArgumentCaptor.forClass(ItemInventory.class);
        verify(itemInventoryDao).upsert(inventoryCaptor.capture());

        assertThat(inventoryCaptor.getValue())
                .extracting(
                        ItemInventory::getItemInventoryId,
                        ItemInventory::getDescription,
                        ItemInventory::getSealed,
                        ItemInventory::getBuiltOnce,
                        ItemInventory::getBoxConditionId,
                        ItemInventory::getInstructionsConditionId,
                        ItemInventory::getItemConditionId
                )
                .containsExactly(
                        999,
                        "Existing description",
                        true,
                        false,
                        8,
                        9,
                        10
                );
    }

    @Test
    void process_movesSameLogicalPhotoToDuplicateWhenContentAndMetadataAreUnchanged() {
        PhotoUploadEvent event = new PhotoUploadEvent("lego-uploads-sandbox", "photos/DSC_9908.jpg");
        ItemInventory existingInventory = itemInventory(558, "e57aad1b8724d89bab9c0d5fccbb3a99");
        existingInventory.setDescription("Stickers applied except for astronaut torso and 4 NASA stickers.");
        existingInventory.setSealed(false);
        existingInventory.setBuiltOnce(true);
        existingInventory.setBoxConditionId(2);
        existingInventory.setInstructionsConditionId(2);

        ItemInventoryPhoto existingPhoto = photo(
                303,
                558,
                "7f7580228caebdd153e1d3fea8089833",
                "DSC_9908.jpg",
                "lego-photos-sandbox",
                "1682-1/e57aad1b8724d89bab9c0d5fccbb3a99/7f7580228caebdd153e1d3fea8089833.jpg"
        );
        existingPhoto.setCaption("Stickers applied except for astronaut torso and 4 NASA stickers.");
        existingPhoto.setPrimary(false);

        when(minioService.getObject("lego-uploads-sandbox", "photos/DSC_9908.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata(
                        "e57aad1b8724d89bab9c0d5fccbb3a99",
                        "1682-1",
                        false,
                        false,
                        true,
                        "Stickers applied except for astronaut torso and 4 NASA stickers.",
                        ConditionEnum.E,
                        ConditionEnum.E,
                        null
                ));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("7f7580228caebdd153e1d3fea8089833");
        when(itemInventoryDao.findByUuid("e57aad1b8724d89bab9c0d5fccbb3a99"))
                .thenReturn(Optional.of(existingInventory));
        when(itemInventoryPhotoDao.findByItemInventoryIdAndFileName(558, "DSC_9908.jpg"))
                .thenReturn(Optional.of(existingPhoto));
        when(itemInventoryPhotoDao.findByMd5("7f7580228caebdd153e1d3fea8089833"))
                .thenReturn(Optional.of(existingPhoto));
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "1682-1"))
                .thenReturn(Optional.of(externalItem(123)));

        service.process(event);

        verify(photoMetricsService).incrementDuplicate("event");
        verify(minioService).copyObject(
                "lego-uploads-sandbox",
                "photos/DSC_9908.jpg",
                "lego-uploads-sandbox",
                "photos/duplicate/DSC_9908.jpg"
        );
        verify(minioService).deleteObject("lego-uploads-sandbox", "photos/DSC_9908.jpg");
        verify(itemInventoryDao, never()).upsert(any());
        verify(itemInventoryPhotoDao, never()).updateMetadata(
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any(PhotoStatus.class)
        );
        verify(itemInventoryPhotoDao, never()).replaceStoredObject(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyLong(),
                anyBoolean(),
                any(),
                any(PhotoStatus.class)
        );
        verify(minioService, never()).putObject(any(), any(), any(), anyLong(), any());
    }

    @Test
    void process_shouldSkipDuplicatePhoto() {
        PhotoUploadEvent event = new PhotoUploadEvent("bucket", "photos/photo.jpg");

        when(minioService.getObject("bucket", "photos/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-1", "3001", null, null, null, null));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("duplicate-md5");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByMd5("duplicate-md5"))
                .thenReturn(Optional.of(ItemInventoryPhoto.builder().build()));

        service.process(event);

        verify(photoMetricsService).incrementDuplicate("event");
        verify(minioService).copyObject(
                "bucket",
                "photos/photo.jpg",
                "bucket",
                "photos/duplicate/photo.jpg"
        );
        verify(minioService).deleteObject("bucket", "photos/photo.jpg");
        verify(itemInventoryDao, never()).upsert(any());
        verify(externalItemDao, never()).findByExternalServiceAndNumber(any(), any());
        verify(minioService, never()).putObject(any(), any(), any(), anyLong(), any());
    }

    @Test
    void process_shouldMoveUnprocessableSourceToRejectedWhenMetadataCannotBeExtracted() {
        PhotoUploadEvent event = new PhotoUploadEvent("bucket", "photos/metadata.json");

        when(minioService.getObject("bucket", "photos/metadata.json"))
                .thenReturn(new ByteArrayInputStream("{}".getBytes()));
        when(metadataExtractorService.extractMetadata(any(byte[].class)))
                .thenThrow(new RuntimeException("not an image"));

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class);

        verify(minioService).copyObject(
                "bucket",
                "photos/metadata.json",
                "bucket",
                "photos/rejected/metadata.json"
        );
        verify(minioService).deleteObject("bucket", "photos/metadata.json");
        verify(photoMetricsService).incrementFailed("event");
    }

    @Test
    void process_shouldFailWhenRequiredMetadataIsMissing() {
        PhotoUploadEvent event = new PhotoUploadEvent("bucket", "photos/photo.jpg");

        when(minioService.getObject("bucket", "photos/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata(null, "3001", null, null, null, null));

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class)
                .hasMessageContaining("Photo processing failed");

        verify(minioService).copyObject(
                "bucket",
                "photos/photo.jpg",
                "bucket",
                "photos/rejected/photo.jpg"
        );
        verify(minioService).deleteObject("bucket", "photos/photo.jpg");
        verify(imageScalingService, never()).scale(any());
        verify(photoMetricsService).incrementFailed("event");
    }

    @Test
    void process_shouldFailWhenExternalItemIsNotFound() {
        PhotoUploadEvent event = new PhotoUploadEvent("bucket", "photos/photo.jpg");

        when(minioService.getObject("bucket", "photos/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-1", "9999", null, null, null, null));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("md5");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByMd5("md5"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "9999"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class);

        verify(minioService).copyObject(
                "bucket",
                "photos/photo.jpg",
                "bucket",
                "photos/rejected/photo.jpg"
        );
        verify(minioService).deleteObject("bucket", "photos/photo.jpg");
        verify(minioService, never()).putObject(any(), any(), any(), anyLong(), any());
        verify(photoMetricsService).incrementFailed("event");
    }

    @Test
    void process_shouldFailBeforeDatabaseWriteWhenUploadFails() {
        PhotoUploadEvent event = new PhotoUploadEvent("bucket", "photos/photo.jpg");

        when(minioService.getObject("bucket", "photos/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-1", "3001", null, null, null, null));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("md5-fail");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByMd5("md5-fail"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3001"))
                .thenReturn(Optional.of(externalItem(123)));
        doThrow(new RuntimeException("upload failed"))
                .when(minioService)
                .putObject(any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class);

        verify(itemInventoryDao, never()).upsert(any());
        verify(minioService, never()).copyObject(anyString(), anyString(), anyString(), anyString());
        verify(itemInventoryPhotoDao, never()).insertPhoto(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyLong(),
                anyBoolean(),
                any(),
                any(PhotoStatus.class)
        );
        verify(photoMetricsService).incrementFailed("event");
    }

    @Test
    void process_shouldRollbackUploadedObjectWhenPhotoInsertFails() {
        PhotoUploadEvent event = new PhotoUploadEvent("bucket", "photos/photo.jpg");

        when(minioService.getObject("bucket", "photos/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-1", "3001", null, null, null, null));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("md5-rollback");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByMd5("md5-rollback"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3001"))
                .thenReturn(Optional.of(externalItem(123)));
        setGeneratedItemInventoryId(500);
        doThrow(new RuntimeException("db insert failed"))
                .when(itemInventoryPhotoDao)
                .insertPhoto(
                500,
                "md5-rollback",
                "metadata-hash",
                "photo.jpg",
                        "lego-photos-sandbox",
                        "3001/uuid-1/md5-rollback.jpg",
                        scaledBytes.length,
                        false,
                        null,
                        PROCESSED
                );

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class);

        verify(minioService).deleteObject("lego-photos-sandbox", "3001/uuid-1/md5-rollback.jpg");
        verify(minioService, never()).copyObject(anyString(), anyString(), anyString(), anyString());
        verify(minioService, never()).deleteObject("bucket", "photos/photo.jpg");
        verify(photoMetricsService).incrementFailed("event");
    }

    @Test
    void process_shouldNotSetPrimaryWhenPrimaryMetadataIsFalseOrAbsent() {
        PhotoUploadEvent event = new PhotoUploadEvent("bucket", "photos/photo.jpg");

        when(minioService.getObject("bucket", "photos/photo.jpg"))
                .thenReturn(new ByteArrayInputStream(originalBytes));
        when(metadataExtractorService.extractMetadata(originalBytes))
                .thenReturn(metadata("uuid-1", "3001", false, null, null, null));
        when(imageScalingService.scale(originalBytes))
                .thenReturn(scaledBytes);
        when(metadataExtractorService.calculateMd5(scaledBytes))
                .thenReturn("md5");
        when(itemInventoryDao.findByUuid("uuid-1"))
                .thenReturn(Optional.empty());
        when(itemInventoryPhotoDao.findByMd5("md5"))
                .thenReturn(Optional.empty());
        when(externalItemDao.findByExternalServiceAndNumber(BRICKLINK.getExternalServiceId(), "3001"))
                .thenReturn(Optional.of(externalItem(123)));
        setGeneratedItemInventoryId(600);

        service.process(event);

        verify(itemInventoryPhotoDao, never()).setPrimaryPhoto(any(), any());
    }

    private void setGeneratedItemInventoryId(Integer itemInventoryId) {
        doAnswer(invocation -> {
            ItemInventory itemInventory = invocation.getArgument(0);
            itemInventory.setItemInventoryId(itemInventoryId);
            return null;
        }).when(itemInventoryDao).upsert(any(ItemInventory.class));
    }

    private ExternalItem externalItem(Integer externalItemId) {
        ExternalItem externalItem = new ExternalItem();
        externalItem.setExternalItemId(externalItemId);
        return externalItem;
    }

    private ItemInventory itemInventory(Integer itemInventoryId, String uuid) {
        ItemInventory itemInventory = new ItemInventory();
        itemInventory.setItemInventoryId(itemInventoryId);
        itemInventory.setUuid(uuid);
        return itemInventory;
    }

    private ItemInventoryPhoto photo(
            Integer itemInventoryPhotoId,
            Integer itemInventoryId,
            String md5,
            String fileName,
            String s3Bucket,
            String s3Key
    ) {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(itemInventoryPhotoId)
                .itemInventoryId(itemInventoryId)
                .md5(md5)
                .metadataHash("metadata-hash")
                .fileName(fileName)
                .s3Bucket(s3Bucket)
                .s3Key(s3Key)
                .fileSize(1000L)
                .primary(false)
                .status(PROCESSED)
                .build();
    }

    private ImageMetadata metadata(
            String uuid,
            String externalItemNumber,
            Boolean primary,
            Boolean sealed,
            Boolean builtOnce,
            String caption
    ) {
        return metadata(
                uuid,
                externalItemNumber,
                primary,
                sealed,
                builtOnce,
                caption,
                ConditionEnum.M,
                ConditionEnum.E,
                ConditionEnum.G
        );
    }

    private ImageMetadata metadata(
            String uuid,
            String externalItemNumber,
            Boolean primary,
            Boolean sealed,
            Boolean builtOnce,
            String caption,
            ConditionEnum boxCondition,
            ConditionEnum instructionsCondition,
            ConditionEnum itemCondition
    ) {
        return new ImageMetadata(
                uuid,
                externalItemNumber,
                primary,
                sealed,
                builtOnce,
                boxCondition,
                instructionsCondition,
                itemCondition,
                caption
        );
    }
}
