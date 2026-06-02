package io.legohunter.ingress.common.storage.model.s3.minio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record S3Entity(
        String s3SchemaVersion,
        String configurationId,
        BucketEntity bucket,
        ObjectEntity object
) {}

