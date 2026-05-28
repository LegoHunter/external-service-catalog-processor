package io.legohunter.egress.imagehosting.repair;

import io.legohunter.imaging.service.sync.model.SyncPlan;
import io.legohunter.imaging.service.sync.model.SyncReport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageHostingDbRepairControllerTest {

    @Test
    void planItemInventoryRepairBuildsRequestFromPathAndQueryParameters() {
        ImageHostingDbRepairPlanService service = mock(ImageHostingDbRepairPlanService.class);
        ImageHostingDbRepairPlanExecutor executor = mock(ImageHostingDbRepairPlanExecutor.class);
        SyncPlan plan = SyncPlan.builder().planId("repair-plan-1").build();
        when(service.plan(any())).thenReturn(plan);
        ImageHostingDbRepairController controller = new ImageHostingDbRepairController(service, executor);

        ResponseEntity<SyncPlan> response = controller.planItemInventoryRepair(
                100,
                "flickr",
                10,
                "user-123",
                100,
                250
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(plan);

        ArgumentCaptor<ImageHostingDbRepairPlanRequest> requestCaptor =
                ArgumentCaptor.forClass(ImageHostingDbRepairPlanRequest.class);
        verify(service).plan(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ImageHostingDbRepairPlanRequest::getItemInventoryId,
                        ImageHostingDbRepairPlanRequest::getProvider,
                        ImageHostingDbRepairPlanRequest::getExternalServiceId,
                        ImageHostingDbRepairPlanRequest::getUserId,
                        ImageHostingDbRepairPlanRequest::getAlbumPageSize,
                        ImageHostingDbRepairPlanRequest::getPhotoPageSize
                )
                .containsExactly(100, "flickr", 10, "user-123", 100, 250);
    }

    @Test
    void applyItemInventoryRepairBuildsPlanThenExecutesIt() {
        ImageHostingDbRepairPlanService service = mock(ImageHostingDbRepairPlanService.class);
        ImageHostingDbRepairPlanExecutor executor = mock(ImageHostingDbRepairPlanExecutor.class);
        SyncPlan plan = SyncPlan.builder().planId("repair-plan-1").build();
        SyncReport report = SyncReport.builder().planId("repair-plan-1").build();
        when(service.plan(any())).thenReturn(plan);
        when(executor.execute(plan, true)).thenReturn(report);
        ImageHostingDbRepairController controller = new ImageHostingDbRepairController(service, executor);

        ResponseEntity<SyncReport> response = controller.applyItemInventoryRepairPlan(
                100,
                "flickr",
                10,
                "user-123",
                100,
                250,
                true
        );

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isSameAs(report);
        verify(executor).execute(plan, true);
    }
}
