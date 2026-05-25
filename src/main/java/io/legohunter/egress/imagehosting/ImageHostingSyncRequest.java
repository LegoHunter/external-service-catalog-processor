package io.legohunter.egress.imagehosting;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingSyncRequest {
    private Integer itemInventoryId;
    private String provider;
    private Integer externalServiceId;
    @Builder.Default
    private boolean dryRun = false;
    @Builder.Default
    private boolean retryFailed = false;
}
