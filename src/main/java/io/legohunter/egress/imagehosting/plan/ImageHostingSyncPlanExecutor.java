package io.legohunter.egress.imagehosting.plan;

import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncReport;

public interface ImageHostingSyncPlanExecutor {
    SyncReport execute(SyncPlan plan, boolean allowReviewRequired);
}
