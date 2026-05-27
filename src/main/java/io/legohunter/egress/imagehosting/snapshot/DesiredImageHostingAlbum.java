package io.legohunter.egress.imagehosting.snapshot;

import io.legohunter.data.dto.ExternalImageAlbum;
import lombok.Builder;
import lombok.Data;

import java.util.Optional;

@Data
@Builder
public class DesiredImageHostingAlbum {
    private String desiredTitle;
    private String desiredDescription;
    private ExternalImageAlbum externalAlbum;

    public Optional<ExternalImageAlbum> externalAlbumOptional() {
        return Optional.ofNullable(externalAlbum);
    }

    public Long getExternalImageAlbumId() {
        return externalAlbum == null ? null : externalAlbum.getExternalImageAlbumId();
    }

    public String getExternalAlbumId() {
        return externalAlbum == null ? null : externalAlbum.getExternalAlbumId();
    }

    public String getAlbumUrl() {
        return externalAlbum == null ? null : externalAlbum.getAlbumUrl();
    }

    public String getShortUrl() {
        return externalAlbum == null ? null : externalAlbum.getShortUrl();
    }
}
