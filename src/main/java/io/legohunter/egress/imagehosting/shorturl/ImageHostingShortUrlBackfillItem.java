package io.legohunter.egress.imagehosting.shorturl;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImageHostingShortUrlBackfillItem {
    private Long externalImageAlbumId;
    private Integer itemInventoryId;
    private String externalAlbumId;
    private String albumUrl;
    private String shortUrl;
    private ImageHostingShortUrlStatus status;
    private String message;
}
