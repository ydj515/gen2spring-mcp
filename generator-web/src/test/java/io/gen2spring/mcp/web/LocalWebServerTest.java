package io.gen2spring.mcp.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class LocalWebServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void servesTheSecureShellProfilesUploadAndPreviewJourney() throws Exception {
        try (LocalWebServer server = WebApplicationFactory.create(0)) {
            server.start();
            assertEquals("127.0.0.1", server.address().getAddress().getHostAddress());

            HttpResponse<String> root = send(server.uri(), "/", "GET", null, null, null);
            assertEquals(200, root.statusCode());
            assertEquals("no-store", root.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", root.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertEquals("DENY", root.headers().firstValue("X-Frame-Options").orElseThrow());
            assertEquals("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
                            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
                    root.headers().firstValue("Content-Security-Policy").orElseThrow());
            assertTrue(root.body().contains("generator-api-token"));
            assertTrue(root.body().contains(server.tokenForTest()));
            assertTrue(rawRequest(server, "localhost:" + server.address().getPort())
                    .startsWith("HTTP/1.1 403"));

            HttpResponse<String> unauthorized = send(
                    server.uri(), "/api/profiles", "GET", null, "wrong-token", null);
            assertEquals(403, unauthorized.statusCode());
            assertFalse(unauthorized.body().contains("wrong-token"));

            HttpResponse<String> profiles = send(
                    server.uri(), "/api/profiles", "GET", null, server.tokenForTest(), null);
            JsonNode profileJson = JSON.readTree(profiles.body());
            assertEquals(200, profiles.statusCode());
            assertEquals(4, profileJson.path("profiles").size());
            assertEquals("spring-ai-1.1-java17-mvc-streamable",
                    profileJson.path("profiles").get(0).path("id").textValue());

            HttpResponse<String> upload = send(
                    server.uri(), "/api/specifications", "POST", specification(), server.tokenForTest(),
                    "weather.yaml");
            assertEquals(201, upload.statusCode(), upload.body());
            JsonNode uploaded = JSON.readTree(upload.body());
            assertEquals("getForecast", uploaded.path("operations").get(0).path("operationId").textValue());
            String specificationId = uploaded.path("id").textValue();

            HttpResponse<String> preview = send(
                    server.uri(), "/api/specifications/" + specificationId + "/preview",
                    "POST", configuration(), server.tokenForTest(), null);
            assertEquals(200, preview.statusCode(), preview.body());
            JsonNode previewJson = JSON.readTree(preview.body());
            assertEquals("weather_get_forecast", previewJson.path("tools").get(0).path("name").textValue());
            assertEquals("spring-ai-2.0-java21-mvc-streamable",
                    previewJson.path("profile").path("id").textValue());
            assertFalse(preview.body().contains("representative-private-value"));

            HttpResponse<String> invalidPreview = send(
                    server.uri(), "/api/specifications/" + specificationId + "/preview",
                    "POST", "{\"private-marker\":\"private-value\"}", server.tokenForTest(), null);
            assertEquals(400, invalidPreview.statusCode());
            assertFalse(invalidPreview.body().contains("private-marker"));
            assertFalse(invalidPreview.body().contains("private-value"));

            HttpResponse<String> oversized = send(
                    server.uri(), "/api/specifications", "POST",
                    "a".repeat(SpecificationStore.MAX_SPECIFICATION_BYTES + 1),
                    server.tokenForTest(), "large.yaml");
            assertEquals(413, oversized.statusCode());

            HttpResponse<String> unsafeName = send(
                    server.uri(), "/api/specifications", "POST", specification(),
                    server.tokenForTest(), "../private-marker.yaml");
            assertEquals(400, unsafeName.statusCode());
            assertFalse(unsafeName.body().contains("private-marker"));
        }
    }

    @Test
    void acceptsOnlyTheBoundedPortArgumentForm() {
        assertEquals(0, WebArguments.parse(new String[0]).port());
        assertEquals(65535, WebArguments.parse(new String[] {"--port", "65535"}).port());
        assertThrows(IllegalArgumentException.class,
                () -> WebArguments.parse(new String[] {"--port", "65536"}));
        assertThrows(IllegalArgumentException.class,
                () -> WebArguments.parse(new String[] {"--bind", "127.0.0.1"}));
    }

    private String rawRequest(LocalWebServer server, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.address().getPort())) {
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host
                    + "\r\nConnection: close\r\n\r\n").getBytes(UTF_8));
            socket.getOutputStream().flush();
            try (InputStream input = socket.getInputStream()) {
                return new String(input.readAllBytes(), UTF_8);
            }
        }
    }

    private HttpResponse<String> send(
            URI base,
            String path,
            String method,
            String body,
            String token,
            String specificationName) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path));
        if (token != null) {
            request.header("X-Gen2Spring-Token", token);
        }
        if (specificationName != null) {
            request.header("X-Specification-Name", specificationName);
            request.header("Content-Type", "application/octet-stream");
        } else if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if ("POST".equals(method)) {
            request.header("Origin", base.toString().replaceAll("/$", ""));
            request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, UTF_8));
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    private String specification() {
        return """
                openapi: 3.0.3
                info: {title: Weather, version: 1.0.0}
                servers: [{url: https://weather.example.test}]
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      summary: Get forecast
                      parameters:
                        - name: city
                          in: query
                          required: true
                          schema: {type: string}
                      responses:
                        '200': {description: Success}
                """;
    }

    private String configuration() {
        return """
                {
                  "project": {"groupId":"com.example","artifactId":"weather-mcp-server",
                    "packageName":"com.example.weather"},
                  "provider":"weather","domain":"forecast",
                  "targetProfileId":"spring-ai-2.0-java21-mvc-streamable",
                  "validationLevel":"MCP_PROTOCOL",
                  "validation":{"toolCall":{"operationId":"getForecast",
                    "arguments":{"city":"representative-private-value"}}},
                  "operations":[{"operationId":"getForecast","enabled":true,
                    "toolName":"weather_get_forecast","toolDescription":"Get forecast","parameters":{}}]
                }
                """;
    }
}
