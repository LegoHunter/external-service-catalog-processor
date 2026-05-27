package io.legohunter.egress.imagehosting.snapshot;

import io.legohunter.data.dto.ExternalImage;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventoryPhoto;
import lombok.Builder;
import lombok.Data;

import java.util.Optional;

@Data
@Builder
public class DesiredImageHostingPhoto {
    private ItemInventoryPhoto inventoryPhoto;
    private ExternalImage externalImage;
    private ExternalImageAlbumImage albumMembership;

    public Optional<ExternalImage> externalImageOptional() {
        return Optional.ofNullable(externalImage);
    }

    public Optional<ExternalImageAlbumImage> albumMembershipOptional() {
        return Optional.ofNullable(albumMembership);
    }

    public Integer getItemInventoryPhotoId() {
        return inventoryPhoto == null ? null : inventoryPhoto.getItemInventoryPhotoId();
    }

    public Long getExternalImageId() {
        return externalImage == null ? null : externalImage.getExternalImageId();
    }

    public String getExternalServiceImageId() {
        return externalImage == null ? null : externalImage.getExternalServiceImageId();
    }

    public boolean isPrimary() {
        return inventoryPhoto != null && Boolean.TRUE.equals(inventoryPhoto.getPrimary());
    }

    public boolean hasS3Object() {
        return inventoryPhoto != null
                && hasText(inventoryPhoto.getS3Bucket())
                && hasText(inventoryPhoto.getS3Key());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
