package io.gen2spring.mcp.app.web.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecificationStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void storesOneBoundedPhysicalSpecificationUnderAnOpaqueIdentifier() throws Exception {
        Path root;
        String id;
        try (SpecificationStore store = new SpecificationStore(tempDir, new SwaggerOpenApiAnalyzer())) {
            root = store.root();
            SpecificationStore.StoredSpecification stored = store.store(
                    "weather.yaml", new ByteArrayInputStream(specification().getBytes(UTF_8)));
            id = stored.id();

            assertTrue(id.matches("[a-f0-9]{64}"));
            assertEquals("Weather", stored.analysis().document().operations().getFirst().summary());
            assertTrue(Files.isRegularFile(stored.path()));
            assertFalse(Files.isSymbolicLink(stored.path()));
            assertEquals(stored, store.require(id));
            assertThrows(UnsupportedOperationException.class,
                    () -> stored.analysis().document().operations().add(null));
        }
        assertFalse(Files.exists(root));
        WebErrorMapper.WebException rejected = assertThrows(WebErrorMapper.WebException.class, () -> {
            try (SpecificationStore store = new SpecificationStore(tempDir, new SwaggerOpenApiAnalyzer())) {
                store.store("../private.yaml", new ByteArrayInputStream(specification().getBytes(UTF_8)));
            }
        });
        assertEquals("SPECIFICATION_NAME_INVALID", new WebErrorMapper().map(rejected).code());
    }

    @Test
    void evictsOnlyTheOldestUnpinnedSpecificationAtTheRetainedBound() {
        try (SpecificationStore store = new SpecificationStore(tempDir, new SwaggerOpenApiAnalyzer())) {
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                ids.add(store.store("weather.yaml", new ByteArrayInputStream(
                        specification().getBytes(UTF_8))).id());
            }
            store.retain(ids.getFirst());
            String ninth = store.store("weather.yaml", new ByteArrayInputStream(
                    specification().getBytes(UTF_8))).id();

            assertEquals(ids.getFirst(), store.require(ids.getFirst()).id());
            assertEquals(ninth, store.require(ninth).id());
            assertThrows(WebErrorMapper.WebException.class, () -> store.require(ids.get(1)));
            store.release(ids.getFirst());
        }
    }

    @Test
    void preservesRetainedSpecificationsWhenAReplacementIsRejected() {
        try (SpecificationStore store = new SpecificationStore(tempDir, new SwaggerOpenApiAnalyzer())) {
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                ids.add(store.store("weather.yaml", new ByteArrayInputStream(
                        specification().getBytes(UTF_8))).id());
            }

            assertThrows(RuntimeException.class, () -> store.store(
                    "invalid.yaml", new ByteArrayInputStream("openapi: [".getBytes(UTF_8))));

            assertEquals(ids.getFirst(), store.require(ids.getFirst()).id());
            assertEquals(8, ids.stream().filter(id -> store.require(id) != null).count());
        }
    }

    private String specification() {
        return """
                openapi: 3.0.3
                info: {title: Weather, version: 1.0.0}
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      summary: Weather
                      responses:
                        '200': {description: Success}
                """;
    }
}
