package io.legohunter.ingress.common.storage.model.s3.minio;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ResponseElements(
        @JsonProperty("x-amz-id-2")
        String xAmzId2,

        @JsonProperty("x-amz-request-id")
        String xAmzRequestId,

        @JsonProperty("x-minio-deployment-id")
        String xMinioDeploymentId,

        @JsonProperty("x-minio-origin-endpoint")
        String xMinioOriginEndpoint,

        @JsonProperty("content-length")
        long contentLength
) {}
