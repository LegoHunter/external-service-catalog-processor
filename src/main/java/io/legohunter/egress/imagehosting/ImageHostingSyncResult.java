package io.legohunter.egress.imagehosting;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

@Data
@Builder
public class ImageHostingSyncResult {
    private Integer itemInventoryId;
    private String provider;
    private Integer externalServiceId;
    private boolean dryRun;
    private boolean retryFailed;
    private ImageHostingSyncOutcome outcome;
    private int photosDiscovered;
    private int photosUploaded;
    private int photosMetadataUpdated;
    private int photosSkipped;
    private int photosFailed;
    private Long externalImageAlbumId;
    private String albumId;
    private String albumUrl;
    private boolean albumCreated;
    private boolean membershipUpdated;

    @Singular
    private List<Integer> uploadedPhotoIds;

    @Singular
    private List<Integer> metadataUpdatedPhotoIds;

    @Singular
    private List<Integer> skippedPhotoIds;

    @Singular
    private List<Integer> failedPhotoIds;

    @Singular
    private List<String> failureMessages;
}
