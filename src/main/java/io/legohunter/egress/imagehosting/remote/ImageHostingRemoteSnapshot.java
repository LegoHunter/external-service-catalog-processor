package io.legohunter.egress.imagehosting.remote;

import io.legohunter.imaging.model.HostedAlbum;
import io.legohunter.imaging.model.HostedPhoto;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Data
@Builder
public class ImageHostingRemoteSnapshot {
    private String provider;
    private Integer externalServiceId;
    private String userId;
    private String requestedAlbumId;
    private HostedAlbum album;
    private int albumPagesRead;
    private int photoPagesRead;

    @Singular
    private List<HostedPhoto> photos;

    @Singular
    private List<String> failureMessages;

    public Optional<HostedAlbum> albumOptional() {
        return Optional.ofNullable(album);
    }

    public List<HostedPhoto> getPhotos() {
        return Optional.ofNullable(photos).orElse(Collections.emptyList());
    }

    public List<String> getFailureMessages() {
        return Optional.ofNullable(failureMessages).orElse(Collections.emptyList());
    }

    public boolean isAlbumFound() {
        return album != null;
    }

    public boolean isSuccessful() {
        return getFailureMessages().isEmpty();
    }
}
