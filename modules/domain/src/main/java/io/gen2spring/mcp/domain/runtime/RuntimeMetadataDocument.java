package io.gen2spring.mcp.domain.runtime;

import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record RuntimeMetadataDocument(
        String metadataVersion,
        String specificationChecksum,
        List<RuntimeTool> tools) {
    public static final String VERSION = "1.0";
    public static final String FILE_NAME = "RUNTIME_METADATA.json";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public RuntimeMetadataDocument {
        if (!VERSION.equals(metadataVersion) || !validHash(specificationChecksum) || tools == null) {
            throw new IllegalArgumentException("Runtime metadata identity is invalid");
        }
        List<RuntimeTool> sorted = tools.stream()
                .map(tool -> Objects.requireNonNull(tool, "tool"))
                .sorted(Comparator.comparing(RuntimeTool::name).thenComparing(RuntimeTool::operationId))
                .toList();
        if (sorted.stream().map(RuntimeTool::name).distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("Runtime Tool names must be unique");
        }
        validateCredentialSlots(sorted);
        tools = sorted;
    }

    private static boolean validHash(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static void validateCredentialSlots(List<RuntimeTool> tools) {
        Map<String, CredentialTarget> targets = new HashMap<>();
        for (RuntimeTool tool : tools) {
            for (RuntimeCredential credential : tool.credentials()) {
                CredentialTarget target = CredentialTarget.from(credential);
                CredentialTarget previous = targets.putIfAbsent(credential.credentialSlot(), target);
                if (previous != null && !previous.equals(target)) {
                    throw new IllegalArgumentException("Runtime credential slots must target one parameter");
                }
            }
        }
    }

    public record RuntimeTool(
            String operationId,
            String name,
            String description,
            Map<String, Object> inputSchema,
            String outputKind,
            Map<String, Object> outputSchema,
            RuntimeHttp http,
            ResponseNormalizationPolicy responseNormalization,
            RetryPolicy retry,
            PaginationPolicy pagination,
            List<RuntimeCredential> credentials) {
        public RuntimeTool {
            requireText(operationId, "operationId");
            requireText(name, "name");
            requireText(description, "description");
            if (!"GENERIC_JSON".equals(outputKind) && !"TYPED_DTO".equals(outputKind)) {
                throw new IllegalArgumentException("Runtime Tool output kind is invalid");
            }
            inputSchema = immutableJsonObject(inputSchema);
            outputSchema = immutableJsonObject(outputSchema);
            Objects.requireNonNull(http, "http");
            List<RuntimeCredential> copied = credentials == null ? List.of() : List.copyOf(credentials);
            credentials = copied.stream()
                    .map(credential -> Objects.requireNonNull(credential, "credential"))
                    .sorted(Comparator.comparing(RuntimeCredential::credentialSlot)
                            .thenComparing(credential -> credential.targetLocation().name())
                            .thenComparing(RuntimeCredential::canonicalTargetName))
                    .toList();
        }
    }

    public record RuntimeHttp(
            HttpMethod method,
            String baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectRequestBody,
            boolean requestBodyRequired) {
        public RuntimeHttp {
            Objects.requireNonNull(method, "method");
            URI uri = parseBaseUrl(baseUrl);
            boolean absoluteHttp = uri.isAbsolute()
                    && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
            boolean pathAbsoluteRelative = !uri.isAbsolute()
                    && uri.getRawAuthority() == null
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/");
            if (!uri.toASCIIString().equals(baseUrl)
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || !absoluteHttp && !pathAbsoluteRelative) {
                throw new IllegalArgumentException("Runtime HTTP base URL is invalid");
            }
            if (path == null || path.isBlank() || !path.startsWith("/") || containsControl(path)) {
                throw new IllegalArgumentException("Runtime HTTP path is invalid");
            }
            List<ParameterBinding> copied = bindings == null ? List.of() : List.copyOf(bindings);
            copied.forEach(RuntimeMetadataDocument::validateBinding);
            bindings = copied.stream()
                    .sorted(Comparator.comparing(ParameterBinding::sourceName)
                            .thenComparing(binding -> binding.targetLocation().name())
                            .thenComparing(RuntimeMetadataDocument::canonicalBindingTarget))
                    .toList();
        }

        private static URI parseBaseUrl(String value) {
            try {
                return URI.create(value);
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Runtime HTTP base URL is invalid", failure);
            }
        }
    }

    public record RuntimeCredential(
            String credentialSlot,
            ParameterLocation targetLocation,
            String targetName,
            boolean required) {
        private static final Pattern SLOT = Pattern.compile("[a-z][a-z0-9_-]{0,127}");

        public RuntimeCredential {
            if (credentialSlot == null || !SLOT.matcher(credentialSlot).matches()) {
                throw new IllegalArgumentException("Runtime credential slot is invalid");
            }
            Objects.requireNonNull(targetLocation, "targetLocation");
            requireText(targetName, "targetName");
        }

        private String canonicalTargetName() {
            return targetLocation == ParameterLocation.HEADER
                    ? targetName.toLowerCase(Locale.ROOT) : targetName;
        }
    }

    private record CredentialTarget(ParameterLocation location, String name) {
        private static CredentialTarget from(RuntimeCredential credential) {
            return new CredentialTarget(credential.targetLocation(), credential.canonicalTargetName());
        }
    }

    private static Map<String, Object> immutableJsonObject(Map<String, Object> source) {
        if (source == null) {
            throw new IllegalArgumentException("Runtime JSON schema is invalid");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || containsControl(key)) {
                throw new IllegalArgumentException("Runtime JSON schema is invalid");
            }
            copy.put(key, immutableJsonValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Runtime JSON schema is invalid");
                }
                typed.put(stringKey, nested);
            });
            return immutableJsonObject(typed);
        }
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(item -> copied.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copied);
        }
        throw new IllegalArgumentException("Runtime JSON schema is invalid");
    }

    private static void validateBinding(ParameterBinding binding) {
        Objects.requireNonNull(binding, "binding");
        requireText(binding.sourceName(), "sourceName");
        Objects.requireNonNull(binding.targetLocation(), "targetLocation");
        requireText(binding.targetName(), "targetName");
    }

    private static String canonicalBindingTarget(ParameterBinding binding) {
        return binding.targetLocation() == ParameterLocation.HEADER
                ? binding.targetName().toLowerCase(Locale.ROOT) : binding.targetName();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || containsControl(value)) {
            throw new IllegalArgumentException("Runtime metadata " + name + " is invalid");
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
