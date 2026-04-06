package io.legohunter.ingress.common.storage.model.s3.minio;

import java.util.List;

public record S3EventNotification(
        String EventName,
        String Key,
        List<Record> Records
) {}