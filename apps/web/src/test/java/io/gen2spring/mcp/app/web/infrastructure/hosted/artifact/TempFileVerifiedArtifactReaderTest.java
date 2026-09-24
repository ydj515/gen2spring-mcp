package io.gen2spring.mcp.app.web.infrastructure.hosted.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader.ArtifactSource;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TempFileVerifiedArtifactReaderTest {
    private static final ObjectKey KEY = ObjectKey.parse(
            "artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/result");

    @Test
    void stagesVerifiedBytesBeforeExposingThemForDownload() throws Exception {
        byte[] bytes = "good".getBytes(StandardCharsets.UTF_8);
        String digest = digest(bytes);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.get(KEY)).thenReturn(content(bytes, digest));
        var reader = new TempFileVerifiedArtifactReader(storage, 100);
        var output = new ByteArrayOutputStream();

        try (var verified = reader.open(artifact(digest))) {
            verified.writeTo(output);
        }

        assertArrayEquals(bytes, output.toByteArray());
    }

    @Test
    void rejectsChangedBytesEvenWhenStoredMetadataClaimsTheExpectedDigest() throws Exception {
        byte[] expected = "good".getBytes(StandardCharsets.UTF_8);
        byte[] changed = "evil".getBytes(StandardCharsets.UTF_8);
        String digest = digest(expected);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.get(KEY)).thenReturn(content(changed, digest));

        assertThrows(TempFileVerifiedArtifactReader.ArtifactReadFailure.class,
                () -> new TempFileVerifiedArtifactReader(storage, 100).open(artifact(digest)));
    }

    @Test
    void rejectsAnArtifactBeyondTheConfiguredBoundBeforeStaging() throws Exception {
        byte[] bytes = "good".getBytes(StandardCharsets.UTF_8);
        String digest = digest(bytes);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.get(KEY)).thenReturn(content(bytes, digest));

        assertThrows(TempFileVerifiedArtifactReader.ArtifactReadFailure.class,
                () -> new TempFileVerifiedArtifactReader(storage, 3).open(artifact(digest)));
    }

    private ArtifactSource artifact(String digest) {
        return new ArtifactSource(KEY, digest, 4, "application/zip");
    }

    private StoredObjectContent content(byte[] bytes, String digest) {
        return new StoredObjectContent() {
            @Override public InputStream body() { return new ByteArrayInputStream(bytes); }
            @Override public long size() { return bytes.length; }
            @Override public String sha256() { return digest; }
            @Override public String contentType() { return "application/zip"; }
            @Override public void close() {}
        };
    }

    private String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
