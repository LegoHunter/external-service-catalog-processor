package io.legohunter.ingress.common.storage.model.s3.minio;

import java.util.Map;

public record ObjectEntity(
        String key,
        long size,
        String eTag,
        String contentType,
        Map<String, String> userMetadata,
        String sequencer
) {}

