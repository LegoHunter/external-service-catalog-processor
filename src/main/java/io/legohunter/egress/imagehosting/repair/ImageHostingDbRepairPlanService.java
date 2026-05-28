package io.legohunter.egress.imagehosting.repair;

import io.legohunter.imaging.service.sync.model.SyncPlan;

public interface ImageHostingDbRepairPlanService {
    SyncPlan plan(ImageHostingDbRepairPlanRequest request);
}
