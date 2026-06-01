package io.legohunter.egress.imagehosting.shorturl;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Data
@Builder
public class ImageHostingShortUrlBackfillReport {
    private String provider;
    private Integer externalServiceId;

    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    private LocalDateTime finishedAt;
    private int scannedAlbumCount;
    private int eligibleAlbumCount;
    private int recoveredCount;
    private int lookupMissCount;
    private int failedCount;
    private int disabledCount;

    @Singular
    private List<ImageHostingShortUrlBackfillItem> items;

    public List<ImageHostingShortUrlBackfillItem> getItems() {
        return Optional.ofNullable(items).orElse(Collections.emptyList());
    }
}
