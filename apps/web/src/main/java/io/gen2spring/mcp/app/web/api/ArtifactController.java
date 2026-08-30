package io.gen2spring.mcp.app.web.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
final class ArtifactController {
    private static final List<String> ARTIFACTS = List.of("archive", "manifest", "report");
    private final ArtifactHandler artifacts;

    ArtifactController(ArtifactHandler artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @GetMapping("/api/jobs/{id}/{name:archive|manifest|report}")
    void download(
            @PathVariable String id,
            @PathVariable String name,
            HttpServletResponse response) throws IOException {
        WebApiRoutes.requireIdentifier(id);
        if (!ARTIFACTS.contains(name)) {
            throw WebErrorMapper.failure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
        }
        ArtifactHandler.Download download = artifacts.download(id, name);
        byte[] bytes = download.bytes();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader(HttpHeaders.CONTENT_TYPE, download.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, download.contentDisposition());
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
