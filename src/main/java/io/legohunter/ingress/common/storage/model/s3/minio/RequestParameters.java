package io.legohunter.ingress.common.storage.model.s3.minio;

public record RequestParameters(
        String principalId,
        String region,
        String sourceIPAddress
) {}

