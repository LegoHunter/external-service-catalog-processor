package io.legohunter.egress.imagehosting.repair;

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
@Tag(name = "Image Hosting DB Repair", description = "Dry-run and apply endpoints for repairing DB image-hosting links from remote state.")
public class ImageHostingDbRepairController {
    private final ImageHostingDbRepairPlanService repairPlanService;
    private final ImageHostingDbRepairPlanExecutor repairPlanExecutor;

    @GetMapping("/item-inventories/{itemInventoryId}/repair-plan")
    @Operation(summary = "Build a dry-run DB repair plan from current remote image-hosting state.")
    public ResponseEntity<SyncPlan> planItemInventoryRepair(
            @PathVariable Integer itemInventoryId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer externalServiceId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "500") int albumPageSize,
            @RequestParam(defaultValue = "500") int photoPageSize
    ) {
        SyncPlan plan = repairPlanService.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(itemInventoryId)
                .provider(provider)
                .externalServiceId(externalServiceId)
                .userId(userId)
                .albumPageSize(albumPageSize)
                .photoPageSize(photoPageSize)
                .build());
        return ResponseEntity.ok(plan);
    }

    @PostMapping("/item-inventories/{itemInventoryId}/repair-plan/apply")
    @Operation(summary = "Build and apply a DB repair plan from current remote image-hosting state.")
    public ResponseEntity<SyncReport> applyItemInventoryRepairPlan(
            @PathVariable Integer itemInventoryId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer externalServiceId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "500") int albumPageSize,
            @RequestParam(defaultValue = "500") int photoPageSize,
            @RequestParam(defaultValue = "false") boolean allowReviewRequired
    ) {
        SyncPlan plan = repairPlanService.plan(ImageHostingDbRepairPlanRequest.builder()
                .itemInventoryId(itemInventoryId)
                .provider(provider)
                .externalServiceId(externalServiceId)
                .userId(userId)
                .albumPageSize(albumPageSize)
                .photoPageSize(photoPageSize)
                .build());
        SyncReport report = repairPlanExecutor.execute(plan, allowReviewRequired);
        return ResponseEntity.accepted().body(report);
    }
}
