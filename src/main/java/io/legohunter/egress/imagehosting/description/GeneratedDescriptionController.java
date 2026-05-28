package io.legohunter.egress.imagehosting.description;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/image-hosting")
@Tag(name = "Image Hosting Descriptions", description = "Generated item and marketplace descriptions from durable DB state.")
public class GeneratedDescriptionController {
    private final GeneratedDescriptionComposer composer;

    @GetMapping("/item-inventories/{itemInventoryId}/generated-description")
    @Operation(summary = "Preview the generated image-hosting item description for an item inventory.")
    public ResponseEntity<GeneratedItemDescription> generatedDescription(
            @PathVariable Integer itemInventoryId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer externalServiceId
    ) {
        GeneratedItemDescription description = composer.compose(GeneratedDescriptionRequest.builder()
                .itemInventoryId(itemInventoryId)
                .provider(provider)
                .externalServiceId(externalServiceId)
                .build());
        return ResponseEntity.ok(description);
    }
}
