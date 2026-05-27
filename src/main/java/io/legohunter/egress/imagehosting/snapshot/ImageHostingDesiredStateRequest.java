package io.legohunter.egress.imagehosting.snapshot;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingDesiredStateRequest {
    private Integer itemInventoryId;
    private String provider;
    private Integer externalServiceId;
}
