package io.legohunter.ingress.s3.service;

import io.legohunter.ingress.s3.api.MinioService;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;

    @Override
    public InputStream getObject(String bucket, String key) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(key).build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean objectExists(String bucket, String key) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            return false;
        }

        try {
            minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(key).build()
            );
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void putObject(String bucket, String key, InputStream stream, long size, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(stream, size, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void copyObject(String sourceBucket, String sourceKey, String destBucket, String destKey) {
        try {
            minioClient.copyObject(
                    CopyObjectArgs.builder()
                            .source(CopySource.builder().bucket(sourceBucket).object(sourceKey).build())
                            .bucket(destBucket)
                            .object(destKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(key).build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
