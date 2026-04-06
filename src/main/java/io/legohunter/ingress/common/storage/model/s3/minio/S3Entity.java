package io.legohunter.ingress.common.storage.model.s3.minio;

import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.S3Object;

public record S3Entity(
        String s3SchemaVersion,
        String configurationId,
        BucketEntity bucket,
        ObjectEntity object
) {}

