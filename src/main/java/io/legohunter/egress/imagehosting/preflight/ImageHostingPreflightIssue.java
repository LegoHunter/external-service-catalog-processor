package io.legohunter.egress.imagehosting.preflight;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingPreflightIssue {
    private ImageHostingPreflightIssueType type;
    private Integer itemInventoryId;
    private Integer itemInventoryPhotoId;
    private Long externalImageId;
    private Long externalImageAlbumId;
    private String message;
}
