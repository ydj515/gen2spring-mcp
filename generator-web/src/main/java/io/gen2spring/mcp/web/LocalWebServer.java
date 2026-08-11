package io.gen2spring.mcp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.gen2spring.mcp.application.GeneratorApplication;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalWebServer implements AutoCloseable {
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; "
            + "style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";
    private static final int CONFIGURATION_LIMIT = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final SpecificationStore specifications;
    private final GenerationJobManager jobs;
    private final RequestGuard guard;
    private final StaticAssetHandler assets;
    private final PreviewHandler previews;
    private final JobHandler jobHandler;
    private final ArtifactHandler artifactHandler;
    private final JsonHttp http;
    private final WebErrorMapper errors = new WebErrorMapper();
    private final String token;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    LocalWebServer(
            int port,
            GeneratorApplication application,
            SpecificationStore specifications,
            GenerationJobManager jobs,
            ObjectMapper json) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Web server port is invalid");
        }
        this.specifications = Objects.requireNonNull(specifications, "specifications");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.token = token();
        this.http = new JsonHttp(json);
        this.assets = new StaticAssetHandler(token);
        this.previews = new PreviewHandler(application, specifications, http.mapper());
        this.jobHandler = new JobHandler(application, specifications, jobs, http.mapper());
        this.artifactHandler = new ArtifactHandler(jobs);
        this.executor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                daemonThreads(),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
            this.server = HttpServer.create(new InetSocketAddress(loopback, port), 16);
        } catch (IOException exception) {
            executor.shutdownNow();
            jobs.close();
            specifications.close();
            throw WebErrorMapper.failure(
                    500, "WEB_BIND_FAILED", "WEB_START", "The local server could not be started");
        }
        this.guard = new RequestGuard(server.getAddress().getPort(), token);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("Local Web server cannot be started");
        }
        server.start();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + address().getPort() + "/");
    }

    String tokenForTest() {
        return token;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            jobs.close();
            specifications.close();
        }
    }

    private void handle(HttpExchange exchange) {
        try {
            securityHeaders(exchange.getResponseHeaders());
            String rawPath = exchange.getRequestURI().getRawPath();
            boolean apiRequest = rawPath.startsWith("/api/") || "/api".equals(rawPath);
            guard.require(
                    exchange.getRequestHeaders(),
                    exchange.getRemoteAddress().getAddress(),
                    exchange.getRequestMethod(),
                    apiRequest);
            if (exchange.getRequestURI().getRawQuery() != null) {
                throw WebErrorMapper.failure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
            }
            route(exchange, rawPath);
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable failure) {
            sendFailure(exchange, failure);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (assets.supports(path)) {
            requireMethod(method, "GET");
            StaticAssetHandler.Asset asset = assets.asset(path);
            http.sendBytes(exchange, 200, asset.contentType(), asset.bytes());
            return;
        }
        if ("/api/profiles".equals(path)) {
            requireMethod(method, "GET");
            http.sendJson(exchange, 200, previews.profiles());
            return;
        }
        if ("/api/specifications".equals(path)) {
            requireMethod(method, "POST");
            requireUploadContentType(exchange.getRequestHeaders());
            String name = requireSingleHeader(exchange.getRequestHeaders(), "X-Specification-Name");
            http.sendJson(exchange, 201, previews.upload(name, exchange.getRequestBody()));
            return;
        }
        String specificationId = previewSpecificationId(path);
        if (specificationId != null) {
            requireMethod(method, "POST");
            requireJsonContentType(exchange.getRequestHeaders());
            http.sendJson(exchange, 200, previews.preview(specificationId, exchange.getRequestBody()));
            return;
        }
        String jobSpecificationId = jobSpecificationId(path);
        if (jobSpecificationId != null) {
            requireMethod(method, "POST");
            requireJsonContentType(exchange.getRequestHeaders());
            http.sendJson(exchange, 202, jobHandler.start(jobSpecificationId, exchange.getRequestBody()));
            return;
        }
        JobRoute jobRoute = jobRoute(path);
        if (jobRoute != null) {
            if (jobRoute.artifact() == null && "GET".equals(method)) {
                http.sendJson(exchange, 200, jobHandler.status(jobRoute.id()));
                return;
            }
            if (jobRoute.artifact() == null && "DELETE".equals(method)) {
                jobHandler.delete(jobRoute.id());
                http.sendEmpty(exchange, 204);
                return;
            }
            if (jobRoute.artifact() != null && "GET".equals(method)) {
                ArtifactHandler.Download download = artifactHandler.download(jobRoute.id(), jobRoute.artifact());
                exchange.getResponseHeaders().set("Content-Disposition", download.contentDisposition());
                http.sendBytes(exchange, 200, download.contentType(), download.bytes());
                return;
            }
            throw WebErrorMapper.failure(405, "METHOD_NOT_ALLOWED", "HTTP",
                    "The request method is not allowed");
        }
        throw WebErrorMapper.failure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
    }

    private String previewSpecificationId(String path) {
        String prefix = "/api/specifications/";
        String suffix = "/preview";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String value = path.substring(prefix.length(), path.length() - suffix.length());
        return value.matches("[a-f0-9]{64}") ? value : null;
    }

    private String jobSpecificationId(String path) {
        String prefix = "/api/specifications/";
        String suffix = "/jobs";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String value = path.substring(prefix.length(), path.length() - suffix.length());
        return value.matches("[a-f0-9]{64}") ? value : null;
    }

    private JobRoute jobRoute(String path) {
        String prefix = "/api/jobs/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String remainder = path.substring(prefix.length());
        String[] components = remainder.split("/", -1);
        if ((components.length != 1 && components.length != 2)
                || !components[0].matches("[a-f0-9]{64}")) {
            return null;
        }
        if (components.length == 1) {
            return new JobRoute(components[0], null);
        }
        if (!List.of("archive", "manifest", "report").contains(components[1])) {
            return null;
        }
        return new JobRoute(components[0], components[1]);
    }

    private void requireMethod(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw WebErrorMapper.failure(405, "METHOD_NOT_ALLOWED", "HTTP", "The request method is not allowed");
        }
    }

    private void requireUploadContentType(Headers headers) {
        String contentType = requireSingleHeader(headers, "Content-Type");
        if (!List.of("application/octet-stream", "application/yaml", "text/yaml", "application/json")
                .contains(contentType)) {
            throw WebErrorMapper.failure(
                    415, "CONTENT_TYPE_UNSUPPORTED", "HTTP", "The request content type is not supported");
        }
    }

    private void requireJsonContentType(Headers headers) {
        String contentType = requireSingleHeader(headers, "Content-Type");
        if (!("application/json".equals(contentType)
                || "application/json; charset=utf-8".equalsIgnoreCase(contentType))) {
            throw WebErrorMapper.failure(
                    415, "CONTENT_TYPE_UNSUPPORTED", "HTTP", "The request content type is not supported");
        }
        String contentLength = headers.getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > CONFIGURATION_LIMIT) {
                    throw new BoundedBodyReader.PayloadTooLargeException();
                }
            } catch (NumberFormatException exception) {
                throw WebErrorMapper.failure(400, "REQUEST_INVALID", "HTTP", "The request is invalid");
            }
        }
    }

    private String requireSingleHeader(Headers headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw WebErrorMapper.failure(400, "REQUEST_INVALID", "HTTP", "The request is invalid");
        }
        return values.getFirst();
    }

    private void sendFailure(HttpExchange exchange, Throwable failure) {
        try {
            WebErrorMapper.WebFailure mapped = errors.map(failure);
            http.sendJson(exchange, mapped.status(), http.error(mapped));
        } catch (IOException | RuntimeException ignored) {
            // The connection is already unusable; do not expose the original failure.
        }
    }

    private void securityHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    }

    private static String token() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "gen2spring-web-request");
            thread.setDaemon(true);
            return thread;
        };
    }

    private record JobRoute(String id, String artifact) {}
}
