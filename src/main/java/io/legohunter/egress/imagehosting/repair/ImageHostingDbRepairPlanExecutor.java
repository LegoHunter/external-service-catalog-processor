package io.legohunter.egress.imagehosting.repair;

import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncReport;

public interface ImageHostingDbRepairPlanExecutor {
    SyncReport execute(SyncPlan plan, boolean allowReviewRequired);
}
