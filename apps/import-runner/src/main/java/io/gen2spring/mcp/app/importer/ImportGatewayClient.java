package io.gen2spring.mcp.app.importer;

import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

@FunctionalInterface
public interface ImportGatewayClient {
    Fetched fetch(ImportTarget target);

    record Fetched(byte[] source, String mediaType) {
        private static final Pattern MEDIA_TYPE = Pattern.compile(
                "[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

        public Fetched {
            source = Arrays.copyOf(Objects.requireNonNull(source, "source"), source.length);
            if (mediaType == null
                    || !MEDIA_TYPE.matcher(mediaType).matches()
                    || (!json(mediaType) && !yaml(mediaType))) {
                throw new IllegalArgumentException("Import gateway response is invalid");
            }
        }

        @Override
        public byte[] source() {
            return Arrays.copyOf(source, source.length);
        }

        String extension() {
            return json(mediaType) ? "json" : "yaml";
        }

        private static boolean json(String mediaType) {
            return "application/json".equals(mediaType) || mediaType.endsWith("+json");
        }

        private static boolean yaml(String mediaType) {
            return "application/yaml".equals(mediaType)
                    || "application/x-yaml".equals(mediaType)
                    || "text/yaml".equals(mediaType)
                    || "text/x-yaml".equals(mediaType)
                    || mediaType.endsWith("+yaml");
        }
    }
}
