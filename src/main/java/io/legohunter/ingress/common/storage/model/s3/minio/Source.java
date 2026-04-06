package io.legohunter.ingress.common.storage.model.s3.minio;

public record Source(
        String host,
        String port,
        String userAgent
) {}
