package io.legohunter.egress.imagehosting.repair;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingDbRepairPlanRequest {
    private Integer itemInventoryId;
    private String provider;
    private Integer externalServiceId;
    private String userId;

    @Builder.Default
    private int albumPageSize = 500;

    @Builder.Default
    private int photoPageSize = 500;
}
