package io.legohunter.egress.imagehosting.publishing;

import io.legohunter.data.dto.ItemInventoryPhoto;
import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.legohunter.imaging.model.HostedPhotoMetadataUpdate;
import io.legohunter.imaging.model.HostedPhotoUploadMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ImageHostingPublishingPolicy {
    private final ImageHostingSyncProperties properties;

    public HostedPhotoUploadMetadata uploadMetadata(ItemInventoryPhoto photo) {
        ImageHostingSyncProperties.Photo policy = photoPolicy();
        return HostedPhotoUploadMetadata.builder()
                .title(photoTitle(photo))
                .description(photoDescription(photo))
                .tags(renderTags(policy.getTags(), photo))
                .publicFlag(policy.getPublicFlag())
                .friendFlag(policy.getFriendFlag())
                .familyFlag(policy.getFamilyFlag())
                .hidden(policy.getHidden())
                .safetyLevel(policy.getSafetyLevel())
                .build();
    }

    public HostedPhotoMetadataUpdate photoMetadataUpdate(String photoId, ItemInventoryPhoto photo) {
        return HostedPhotoMetadataUpdate.builder()
                .photoId(photoId)
                .title(photoTitle(photo))
                .description(photoDescription(photo))
                .tags(renderTags(photoPolicy().getTags(), photo))
                .build();
    }

    public String photoTitle(ItemInventoryPhoto photo) {
        return renderTemplate(photoPolicy().getTitleTemplate(), photo, fallbackTitle(photo));
    }

    public String photoDescription(ItemInventoryPhoto photo) {
        return renderTemplate(photoPolicy().getDescriptionTemplate(), photo, photoTitle(photo));
    }

    private ImageHostingSyncProperties.Photo photoPolicy() {
        return Optional.ofNullable(properties.getPublishing())
                .map(ImageHostingSyncProperties.Publishing::getPhoto)
                .orElseGet(ImageHostingSyncProperties.Photo::new);
    }

    private List<String> renderTags(List<String> tags, ItemInventoryPhoto photo) {
        return Optional.ofNullable(tags).orElse(List.of()).stream()
                .map(tag -> renderTemplate(tag, photo, ""))
                .filter(this::hasText)
                .distinct()
                .toList();
    }

    private String renderTemplate(String template, ItemInventoryPhoto photo, String fallback) {
        if (!hasText(template)) {
            return fallback;
        }

        String rendered = template
                .replace("{captionOrFilename}", captionOrFilename(photo))
                .replace("{captionOrTitle}", hasText(caption(photo)) ? caption(photo) : fallbackTitle(photo))
                .replace("{caption}", caption(photo))
                .replace("{filename}", filename(photo))
                .replace("{itemInventoryPhotoId}", value(photo == null ? null : photo.getItemInventoryPhotoId()))
                .replace("{md5}", value(photo == null ? null : photo.getMd5()));
        return hasText(rendered) ? rendered.trim() : fallback;
    }

    private String fallbackTitle(ItemInventoryPhoto photo) {
        if (hasText(caption(photo))) {
            return caption(photo);
        }
        if (hasText(filename(photo))) {
            return filename(photo);
        }
        return "photo-%s.jpg".formatted(value(photo == null ? null : photo.getItemInventoryPhotoId()));
    }

    private String captionOrFilename(ItemInventoryPhoto photo) {
        if (hasText(caption(photo))) {
            return caption(photo);
        }
        return filename(photo);
    }

    private String caption(ItemInventoryPhoto photo) {
        return value(photo == null ? null : photo.getCaption());
    }

    private String filename(ItemInventoryPhoto photo) {
        return value(photo == null ? null : photo.getFileName());
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
