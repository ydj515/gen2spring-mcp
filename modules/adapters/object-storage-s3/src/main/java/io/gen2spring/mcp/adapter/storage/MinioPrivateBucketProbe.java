package io.gen2spring.mcp.adapter.storage;

import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class MinioPrivateBucketProbe {
    private MinioPrivateBucketProbe() {}

    public static void verify(S3Client client, String bucket) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(bucket, "bucket");
        client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        try {
            client.getBucketPolicy(GetBucketPolicyRequest.builder().bucket(bucket).build());
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404
                    && failure.awsErrorDetails() != null
                    && "NoSuchBucketPolicy".equals(failure.awsErrorDetails().errorCode())) {
                return;
            }
            throw failure;
        }
        throw new IllegalStateException("Bucket policy must be absent");
    }
}
