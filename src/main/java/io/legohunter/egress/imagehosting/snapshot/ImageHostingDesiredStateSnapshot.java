package io.legohunter.egress.imagehosting.snapshot;

import io.legohunter.data.dto.ExternalItem;
import io.legohunter.data.dto.ExternalImageAlbumImage;
import io.legohunter.data.dto.ItemInventory;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Data
@Builder
public class ImageHostingDesiredStateSnapshot {
    private String provider;
    private Integer externalServiceId;
    private ItemInventory inventory;
    private ExternalItem externalItem;
    private DesiredImageHostingAlbum album;

    @Singular
    private List<DesiredImageHostingPhoto> photos;

    @Singular
    private List<ExternalImageAlbumImage> albumMemberships;

    public Integer getItemInventoryId() {
        return inventory == null ? null : inventory.getItemInventoryId();
    }

    public List<DesiredImageHostingPhoto> getPhotos() {
        return Optional.ofNullable(photos).orElse(Collections.emptyList());
    }

    public List<ExternalImageAlbumImage> getAlbumMemberships() {
        return Optional.ofNullable(albumMemberships).orElse(Collections.emptyList());
    }

    public Optional<ExternalItem> externalItemOptional() {
        return Optional.ofNullable(externalItem);
    }

    public Optional<DesiredImageHostingAlbum> albumOptional() {
        return Optional.ofNullable(album);
    }

    public boolean hasPhotos() {
        return !getPhotos().isEmpty();
    }

    public Optional<DesiredImageHostingPhoto> primaryPhotoOptional() {
        return getPhotos().stream()
                .filter(DesiredImageHostingPhoto::isPrimary)
                .findFirst();
    }
}
