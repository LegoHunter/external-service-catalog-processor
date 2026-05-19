package io.legohunter.egress.imagehosting;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingSyncRequest {
    private Integer itemInventoryId;
    private Integer externalServiceId;
}
