package io.legohunter.egress.imagehosting.plan;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingSyncPlanRequest {
    private Integer itemInventoryId;
    private String provider;
    private Integer externalServiceId;
    private String userId;

    @Builder.Default
    private int albumPageSize = 500;

    @Builder.Default
    private int photoPageSize = 500;
}
