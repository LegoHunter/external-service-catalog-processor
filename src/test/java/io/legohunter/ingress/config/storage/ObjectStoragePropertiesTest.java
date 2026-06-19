package io.legohunter.ingress.config.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectStoragePropertiesTest {

    @Test
    void finalPhotoBucketReturnsTrimmedBucketName() {
        ObjectStorageProperties properties = objectStorageProperties(" lego-photos-sandbox ");

        assertThat(properties.finalPhotoBucket())
                .isEqualTo("lego-photos-sandbox");
    }

    @Test
    void finalPhotoBucketRejectsBlankBucketName() {
        ObjectStorageProperties properties = objectStorageProperties(" ");

        assertThatThrownBy(properties::finalPhotoBucket)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lego.object-storage.buckets.final-photo must be configured");
    }

    private static ObjectStorageProperties objectStorageProperties(String finalPhotoBucket) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        ObjectStorageProperties.Buckets buckets = new ObjectStorageProperties.Buckets();
        buckets.setFinalPhoto(finalPhotoBucket);
        properties.setBuckets(buckets);
        return properties;
    }
}
