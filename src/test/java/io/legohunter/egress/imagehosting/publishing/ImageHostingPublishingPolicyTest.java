package io.legohunter.egress.imagehosting.publishing;

import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedPhotoMetadataUpdate;
import io.legohunter.imaging.model.HostedPhotoUploadMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageHostingPublishingPolicyTest {

    @Test
    void uploadMetadataRendersConfiguredPhotoPolicy() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        ImageHostingSyncProperties.Photo photoPolicy = properties.getPublishing().getPhoto();
        photoPolicy.setTitleTemplate("Photo {itemInventoryPhotoId}: {captionOrFilename}");
        photoPolicy.setDescriptionTemplate("{caption}");
        photoPolicy.setTags(List.of("lego", "photo-{itemInventoryPhotoId}", "{caption}"));
        photoPolicy.setPublicFlag(false);
        photoPolicy.setFriendFlag(true);
        photoPolicy.setFamilyFlag(true);
        photoPolicy.setHidden(true);
        photoPolicy.setSafetyLevel("restricted");
        ImageHostingPublishingPolicy policy = new ImageHostingPublishingPolicy(properties);

        HostedPhotoUploadMetadata metadata = policy.uploadMetadata(photo());

        assertThat(metadata.getTitle()).isEqualTo("Photo 11: Front caption");
        assertThat(metadata.getDescription()).isEqualTo("Front caption");
        assertThat(metadata.getTags()).containsExactly("lego", "photo-11", "Front caption");
        assertThat(metadata.getPublicFlag()).isFalse();
        assertThat(metadata.getFriendFlag()).isTrue();
        assertThat(metadata.getFamilyFlag()).isTrue();
        assertThat(metadata.getHidden()).isTrue();
        assertThat(metadata.getSafetyLevel()).isEqualTo("restricted");
    }

    @Test
    void photoMetadataUpdateUsesSameTitleDescriptionAndTags() {
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        properties.getPublishing().getPhoto().setTags(List.of("lego", "{filename}"));
        ImageHostingPublishingPolicy policy = new ImageHostingPublishingPolicy(properties);

        HostedPhotoMetadataUpdate metadataUpdate = policy.photoMetadataUpdate("photo-123", photo());

        assertThat(metadataUpdate.getPhotoId()).isEqualTo("photo-123");
        assertThat(metadataUpdate.getTitle()).isEqualTo("Front caption");
        assertThat(metadataUpdate.getDescription()).isEqualTo("Front caption");
        assertThat(metadataUpdate.getTags()).containsExactly("lego", "front.jpg");
    }

    private ItemInventoryPhoto photo() {
        return ItemInventoryPhoto.builder()
                .itemInventoryPhotoId(11)
                .fileName("front.jpg")
                .caption("Front caption")
                .md5("md5-11")
                .build();
    }
}
