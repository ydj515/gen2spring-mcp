package io.gen2spring.mcp.app.web.application.local.result;

public record ArtifactDownload(byte[] bytes, String contentType, String downloadName) {
    public ArtifactDownload {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
