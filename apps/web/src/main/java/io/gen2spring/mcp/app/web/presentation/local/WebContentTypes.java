package io.gen2spring.mcp.app.web.presentation.local;

import io.gen2spring.mcp.app.web.presentation.error.WebErrorMapper;

import java.util.List;

final class WebContentTypes {
    private static final List<String> UPLOAD = List.of(
            "application/octet-stream", "application/yaml", "text/yaml", "application/json");

    private WebContentTypes() {}

    static void requireUpload(String contentType) {
        if (!UPLOAD.contains(contentType)) {
            throw unsupported();
        }
    }

    static void requireJson(String contentType) {
        if (!("application/json".equals(contentType)
                || "application/json; charset=utf-8".equalsIgnoreCase(contentType))) {
            throw unsupported();
        }
    }

    private static WebErrorMapper.WebException unsupported() {
        return WebErrorMapper.failure(
                415, "CONTENT_TYPE_UNSUPPORTED", "HTTP",
                "The request content type is not supported");
    }
}
