package io.legohunter.egress.imagehosting.plan;

import io.legohunter.imaging.service.sync.model.SyncPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
public class ImageHostingSyncPlanController {
    private final ImageHostingSyncPlanService syncPlanService;

    @GetMapping("/item-inventories/{itemInventoryId}/sync-plan")
    public ResponseEntity<SyncPlan> planItemInventorySync(
            @PathVariable Integer itemInventoryId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer externalServiceId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "500") int albumPageSize,
            @RequestParam(defaultValue = "500") int photoPageSize
    ) {
        SyncPlan plan = syncPlanService.plan(ImageHostingSyncPlanRequest.builder()
                .itemInventoryId(itemInventoryId)
                .provider(provider)
                .externalServiceId(externalServiceId)
                .userId(userId)
                .albumPageSize(albumPageSize)
                .photoPageSize(photoPageSize)
                .build());
        return ResponseEntity.ok(plan);
    }
}
