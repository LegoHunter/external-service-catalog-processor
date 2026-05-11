package io.legohunter.ingress.source.photo.service;

import io.legohunter.ingress.common.logging.LoggingContext;
import io.legohunter.ingress.source.photo.exception.PhotoProcessingException;
import io.legohunter.ingress.source.photo.exception.Unchecked;
import io.legohunter.ingress.source.photo.metrics.PhotoMetricsService;
import io.legohunter.ingress.source.photo.model.PhotoUploadEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lego.data.v2.dao.ExternalItemDao;
import net.lego.data.v2.dao.ItemInventoryDao;
import net.lego.data.v2.dao.ItemInventoryPhotoDao;
import net.lego.data.v2.dto.ExternalItem;
import net.lego.data.v2.dto.ItemInventory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;

import static net.lego.data.v2.dto.ExternalService.ExternalServiceType.BRICKLINK;
import static net.lego.data.v2.enums.PhotoStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoProcessingService {

    private static final String FINAL_BUCKET = "lego-photos-sandbox";

    private final MinioService minioService;
    private final ImageScalingService imageScalingService;
    private final MetadataExtractorService metadataExtractorService;
    private final PhotoMetricsService photoMetricsService;

    private final ItemInventoryDao itemInventoryDao;
    private final ItemInventoryPhotoDao itemInventoryPhotoDao;
    private final ExternalItemDao externalItemDao;

    public void process(PhotoUploadEvent event) {

        execute(
                event.getObjectKey(),
                "event",
                true,
                () -> minioService.getObject(
                        event.getBucket(),
                        event.getObjectKey()
                ),
                event
        );
    }

    public void process(MultipartFile file) {

        execute(
                file.getOriginalFilename(),
                "batch",
                false,
                Unchecked.wrap(
                        file::getInputStream,
                        e -> new PhotoProcessingException(
                                "Failed to open multipart file stream",
                                e
                        )
                ),
                null
        );
    }

    private void execute(
            String filename,
            String mode,
            boolean deleteSource,
            Supplier<InputStream> streamSupplier,
            PhotoUploadEvent event
    ) {

        LoggingContext.init();

        long startTime = System.currentTimeMillis();

        try {

            log.info(
                    "photo.process.start mode={} filename={}",
                    mode,
                    filename
            );

            try (InputStream is = streamSupplier.get()) {

                processInternal(
                        is,
                        filename,
                        deleteSource,
                        event,
                        mode
                );
            }

            photoMetricsService.incrementProcessed(mode);

            log.info(
                    "photo.process.success mode={} filename={}",
                    mode,
                    filename
            );

        } catch (Exception e) {

            photoMetricsService.incrementFailed(mode);

            log.error(
                    "photo.process.failed mode={} filename={}",
                    mode,
                    filename,
                    e
            );

            throw new PhotoProcessingException(
                    "Photo processing failed for file [%s]"
                            .formatted(filename),
                    e
            );

        } finally {

            long duration = System.currentTimeMillis() - startTime;

            photoMetricsService.recordProcessingTime(
                    duration,
                    mode
            );

            log.info(
                    "photo.process.complete mode={} durationMs={}",
                    mode,
                    duration
            );

            LoggingContext.clear();
        }
    }

    private void processInternal(
            InputStream originalStream,
            String originalFilename,
            boolean deleteSource,
            PhotoUploadEvent event,
            String mode
    ) throws Exception {

        MDC.put("filename", originalFilename);

        // =========================================================
        // READ ORIGINAL
        // =========================================================

        byte[] originalBytes = originalStream.readAllBytes();

        log.info(
                "photo.read.complete sizeBytes={}",
                originalBytes.length
        );

        // =========================================================
        // EXTRACT METADATA
        // =========================================================

        Map<String, String> keywords =
                metadataExtractorService.extractKeywords(originalBytes);

        String uuid = keywords.get("uuid");
        String bricklinkItemNumber = keywords.get("bl");

        boolean primaryPhoto = parseBooleanKeyword(keywords.get("primary"));

        boolean sealed = parseBooleanKeyword(keywords.get("sealed"));

        boolean builtOnce = parseBooleanKeyword(keywords.get("bo"));

        String description = keywords.get("description");

        if (uuid != null) {
            MDC.put("uuid", uuid);
        }

        if (bricklinkItemNumber != null) {
            MDC.put("bricklink", bricklinkItemNumber);
        }

        if (uuid == null || bricklinkItemNumber == null) {

            throw new IllegalStateException(
                    "Missing required metadata keywords"
            );
        }

        log.info(
                "photo.metadata.extracted uuid={} bricklink={}",
                uuid,
                bricklinkItemNumber
        );

        // =========================================================
        // SCALE IMAGE
        // =========================================================

        byte[] scaledBytes = imageScalingService.scale(originalBytes);

        log.info(
                "photo.scaled originalSize={} scaledSize={}",
                originalBytes.length,
                scaledBytes.length
        );

        // =========================================================
        // MD5
        // =========================================================

        String md5 =
                metadataExtractorService.calculateMd5(scaledBytes);

        MDC.put("md5", md5);

        log.info(
                "photo.hash.computed md5={}",
                md5
        );

        // =========================================================
        // DUPLICATE CHECK
        // =========================================================

        if (itemInventoryPhotoDao.findByMd5(md5).isPresent()) {

            log.info(
                    "photo.duplicate.detected md5={}",
                    md5
            );

            photoMetricsService.incrementDuplicate(mode);

            return;
        }

        // =========================================================
        // FIND EXTERNAL ITEM
        // =========================================================

        ExternalItem externalItem =
                externalItemDao.findByExternalServiceAndNumber(
                                BRICKLINK.getExternalServiceId(),
                                bricklinkItemNumber
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Bricklink item number [%s] extracted from photo [%s] was not found"
                                                .formatted(
                                                        bricklinkItemNumber,
                                                        originalFilename
                                                )
                                )
                        );

        // =========================================================
        // UPSERT ITEM INVENTORY
        // =========================================================

        ItemInventory itemInventory =
                buildItemInventory(
                        uuid,
                        externalItem.getExternalItemId(),
                        description,
                        sealed,
                        builtOnce
                );

        itemInventoryDao.upsert(itemInventory);

        log.info(
                "photo.db.inventory.upsert uuid={} id={}",
                uuid,
                itemInventory.getItemInventoryId()
        );

        // =========================================================
        // INSERT PHOTO ROW
        // =========================================================

        itemInventoryPhotoDao.insertPhoto(
                itemInventory.getItemInventoryId(),
                md5,
                originalFilename,
                false,
                UPLOADED
        );

        String destinationKey =
                buildKey(
                        bricklinkItemNumber,
                        uuid,
                        md5
                );

        // =========================================================
        // UPLOAD TO FINAL BUCKET
        // =========================================================

        try {

            minioService.putObject(
                    FINAL_BUCKET,
                    destinationKey,
                    new ByteArrayInputStream(scaledBytes),
                    scaledBytes.length,
                    "image/jpeg"
            );

            itemInventoryPhotoDao.markUploaded(
                    md5,
                    FINAL_BUCKET,
                    destinationKey,
                    scaledBytes.length
            );

            itemInventoryPhotoDao.transitionStatus(
                    md5,
                    UPLOADED,
                    PROCESSED
            );

            // =============================================
            // PRIMARY PHOTO
            // =============================================

            if (primaryPhoto) {

                itemInventoryPhotoDao.setPrimaryPhoto(
                        itemInventory.getItemInventoryId(),
                        md5
                );

                log.info(
                        "photo.primary.set itemInventoryId={} md5={}",
                        itemInventory.getItemInventoryId(),
                        md5
                );
            }

            log.info(
                    "photo.upload.success bucket={} key={}",
                    FINAL_BUCKET,
                    destinationKey
            );

        } catch (Exception e) {

            // =============================================
            // CLEANUP S3 IF DB TRANSITION FAILS
            // =============================================

            try {

                minioService.deleteObject(
                        FINAL_BUCKET,
                        destinationKey
                );

                log.warn(
                        "photo.upload.rollback.deleted bucket={} key={}",
                        FINAL_BUCKET,
                        destinationKey
                );

            } catch (Exception cleanupException) {

                log.error(
                        "photo.upload.rollback.failed key={}",
                        destinationKey,
                        cleanupException
                );
            }

            try {

                itemInventoryPhotoDao.transitionStatus(
                        md5,
                        UPLOADED,
                        FAILED
                );

            } catch (Exception transitionException) {

                log.error(
                        "photo.status.transition.failed md5={}",
                        md5,
                        transitionException
                );
            }

            log.error(
                    "photo.upload.failed md5={}",
                    md5,
                    e
            );

            throw e;
        }

        // =========================================================
        // DELETE SOURCE OBJECT
        // =========================================================

        if (deleteSource && event != null) {

            try {

                minioService.deleteObject(
                        event.getBucket(),
                        event.getObjectKey()
                );

                log.info(
                        "photo.source.deleted bucket={} key={}",
                        event.getBucket(),
                        event.getObjectKey()
                );

            } catch (Exception e) {

                log.warn(
                        "photo.source.delete.failed bucket={} key={}",
                        event.getBucket(),
                        event.getObjectKey(),
                        e
                );
            }
        }
    }

    private ItemInventory buildItemInventory(
            String uuid,
            Integer externalItemId,
            String description,
            boolean sealed,
            boolean builtOnce
    ) {

        ItemInventory itemInventory = new ItemInventory();

        itemInventory.setUuid(uuid);
        itemInventory.setItemId(externalItemId);

        itemInventory.setDescription(description);

        itemInventory.setSealed(sealed);
        itemInventory.setBuiltOnce(builtOnce);

        itemInventory.setActive(true);

        return itemInventory;
    }

    private boolean parseBooleanKeyword(String value) {

        return value != null
                && (
                "true".equalsIgnoreCase(value)
                        || "yes".equalsIgnoreCase(value)
                        || "1".equals(value)
        );
    }

    private String buildKey(
            String bricklinkItemNumber,
            String uuid,
            String md5
    ) {

        return "%s/%s/%s.jpg".formatted(
                bricklinkItemNumber,
                uuid,
                md5
        );
    }
}