package io.legohunter.egress.imagehosting.plan;

import io.legohunter.imaging.service.sync.model.SyncPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageHostingSyncPlanControllerTest {

    @Test
    void planItemInventorySyncBuildsRequestFromPathAndQueryParameters() {
        ImageHostingSyncPlanService service = mock(ImageHostingSyncPlanService.class);
        SyncPlan plan = SyncPlan.builder().planId("plan-1").build();
        when(service.plan(any())).thenReturn(plan);
        ImageHostingSyncPlanController controller = new ImageHostingSyncPlanController(service);

        ResponseEntity<SyncPlan> response = controller.planItemInventorySync(
                100,
                "flickr",
                10,
                "user-123",
                100,
                250
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(plan);

        ArgumentCaptor<ImageHostingSyncPlanRequest> requestCaptor =
                ArgumentCaptor.forClass(ImageHostingSyncPlanRequest.class);
        verify(service).plan(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ImageHostingSyncPlanRequest::getItemInventoryId,
                        ImageHostingSyncPlanRequest::getProvider,
                        ImageHostingSyncPlanRequest::getExternalServiceId,
                        ImageHostingSyncPlanRequest::getUserId,
                        ImageHostingSyncPlanRequest::getAlbumPageSize,
                        ImageHostingSyncPlanRequest::getPhotoPageSize
                )
                .containsExactly(100, "flickr", 10, "user-123", 100, 250);
    }
}
