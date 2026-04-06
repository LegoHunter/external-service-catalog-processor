package io.legohunter.ingress.common.storage.model.s3.minio;

public record Record(
        String eventVersion,
        String eventSource,
        String awsRegion,
        String eventTime,
        String eventName,
        UserIdentity userIdentity,
        RequestParameters requestParameters,
        ResponseElements responseElements,
        S3Entity s3,
        Source source
) {}

