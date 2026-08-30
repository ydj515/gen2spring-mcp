package io.gen2spring.mcp.app.fetch.api;

import io.gen2spring.mcp.app.fetch.fetching.BoundedFetcher;
import io.gen2spring.mcp.app.fetch.fetching.FetchFailure;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class FetchController {
    private static final String CERTIFICATES = "jakarta.servlet.request.X509Certificate";

    private final BoundedFetcher fetcher;

    public FetchController(BoundedFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @PostMapping(path = "/internal/fetch", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> fetch(@RequestBody FetchRequest request, HttpServletRequest servletRequest) {
        if (request == null) {
            throw new FetchFailure();
        }
        Object certificateAttribute = servletRequest.getAttribute(CERTIFICATES);
        if (!(certificateAttribute instanceof X509Certificate[] certificates) || certificates.length == 0) {
            throw new FetchFailure();
        }
        BoundedFetcher.FetchResult result = fetcher.fetch(ImportTarget.parse(request.target()));
        return ResponseEntity.ok()
                .header("X-Gen2Spring-Upstream-Status", Integer.toString(result.status()))
                .header(HttpHeaders.CONTENT_TYPE, result.mediaType())
                .body(result.body());
    }

    @ExceptionHandler({FetchFailure.class, IllegalArgumentException.class})
    ResponseEntity<FailureBody> failure() {
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FailureBody("FETCH_REJECTED", "URL import fetch failed"));
    }

    public record FetchRequest(String target) {}

    public record FailureBody(String code, String message) {}
}
