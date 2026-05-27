package io.legohunter.egress.imagehosting.remote;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingRemoteSnapshotRequest {
    private String provider;
    private Integer externalServiceId;
    private String userId;
    private String albumId;

    @Builder.Default
    private int albumPageSize = 500;

    @Builder.Default
    private int photoPageSize = 500;
}
