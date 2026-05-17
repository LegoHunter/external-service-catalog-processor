package io.legohunter.ingress.s3.api;

import java.io.InputStream;

public interface MinioService {

    InputStream getObject(String bucket, String key);

    void putObject(String bucket, String key, InputStream stream, long size, String contentType);

    void copyObject(String sourceBucket, String sourceKey, String destBucket, String destKey);

    void deleteObject(String bucket, String key);
}