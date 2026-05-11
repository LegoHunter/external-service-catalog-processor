package io.legohunter.ingress.source.photo.service;

import io.legohunter.ingress.source.photo.exception.PhotoProcessingException;
import io.legohunter.ingress.source.photo.metrics.PhotoMetricsService;
import io.legohunter.ingress.source.photo.model.PhotoUploadEvent;
import net.lego.data.v2.dao.ExternalItemDao;
import net.lego.data.v2.dao.ItemInventoryDao;
import net.lego.data.v2.dao.ItemInventoryPhotoDao;
import net.lego.data.v2.dto.ExternalItem;
import net.lego.data.v2.dto.ItemInventory;
import net.lego.data.v2.enums.PhotoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static net.lego.data.v2.dto.ExternalService.ExternalServiceType.BRICKLINK;
import static net.lego.data.v2.enums.PhotoStatus.PROCESSED;
import static net.lego.data.v2.enums.PhotoStatus.UPLOADED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class PhotoProcessingServiceTest {

    @Mock
    private MinioService minioService;

    @Mock
    private ImageScalingService imageScalingService;

    @Mock
    private MetadataExtractorService metadataExtractorService;

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
    }

    @Test
    void process_event_successfully() {

        PhotoUploadEvent event = new PhotoUploadEvent();
        event.setBucket("lego-uploads-sandbox");
        event.setObjectKey("photos/test.jpg");

        ExternalItem externalItem = new ExternalItem();
        externalItem.setExternalItemId(123);

        when(minioService.getObject(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(originalBytes));

        when(metadataExtractorService.extractKeywords(any()))
                .thenReturn(Map.of(
                        "uuid", "uuid-1",
                        "bl", "3001",
                        "primary", "true",
                        "sealed", "true",
                        "bo", "false",
                        "description", "Test Description"
                ));

        when(imageScalingService.scale(any()))
                .thenReturn(scaledBytes);

        when(metadataExtractorService.calculateMd5(any()))
                .thenReturn("md5-1");

        when(itemInventoryPhotoDao.findByMd5("md5-1"))
                .thenReturn(Optional.empty());

        when(externalItemDao.findByExternalServiceAndNumber(
                BRICKLINK.getExternalServiceId(),
                "3001"))
                .thenReturn(Optional.of(externalItem));

        doAnswer(invocation -> {
            ItemInventory itemInventory = invocation.getArgument(0);
            itemInventory.setItemInventoryId(999);
            return itemInventory;
        }).when(itemInventoryDao).upsert(any(ItemInventory.class));

        service.process(event);

        verify(metadataExtractorService)
                .extractKeywords(any());

        verify(imageScalingService)
                .scale(any());

        verify(metadataExtractorService)
                .calculateMd5(any());

        verify(itemInventoryDao)
                .upsert(any(ItemInventory.class));

        verify(itemInventoryPhotoDao)
                .insertPhoto(
                        eq(999),
                        eq("md5-1"),
                        eq("photos/test.jpg"),
                        eq(false),
                        eq(UPLOADED)
                );

        verify(minioService)
                .putObject(
                        eq("lego-photos-sandbox"),
                        eq("3001/uuid-1/md5-1.jpg"),
                        any(InputStream.class),
                        eq((long) scaledBytes.length),
                        eq("image/jpeg")
                );

        verify(itemInventoryPhotoDao)
                .markUploaded(
                        eq("md5-1"),
                        eq("lego-photos-sandbox"),
                        eq("3001/uuid-1/md5-1.jpg"),
                        eq((long) scaledBytes.length)
                );

        verify(itemInventoryPhotoDao)
                .transitionStatus(
                        "md5-1",
                        UPLOADED,
                        PROCESSED
                );

        verify(itemInventoryPhotoDao)
                .setPrimaryPhoto(999, "md5-1");

        verify(minioService)
                .deleteObject(
                        "lego-uploads-sandbox",
                        "photos/test.jpg"
                );

        verify(photoMetricsService)
                .incrementProcessed("event");
    }

    @Test
    void process_batch_successfully() {

        ExternalItem externalItem = new ExternalItem();
        externalItem.setExternalItemId(100);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "photo.jpg",
                        "image/jpeg",
                        originalBytes
                );

        when(metadataExtractorService.extractKeywords(any()))
                .thenReturn(Map.of(
                        "uuid", "uuid-2",
                        "bl", "3002"
                ));

        when(imageScalingService.scale(any()))
                .thenReturn(scaledBytes);

        when(metadataExtractorService.calculateMd5(any()))
                .thenReturn("md5-2");

        when(itemInventoryPhotoDao.findByMd5("md5-2"))
                .thenReturn(Optional.empty());

        when(externalItemDao.findByExternalServiceAndNumber(
                BRICKLINK.getExternalServiceId(),
                "3002"))
                .thenReturn(Optional.of(externalItem));

        doAnswer(invocation -> {
            ItemInventory itemInventory = invocation.getArgument(0);
            itemInventory.setItemInventoryId(200);
            return itemInventory;
        }).when(itemInventoryDao).upsert(any(ItemInventory.class));

        service.process(file);

        verify(minioService, never())
                .deleteObject(anyString(), anyString());

        verify(photoMetricsService)
                .incrementProcessed("batch");
    }

    @Test
    void process_skips_duplicate_photo() {

        PhotoUploadEvent event = new PhotoUploadEvent();
        event.setBucket("bucket");
        event.setObjectKey("photo.jpg");

        when(minioService.getObject(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(originalBytes));

        when(metadataExtractorService.extractKeywords(any()))
                .thenReturn(Map.of(
                        "uuid", "uuid-1",
                        "bl", "3001"
                ));

        when(imageScalingService.scale(any()))
                .thenReturn(scaledBytes);

        when(metadataExtractorService.calculateMd5(any()))
                .thenReturn("duplicate-md5");

        when(itemInventoryPhotoDao.findByMd5("duplicate-md5"))
                .thenReturn(Optional.of(mock()));

        service.process(event);

        verify(itemInventoryDao, never())
                .upsert(any());

        verify(minioService, never())
                .putObject(any(), any(), any(), anyLong(), any());

        verify(photoMetricsService)
                .incrementDuplicate("event");
    }

    @Test
    void process_fails_when_required_keywords_missing() {

        PhotoUploadEvent event = new PhotoUploadEvent();
        event.setBucket("bucket");
        event.setObjectKey("photo.jpg");

        when(minioService.getObject(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(originalBytes));

        when(metadataExtractorService.extractKeywords(any()))
                .thenReturn(Map.of());

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class)
                .hasMessageContaining("Photo processing failed");

        verify(photoMetricsService)
                .incrementFailed("event");
    }

    @Test
    void process_fails_when_external_item_not_found() {

        PhotoUploadEvent event = new PhotoUploadEvent();
        event.setBucket("bucket");
        event.setObjectKey("photo.jpg");

        when(minioService.getObject(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(originalBytes));

        when(metadataExtractorService.extractKeywords(any()))
                .thenReturn(Map.of(
                        "uuid", "uuid-1",
                        "bl", "9999"
                ));

        when(imageScalingService.scale(any()))
                .thenReturn(scaledBytes);

        when(metadataExtractorService.calculateMd5(any()))
                .thenReturn("md5");

        when(itemInventoryPhotoDao.findByMd5(any()))
                .thenReturn(Optional.empty());

        when(externalItemDao.findByExternalServiceAndNumber(
                BRICKLINK.getExternalServiceId(),
                "9999"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class);

        verify(photoMetricsService)
                .incrementFailed("event");
    }

    @Test
    void process_marks_failed_when_upload_fails() {

        PhotoUploadEvent event = new PhotoUploadEvent();
        event.setBucket("bucket");
        event.setObjectKey("photo.jpg");

        ExternalItem externalItem = new ExternalItem();
        externalItem.setExternalItemId(123);

        when(minioService.getObject(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(originalBytes));

        when(metadataExtractorService.extractKeywords(any()))
                .thenReturn(Map.of(
                        "uuid", "uuid-1",
                        "bl", "3001"
                ));

        when(imageScalingService.scale(any()))
                .thenReturn(scaledBytes);

        when(metadataExtractorService.calculateMd5(any()))
                .thenReturn("md5-fail");

        when(itemInventoryPhotoDao.findByMd5(any()))
                .thenReturn(Optional.empty());

        when(externalItemDao.findByExternalServiceAndNumber(
                BRICKLINK.getExternalServiceId(),
                "3001"))
                .thenReturn(Optional.of(externalItem));

        doAnswer(invocation -> {
            ItemInventory itemInventory = invocation.getArgument(0);
            itemInventory.setItemInventoryId(500);
            return itemInventory;
        }).when(itemInventoryDao).upsert(any(ItemInventory.class));

        doThrow(new RuntimeException("upload failed"))
                .when(minioService)
                .putObject(
                        any(),
                        any(),
                        any(),
                        anyLong(),
                        any()
                );

        assertThatThrownBy(() -> service.process(event))
                .isInstanceOf(PhotoProcessingException.class);

        verify(itemInventoryPhotoDao)
                .transitionStatus(
                        "md5-fail",
                        UPLOADED,
                        PhotoStatus.FAILED
                );

        verify(photoMetricsService)
                .incrementFailed("event");
    }

    @Test
    void process_handles_primary_false_correctly() {

        PhotoUploadEvent event = new PhotoUploadEvent();
        event.setBucket("bucket");
        event.setObjectKey("photo.jpg");

        ExternalItem externalItem = new ExternalItem();
        externalItem.setExternalItemId(123);

        when(minioService.getObject(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(originalBytes));

        when(metadataExtractorService.extractKeywords(any()))
                .thenReturn(Map.of(
                        "uuid", "uuid-1",
                        "bl", "3001",
                        "primary", "false"
                ));

        when(imageScalingService.scale(any()))
                .thenReturn(scaledBytes);

        when(metadataExtractorService.calculateMd5(any()))
                .thenReturn("md5");

        when(itemInventoryPhotoDao.findByMd5(any()))
                .thenReturn(Optional.empty());

        when(externalItemDao.findByExternalServiceAndNumber(
                BRICKLINK.getExternalServiceId(),
                "3001"))
                .thenReturn(Optional.of(externalItem));

        doAnswer(invocation -> {
            ItemInventory itemInventory = invocation.getArgument(0);
            itemInventory.setItemInventoryId(600);
            return itemInventory;
        }).when(itemInventoryDao).upsert(any(ItemInventory.class));

        service.process(event);

        verify(itemInventoryPhotoDao, never())
                .setPrimaryPhoto(anyInt(), anyString());
    }
}