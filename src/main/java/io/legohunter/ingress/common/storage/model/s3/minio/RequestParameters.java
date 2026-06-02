package io.legohunter.ingress.common.storage.model.s3.minio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RequestParameters(
        String principalId,
        String region,
        String sourceIPAddress
) {}

