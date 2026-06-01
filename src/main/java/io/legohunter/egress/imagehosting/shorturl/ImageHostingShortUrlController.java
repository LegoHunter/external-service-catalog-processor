package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting/short-urls")
@Tag(name = "Image Hosting Short URLs", description = "Bitly short URL recovery and backfill for hosted image albums.")
public class ImageHostingShortUrlController {
    private final ImageHostingShortUrlService shortUrlService;
    private final ImageHostingSyncProperties properties;

    @PostMapping("/backfill")
    @Operation(summary = "Recover missing DB short URLs from existing Bitly account links.")
    public ResponseEntity<ImageHostingShortUrlBackfillReport> backfillMissingShortUrls(
            @Parameter(description = "Configured image-hosting provider key. Defaults to the configured default provider.")
            @RequestParam(required = false) String provider,
            @Parameter(description = "External service id to backfill. Defaults from the resolved provider.")
            @RequestParam(required = false) Integer externalServiceId
    ) {
        ImageHostingSyncProperties.ResolvedProvider resolvedProvider =
                properties.resolveProvider(provider, externalServiceId);
        ImageHostingShortUrlBackfillReport report = shortUrlService.backfillMissingShortUrls(
                resolvedProvider.provider(),
                resolvedProvider.externalServiceId()
        );
        return ResponseEntity.accepted().body(report);
    }
}
