package io.legohunter.egress.imagehosting.shorturl;

import io.legohunter.egress.imagehosting.ImageHostingSyncProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageHostingShortUrlControllerTest {
    @Test
    void backfillMissingShortUrlsResolvesProviderAndDelegatesToService() {
        ImageHostingShortUrlService service = mock(ImageHostingShortUrlService.class);
        ImageHostingSyncProperties properties = new ImageHostingSyncProperties();
        ImageHostingSyncProperties.Provider flickr = new ImageHostingSyncProperties.Provider();
        flickr.setExternalServiceId(10);
        properties.getProviders().put("flickr", flickr);
        ImageHostingShortUrlBackfillReport report = ImageHostingShortUrlBackfillReport.builder()
                .provider("flickr")
                .externalServiceId(10)
                .build();
        when(service.backfillMissingShortUrls("flickr", 10)).thenReturn(report);

        ImageHostingShortUrlController controller = new ImageHostingShortUrlController(service, properties);

        ResponseEntity<ImageHostingShortUrlBackfillReport> response =
                controller.backfillMissingShortUrls("flickr", null);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isSameAs(report);
        ArgumentCaptor<Integer> externalServiceIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(service).backfillMissingShortUrls(eq("flickr"), externalServiceIdCaptor.capture());
        assertThat(externalServiceIdCaptor.getValue()).isEqualTo(10);
    }
}
