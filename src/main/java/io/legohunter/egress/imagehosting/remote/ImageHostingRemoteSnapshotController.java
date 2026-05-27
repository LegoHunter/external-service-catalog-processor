package io.legohunter.egress.imagehosting.remote;

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
@Tag(name = "Image Hosting Remote Snapshots", description = "Read-only endpoints for inspecting current remote image-hosting state.")
public class ImageHostingRemoteSnapshotController {
    private final ImageHostingRemoteSnapshotReader remoteSnapshotReader;

    @GetMapping("/remote/albums/{albumId}/snapshot")
    @Operation(summary = "Read a remote image-hosting album snapshot.")
    public ResponseEntity<ImageHostingRemoteSnapshot> readRemoteAlbumSnapshot(
            @PathVariable String albumId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer externalServiceId,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "500") int albumPageSize,
            @RequestParam(defaultValue = "500") int photoPageSize
    ) {
        ImageHostingRemoteSnapshot snapshot = remoteSnapshotReader.read(ImageHostingRemoteSnapshotRequest.builder()
                .provider(provider)
                .externalServiceId(externalServiceId)
                .userId(userId)
                .albumId(albumId)
                .albumPageSize(albumPageSize)
                .photoPageSize(photoPageSize)
                .build());
        return ResponseEntity.ok(snapshot);
    }
}
