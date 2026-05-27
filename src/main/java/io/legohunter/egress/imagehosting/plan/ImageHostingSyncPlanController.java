package io.legohunter.egress.imagehosting.plan;

import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
@Tag(name = "Image Hosting Sync Plans", description = "Dry-run and apply endpoints for image-hosting reconciliation plans.")
public class ImageHostingSyncPlanController {
    private final ImageHostingSyncPlanService syncPlanService;
    private final ImageHostingSyncPlanExecutor syncPlanExecutor;

    @GetMapping("/item-inventories/{itemInventoryId}/sync-plan")
    @Operation(summary = "Build a dry-run image-hosting sync plan for an item inventory.")
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

    @PostMapping("/item-inventories/{itemInventoryId}/sync-plan/apply")
    @Operation(summary = "Build and apply the current image-hosting sync plan for an item inventory.")
    public ResponseEntity<SyncReport> applyItemInventorySyncPlan(
            @PathVariable Integer itemInventoryId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer externalServiceId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "500") int albumPageSize,
            @RequestParam(defaultValue = "500") int photoPageSize,
            @RequestParam(defaultValue = "false") boolean allowReviewRequired
    ) {
        SyncPlan plan = syncPlanService.plan(ImageHostingSyncPlanRequest.builder()
                .itemInventoryId(itemInventoryId)
                .provider(provider)
                .externalServiceId(externalServiceId)
                .userId(userId)
                .albumPageSize(albumPageSize)
                .photoPageSize(photoPageSize)
                .build());
        SyncReport report = syncPlanExecutor.execute(plan, allowReviewRequired);
        return ResponseEntity.accepted().body(report);
    }
}
