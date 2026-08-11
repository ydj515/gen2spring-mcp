package io.gen2spring.mcp.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

final class StaticAssetHandler {
    private static final int MAX_ASSET_BYTES = 512 * 1024;
    private static final Map<String, AssetDefinition> ASSETS = Map.of(
            "/", new AssetDefinition("/web/index.html", "text/html; charset=utf-8", true),
            "/styles.css", new AssetDefinition("/web/styles.css", "text/css; charset=utf-8", false),
            "/app.js", new AssetDefinition("/web/app.js", "text/javascript; charset=utf-8", false));

    private final String token;

    StaticAssetHandler(String token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    boolean supports(String path) {
        return ASSETS.containsKey(path);
    }

    Asset asset(String path) {
        AssetDefinition definition = ASSETS.get(path);
        if (definition == null) {
            throw WebErrorMapper.failure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
        }
        try (InputStream input = StaticAssetHandler.class.getResourceAsStream(definition.resource())) {
            if (input == null) {
                throw new IOException("Asset is absent");
            }
            byte[] bytes = new BoundedBodyReader(MAX_ASSET_BYTES).read(input);
            if (definition.injectToken()) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                if (!html.contains("__GEN2SPRING_TOKEN__")) {
                    throw new IOException("Token placeholder is absent");
                }
                bytes = html.replace("__GEN2SPRING_TOKEN__", token).getBytes(StandardCharsets.UTF_8);
            }
            return new Asset(bytes, definition.contentType());
        } catch (BoundedBodyReader.BodyReadException | IOException exception) {
            throw WebErrorMapper.failure(500, "ASSET_READ_FAILED", "WEB", "The local asset could not be read");
        }
    }

    record Asset(byte[] bytes, String contentType) {
        Asset {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record AssetDefinition(String resource, String contentType, boolean injectToken) {}
}
