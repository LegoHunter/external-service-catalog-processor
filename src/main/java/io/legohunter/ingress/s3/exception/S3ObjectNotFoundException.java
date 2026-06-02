package io.legohunter.ingress.s3.exception;

public class S3ObjectNotFoundException extends RuntimeException {

    private final String bucket;
    private final String key;

    public S3ObjectNotFoundException(String bucket, String key, Throwable cause) {
        super("S3 object [%s/%s] was not found".formatted(bucket, key), cause);
        this.bucket = bucket;
        this.key = key;
    }

    public String getBucket() {
        return bucket;
    }

    public String getKey() {
        return key;
    }
}
