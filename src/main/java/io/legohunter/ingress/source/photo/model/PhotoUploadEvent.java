package io.legohunter.ingress.source.photo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PhotoUploadEvent {
    private String bucket;
    private String objectKey;
}