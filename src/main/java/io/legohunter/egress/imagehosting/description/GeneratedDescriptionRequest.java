package io.legohunter.egress.imagehosting.description;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GeneratedDescriptionRequest {
    private Integer itemInventoryId;
    private String provider;
    private Integer externalServiceId;
}
