package io.gen2spring.mcp.adapter.storage;

import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

public final class S3BucketReadinessProbe {
    private S3BucketReadinessProbe() {}

    public static void verify(S3Client client, String bucket) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(bucket, "bucket");
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }
}
