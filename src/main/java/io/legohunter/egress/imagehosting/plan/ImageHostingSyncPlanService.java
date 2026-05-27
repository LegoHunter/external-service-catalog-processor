package io.legohunter.egress.imagehosting.plan;

import io.legohunter.imaging.service.sync.model.SyncPlan;

public interface ImageHostingSyncPlanService {
    SyncPlan plan(ImageHostingSyncPlanRequest request);
}
