package io.gen2spring.mcp.app.web.presentation.local;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
final class SpecificationController {
    private final PreviewHandler previews;

    SpecificationController(PreviewHandler previews) {
        this.previews = Objects.requireNonNull(previews, "previews");
    }

    @PostMapping(path = "/api/specifications", consumes = {
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            MediaType.APPLICATION_JSON_VALUE,
            "application/yaml",
            "text/yaml"
    })
    ResponseEntity<JsonNode> upload(
            @RequestHeader("X-Specification-Name") String name,
            @RequestHeader("Content-Type") String contentType,
            HttpServletRequest request) throws IOException {
        WebContentTypes.requireUpload(contentType);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(previews.upload(name, request.getInputStream()));
    }

    @PostMapping(
            path = "/api/specifications/{id}/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    JsonNode preview(
            @PathVariable String id,
            @RequestHeader("Content-Type") String contentType,
            HttpServletRequest request) throws IOException {
        WebContentTypes.requireJson(contentType);
        return previews.preview(WebApiRoutes.requireIdentifier(id), request.getInputStream());
    }
}
