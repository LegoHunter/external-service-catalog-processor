package io.legohunter.ingress.common.storage.model.s3.minio;

public record BucketEntity(
        String name,
        OwnerIdentity ownerIdentity,
        String arn
) {}
