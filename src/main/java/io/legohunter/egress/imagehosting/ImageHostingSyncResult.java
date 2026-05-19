package io.legohunter.egress.imagehosting;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;

@Data
@Builder
public class ImageHostingSyncResult {
    private Integer itemInventoryId;
    private Integer externalServiceId;
    private int photosDiscovered;
    private int photosUploaded;
    private int photosSkipped;
    private int photosFailed;
    private boolean albumCreated;
    private boolean membershipUpdated;

    @Singular
    private List<String> failureMessages;
}
