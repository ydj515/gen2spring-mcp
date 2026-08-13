package io.gen2spring.mcp.adapter.storage;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorageFailure;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3ObjectStorage implements ObjectStorage {
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

    private final S3Client client;
    private final String bucket;
    private final int maxObjectBytes;

    public S3ObjectStorage(S3Client client, String bucket, long maxObjectBytes) {
        this.client = Objects.requireNonNull(client, "client");
        if (bucket == null
                || !BUCKET.matcher(bucket).matches()
                || maxObjectBytes < 1
                || maxObjectBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Object storage configuration is invalid");
        }
        this.bucket = bucket;
        this.maxObjectBytes = Math.toIntExact(maxObjectBytes);
    }

    @Override
    public StoredObject put(
            ObjectKey key,
            InputStream body,
            long size,
            String sha256,
            String contentType) {
        Objects.requireNonNull(body, "body");
        StoredObject expected;
        try {
            expected = new StoredObject(key, size, sha256, contentType);
        } catch (RuntimeException failure) {
            throw rejectedWrite(failure);
        }
        if (size > maxObjectBytes) {
            throw rejectedWrite(null);
        }

        byte[] bytes;
        try {
            bytes = body.readNBytes(maxObjectBytes + 1);
        } catch (IOException failure) {
            throw rejectedWrite(failure);
        }
        if (bytes.length != size || !digest(bytes).equals(sha256)) {
            throw rejectedWrite(null);
        }

        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key.value())
                            .contentType(contentType)
                            .metadata(Map.of("sha256", sha256))
                            .build(),
                    RequestBody.fromBytes(bytes));
            return expected;
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    public StoredObjectContent get(ObjectKey key) {
        Objects.requireNonNull(key, "key");
        ResponseInputStream<GetObjectResponse> response;
        try {
            response = client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .build());
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) {
                throw new ObjectStorageFailure(
                        ObjectStorageFailure.Code.NOT_FOUND,
                        "Stored object is unavailable",
                        failure);
            }
            throw unavailable(failure);
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }

        GetObjectResponse metadata = response.response();
        try {
            if (metadata.contentLength() == null || metadata.contentLength() > maxObjectBytes) {
                throw rejectedRead(null);
            }
            StoredObject stored = new StoredObject(
                    key,
                    metadata.contentLength(),
                    metadata.metadata().get("sha256"),
                    metadata.contentType());
            return new Content(response, stored);
        } catch (RuntimeException failure) {
            try {
                response.close();
            } catch (IOException ignored) {
                // The fixed read failure remains authoritative.
            }
            if (failure instanceof ObjectStorageFailure storageFailure) {
                throw storageFailure;
            }
            throw rejectedRead(failure);
        }
    }

    @Override
    public void delete(ObjectKey key) {
        Objects.requireNonNull(key, "key");
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .build());
        } catch (RuntimeException failure) {
            throw unavailable(failure);
        }
    }

    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private ObjectStorageFailure rejectedWrite(Throwable cause) {
        return new ObjectStorageFailure(
                ObjectStorageFailure.Code.REJECTED,
                "Stored object write was rejected",
                cause);
    }

    private ObjectStorageFailure rejectedRead(Throwable cause) {
        return new ObjectStorageFailure(
                ObjectStorageFailure.Code.REJECTED,
                "Stored object read was rejected",
                cause);
    }

    private ObjectStorageFailure unavailable(Throwable cause) {
        return new ObjectStorageFailure(
                ObjectStorageFailure.Code.UNAVAILABLE,
                "Object storage is unavailable",
                cause);
    }

    private static final class Content implements StoredObjectContent {
        private final ResponseInputStream<GetObjectResponse> body;
        private final StoredObject metadata;

        private Content(ResponseInputStream<GetObjectResponse> body, StoredObject metadata) {
            this.body = body;
            this.metadata = metadata;
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public long size() {
            return metadata.size();
        }

        @Override
        public String sha256() {
            return metadata.sha256();
        }

        @Override
        public String contentType() {
            return metadata.contentType();
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
