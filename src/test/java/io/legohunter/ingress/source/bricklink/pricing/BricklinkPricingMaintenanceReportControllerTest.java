package io.legohunter.ingress.source.bricklink.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BricklinkPricingMaintenanceReportControllerTest {
    @Test
    void maintenanceReportReturnsServiceReport() {
        BricklinkPricingMaintenanceReportService service = mock(BricklinkPricingMaintenanceReportService.class);
        BricklinkPricingMaintenanceReport expected = new BricklinkPricingMaintenanceReport(
                ZonedDateTime.parse("2026-06-27T12:00:00Z"),
                true,
                null,
                Set.of(),
                Set.of()
        );
        when(service.buildReport(25)).thenReturn(expected);

        BricklinkPricingMaintenanceReportController controller = new BricklinkPricingMaintenanceReportController(service);
        ResponseEntity<BricklinkPricingMaintenanceReport> response = controller.maintenanceReport(25);

        assertThat(response.getBody()).isSameAs(expected);
        verify(service).buildReport(25);
    }
}
