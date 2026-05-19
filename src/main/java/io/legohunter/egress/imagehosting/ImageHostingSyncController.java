package io.legohunter.egress.imagehosting;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
public class ImageHostingSyncController {
    private final ImageHostingSyncService imageHostingSyncService;

    @PostMapping("/item-inventories/{itemInventoryId}/sync")
    public ResponseEntity<ImageHostingSyncResult> syncItemInventory(
            @PathVariable Integer itemInventoryId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) Integer externalServiceId
    ) {
        ImageHostingSyncResult result = imageHostingSyncService.sync(ImageHostingSyncRequest.builder()
                .itemInventoryId(itemInventoryId)
                .externalServiceId(externalServiceId)
                .dryRun(dryRun)
                .build());
        return ResponseEntity.accepted().body(result);
    }
}
