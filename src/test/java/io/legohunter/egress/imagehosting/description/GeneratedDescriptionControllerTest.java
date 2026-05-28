package io.legohunter.egress.imagehosting.description;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratedDescriptionControllerTest {

    @Test
    void generatedDescriptionBuildsRequestFromPathAndQueryParameters() {
        GeneratedDescriptionComposer composer = mock(GeneratedDescriptionComposer.class);
        GeneratedItemDescription description = GeneratedItemDescription.builder()
                .itemInventoryId(100)
                .provider("flickr")
                .externalServiceId(10)
                .description("Generated description")
                .build();
        when(composer.compose(GeneratedDescriptionRequest.builder()
                .itemInventoryId(100)
                .provider("flickr")
                .externalServiceId(10)
                .build()))
                .thenReturn(description);
        GeneratedDescriptionController controller = new GeneratedDescriptionController(composer);

        ResponseEntity<GeneratedItemDescription> response = controller.generatedDescription(100, "flickr", 10);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(description);

        ArgumentCaptor<GeneratedDescriptionRequest> requestCaptor =
                ArgumentCaptor.forClass(GeneratedDescriptionRequest.class);
        verify(composer).compose(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        GeneratedDescriptionRequest::getItemInventoryId,
                        GeneratedDescriptionRequest::getProvider,
                        GeneratedDescriptionRequest::getExternalServiceId
                )
                .containsExactly(100, "flickr", 10);
    }
}
