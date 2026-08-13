package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_PARSE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_REFERENCE_UNRESOLVED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ExternalReferenceGuard {
    private static final String SOURCE_LOAD = "SOURCE_LOAD";
    private final ObjectMapper jsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final ObjectMapper yamlMapper = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public Preflight verify(byte[] bytes, String extension) {
        try {
            JsonNode root = mapperFor(extension).readTree(bytes);
            if (root == null) {
                throw GeneratorException.user(SPEC_PARSE_FAILED, SOURCE_LOAD, "Specification is empty");
            }
            verifyNode(root);
            return new Preflight(recursiveSchemaOperations(root));
        } catch (JsonProcessingException exception) {
            throw GeneratorException.user(SPEC_PARSE_FAILED, SOURCE_LOAD,
                    "Specification is not valid " + extension.toUpperCase() + "", exception);
        } catch (IOException exception) {
            throw GeneratorException.user(SPEC_PARSE_FAILED, SOURCE_LOAD, "Specification could not be parsed", exception);
        }
    }

    private ObjectMapper mapperFor(String extension) {
        return extension.equals("json") ? jsonMapper : yamlMapper;
    }

    private void verifyNode(JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (field.getKey().equals("$ref")
                        && (!field.getValue().isTextual() || !field.getValue().textValue().startsWith("#/"))) {
                    throw GeneratorException.user(SPEC_REFERENCE_UNRESOLVED, SOURCE_LOAD,
                            "Only local $ref values beginning with #/ are supported");
                }
                verifyNode(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                verifyNode(child);
            }
        }
    }

    private Set<String> recursiveSchemaOperations(JsonNode root) {
        Map<String, Set<String>> dependencies = schemaDependencies(root);
        Set<String> recursiveSchemas = findRecursiveSchemas(dependencies);
        if (recursiveSchemas.isEmpty()) {
            return Set.of();
        }

        Set<String> operations = new HashSet<>();
        JsonNode paths = root.path("paths");
        paths.properties().forEach(path -> {
            Set<String> pathReferences = schemaReferences(path.getValue().path("parameters"));
            path.getValue().properties().forEach(method -> {
                if (isHttpMethod(method.getKey())) {
                    Set<String> references = new HashSet<>(pathReferences);
                    references.addAll(schemaReferences(method.getValue()));
                    if (references.stream().anyMatch(reference -> reachesRecursiveSchema(
                            reference, dependencies, recursiveSchemas, new HashSet<>()))) {
                        operations.add(operationKey(path.getKey(), method.getKey()));
                    }
                }
            });
        });
        return Set.copyOf(operations);
    }

    private Map<String, Set<String>> schemaDependencies(JsonNode root) {
        Map<String, Set<String>> dependencies = new HashMap<>();
        root.path("components").path("schemas").properties().forEach(schema ->
                dependencies.put(schema.getKey(), schemaReferences(schema.getValue())));
        return dependencies;
    }

    private Set<String> schemaReferences(JsonNode node) {
        Set<String> references = new HashSet<>();
        collectSchemaReferences(node, references);
        return references;
    }

    private void collectSchemaReferences(JsonNode node, Set<String> references) {
        if (node.isObject()) {
            node.properties().forEach(field -> {
                if (field.getKey().equals("$ref") && field.getValue().isTextual()) {
                    String value = field.getValue().textValue();
                    if (value.startsWith("#/components/schemas/")) {
                        references.add(value.substring("#/components/schemas/".length()));
                    }
                }
                collectSchemaReferences(field.getValue(), references);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectSchemaReferences(child, references));
        }
    }

    private Set<String> findRecursiveSchemas(Map<String, Set<String>> dependencies) {
        Set<String> recursiveSchemas = new HashSet<>();
        dependencies.keySet().forEach(schema -> {
            if (dependencies.getOrDefault(schema, Set.of()).stream()
                    .anyMatch(reference -> reaches(reference, schema, dependencies, new HashSet<>()))) {
                recursiveSchemas.add(schema);
            }
        });
        return recursiveSchemas;
    }

    private boolean reaches(String current, String target, Map<String, Set<String>> dependencies, Set<String> visited) {
        if (current.equals(target)) {
            return true;
        }
        return visited.add(current) && dependencies.getOrDefault(current, Set.of()).stream()
                .anyMatch(next -> reaches(next, target, dependencies, visited));
    }

    private boolean reachesRecursiveSchema(
            String current,
            Map<String, Set<String>> dependencies,
            Set<String> recursiveSchemas,
            Set<String> visited) {
        return recursiveSchemas.contains(current) || visited.add(current)
                && dependencies.getOrDefault(current, Set.of()).stream()
                .anyMatch(next -> reachesRecursiveSchema(next, dependencies, recursiveSchemas, visited));
    }

    private boolean isHttpMethod(String method) {
        return switch (method) {
            case "get", "post", "put", "patch", "delete", "head", "options", "trace" -> true;
            default -> false;
        };
    }

    private String operationKey(String path, String method) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    public record Preflight(Set<String> operationsWithRecursiveSchemas) {
        public boolean hasRecursiveSchema(String path, String method) {
            return operationsWithRecursiveSchemas.contains(method + " " + path);
        }
    }
}
