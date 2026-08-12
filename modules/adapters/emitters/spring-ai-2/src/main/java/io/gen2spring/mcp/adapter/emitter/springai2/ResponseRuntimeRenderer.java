package io.gen2spring.mcp.adapter.emitter.springai2;

import java.util.LinkedHashMap;
import java.util.Map;

final class ResponseRuntimeRenderer {
    Map<String, String> render(String packageName, String packagePath) {
        Map<String, String> sources = new LinkedHashMap<>();
        String runtimePath = "src/main/java/" + packagePath + "/runtime/";
        sources.put(runtimePath + "ResponseNormalizationPolicy.java", responseNormalizationPolicy(packageName));
        sources.put(runtimePath + "OperationOutcome.java", operationOutcome(packageName));
        sources.put(runtimePath + "NormalizedSuccess.java", normalizedSuccess(packageName));
        sources.put(runtimePath + "ProviderErrorCategory.java", providerErrorCategory(packageName));
        sources.put(runtimePath + "ProviderError.java", providerError(packageName));
        sources.put(runtimePath + "ProviderErrorException.java", providerErrorException(packageName));
        sources.put(runtimePath + "ResponseNormalizer.java", responseNormalizer(packageName));
        return sources;
    }

    private String responseNormalizationPolicy(String packageName) {
        return """
                package %s.runtime;

                import java.util.List;
                import tools.jackson.databind.JsonNode;

                public record ResponseNormalizationPolicy(
                        String dataPointer,
                        String successCodePointer,
                        List<JsonNode> successValues,
                        String errorMessagePointer,
                        String totalCountPointer) {
                    public ResponseNormalizationPolicy {
                        successValues = List.copyOf(successValues);
                    }
                }
                """.formatted(packageName);
    }

    private String operationOutcome(String packageName) {
        return """
                package %s.runtime;

                public sealed interface OperationOutcome permits NormalizedSuccess, ProviderError {}
                """.formatted(packageName);
    }

    private String normalizedSuccess(String packageName) {
        return """
                package %s.runtime;

                import java.util.Objects;
                import tools.jackson.databind.JsonNode;

                public record NormalizedSuccess(JsonNode payload) implements OperationOutcome {
                    public NormalizedSuccess {
                        Objects.requireNonNull(payload, "payload");
                    }
                }
                """.formatted(packageName);
    }

    private String providerErrorCategory(String packageName) {
        return """
                package %s.runtime;

                public enum ProviderErrorCategory {
                    PROVIDER_BUSINESS,
                    UPSTREAM_CLIENT,
                    UPSTREAM_SERVER,
                    UPSTREAM_TIMEOUT,
                    UPSTREAM_UNAVAILABLE,
                    UPSTREAM_PROTOCOL,
                    LOCAL_RESOURCE
                }
                """.formatted(packageName);
    }

    private String providerError(String packageName) {
        return """
                package %s.runtime;

                import java.util.Objects;
                import tools.jackson.databind.JsonNode;

                public record ProviderError(
                        JsonNode payload,
                        ProviderErrorCategory category,
                        Integer httpStatus) implements OperationOutcome {
                    public ProviderError(JsonNode payload) {
                        this(payload, ProviderErrorCategory.UPSTREAM_PROTOCOL, null);
                    }

                    public ProviderError {
                        Objects.requireNonNull(payload, "payload");
                        Objects.requireNonNull(category, "category");
                    }
                }
                """.formatted(packageName);
    }

    private String providerErrorException(String packageName) {
        return """
                package %s.runtime;

                import java.util.Objects;

                public final class ProviderErrorException extends RuntimeException {
                    private final ProviderError error;

                    public ProviderErrorException(ProviderError error) {
                        super("Generated provider request failed");
                        this.error = Objects.requireNonNull(error, "error");
                    }

                    public ProviderError error() {
                        return error;
                    }
                }
                """.formatted(packageName);
    }

    private String responseNormalizer(String packageName) {
        return """
                package %s.runtime;

                import java.security.SecureRandom;
                import java.util.ArrayList;
                import java.util.Comparator;
                import java.util.List;
                import java.util.Objects;
                import java.util.regex.Pattern;
                import org.springframework.http.MediaType;
                import tools.jackson.core.JacksonException;
                import tools.jackson.databind.DeserializationFeature;
                import tools.jackson.databind.JsonNode;
                import tools.jackson.databind.json.JsonMapper;
                import tools.jackson.databind.node.JsonNodeFactory;
                import tools.jackson.databind.node.NullNode;
                import tools.jackson.databind.node.ObjectNode;

                public final class ResponseNormalizer {
                    private static final String UNSAFE_MESSAGE = "Provider returned an unsafe error message";
                    private static final List<String> SENSITIVE_NAMES = List.of(
                            "authorization",
                            "api key",
                            "api_key",
                            "apikey",
                            "servicekey",
                            "clientsecret",
                            "cookie");

                    private final JsonMapper jsonMapper = JsonMapper.builder()
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                            .build();
                    private final SecureRandom secureRandom = new SecureRandom();
                    private final RuntimeTelemetry runtimeTelemetry;

                    public ResponseNormalizer() {
                        this.runtimeTelemetry = null;
                    }

                    public ResponseNormalizer(RuntimeTelemetry runtimeTelemetry) {
                        this.runtimeTelemetry = Objects.requireNonNull(runtimeTelemetry, "runtimeTelemetry");
                    }

                    public OperationOutcome normalize(
                            OperationDefinition operation,
                            int status,
                            MediaType contentType,
                            byte[] body,
                            List<String> secretNames,
                            List<String> secretValues) {
                        Objects.requireNonNull(operation, "operation");
                        Objects.requireNonNull(body, "body");
                        ProviderErrorCategory statusCategory = statusCategory(status);
                        if (statusCategory != null) {
                            return statusError(operation, statusCategory, status, contentType, body,
                                    secretNames, secretValues);
                        }

                        ResponseNormalizationPolicy policy = operation.responseNormalization();
                        if (body.length == 0) {
                            if (policy == null) {
                                return new NormalizedSuccess(NullNode.getInstance());
                            }
                            if (policy.dataPointer() == null
                                    && policy.successCodePointer() == null
                                    && policy.errorMessagePointer() == null
                                    && policy.totalCountPointer() == null) {
                                return new NormalizedSuccess(successEnvelope(
                                        NullNode.getInstance(), null, null, null, policy,
                                        secretNames, secretValues));
                            }
                            return error(operation, ProviderErrorCategory.UPSTREAM_PROTOCOL, status,
                                    null, null, secretNames, secretValues);
                        }

                        JsonNode providerCode = null;
                        String providerMessage = null;
                        try {
                            JsonNode root = parseJson(contentType, body);
                            if (policy == null) {
                                return new NormalizedSuccess(root);
                            }

                            if (policy.successCodePointer() != null) {
                                providerCode = requiredScalar(root, policy.successCodePointer());
                            }
                            if (policy.errorMessagePointer() != null) {
                                JsonNode message = requiredAt(root, policy.errorMessagePointer());
                                if (!message.isTextual()) {
                                    throw new ProtocolMismatch();
                                }
                                providerMessage = message.stringValue();
                            }
                            if (providerCode != null && !matches(providerCode, policy.successValues())) {
                                return error(operation, ProviderErrorCategory.PROVIDER_BUSINESS, status,
                                        providerCode, providerMessage, secretNames, secretValues);
                            }

                            JsonNode data = policy.dataPointer() == null
                                    ? root : requiredAt(root, policy.dataPointer());
                            JsonNode totalCount = null;
                            if (policy.totalCountPointer() != null) {
                                JsonNode count = requiredAt(root, policy.totalCountPointer());
                                if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0) {
                                    throw new ProtocolMismatch();
                                }
                                totalCount = count;
                            }
                            return new NormalizedSuccess(successEnvelope(
                                    data, totalCount, providerCode, providerMessage, policy,
                                    secretNames, secretValues));
                        } catch (JacksonException | ProtocolMismatch failure) {
                            return error(operation, ProviderErrorCategory.UPSTREAM_PROTOCOL, status,
                                    providerCode, providerMessage, secretNames, secretValues);
                        }
                    }

                    public ProviderError error(
                            OperationDefinition operation,
                            ProviderErrorCategory category,
                            Integer status,
                            JsonNode providerCode,
                            String providerMessage,
                            List<String> secretNames,
                            List<String> secretValues) {
                        Objects.requireNonNull(operation, "operation");
                        Objects.requireNonNull(category, "category");
                        ObjectNode details = JsonNodeFactory.instance.objectNode();
                        details.put("category", category.name());
                        details.set("providerCode", providerCode == null ? NullNode.getInstance() : providerCode);
                        String safeMessage = sanitize(providerMessage, secretNames, secretValues);
                        if (safeMessage == null) {
                            details.putNull("providerMessage");
                        } else {
                            details.put("providerMessage", safeMessage);
                        }
                        details.put("retryable", retryable(category, status));
                        if (status == null) {
                            details.putNull("httpStatus");
                        } else {
                            details.put("httpStatus", status);
                        }
                        details.put("operationId", operation.operationId());
                        details.put("traceId", runtimeTelemetry == null
                                ? traceId() : runtimeTelemetry.currentTraceIdOrFallback());
                        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
                        envelope.set("error", details);
                        return new ProviderError(envelope, category, status);
                    }

                    private ProviderError statusError(
                            OperationDefinition operation,
                            ProviderErrorCategory category,
                            int status,
                            MediaType contentType,
                            byte[] body,
                            List<String> secretNames,
                            List<String> secretValues) {
                        JsonNode providerCode = null;
                        String providerMessage = null;
                        ResponseNormalizationPolicy policy = operation.responseNormalization();
                        if (policy != null && body.length > 0) {
                            try {
                                JsonNode root = parseJson(contentType, body);
                                if (policy.successCodePointer() != null) {
                                    providerCode = requiredScalar(root, policy.successCodePointer());
                                }
                                if (policy.errorMessagePointer() != null) {
                                    JsonNode message = requiredAt(root, policy.errorMessagePointer());
                                    if (!message.isTextual()) {
                                        throw new ProtocolMismatch();
                                    }
                                    providerMessage = message.stringValue();
                                }
                            } catch (JacksonException | ProtocolMismatch ignored) {
                                providerCode = null;
                                providerMessage = null;
                            }
                        }
                        return error(operation, category, status, providerCode, providerMessage,
                                secretNames, secretValues);
                    }

                    private JsonNode parseJson(MediaType contentType, byte[] body) throws JacksonException {
                        if (!isJson(contentType)) {
                            throw new ProtocolMismatch();
                        }
                        JsonNode root = jsonMapper.readTree(body);
                        if (root == null || root.isMissingNode()) {
                            throw new ProtocolMismatch();
                        }
                        return root;
                    }

                    private boolean isJson(MediaType contentType) {
                        if (contentType == null || !"application".equalsIgnoreCase(contentType.getType())) {
                            return false;
                        }
                        String subtype = contentType.getSubtype();
                        return "json".equalsIgnoreCase(subtype)
                                || subtype.toLowerCase(java.util.Locale.ROOT).endsWith("+json");
                    }

                    private JsonNode requiredScalar(JsonNode root, String pointer) {
                        JsonNode value = requiredAt(root, pointer);
                        if (!(value.isTextual() || value.isNumber() || value.isBoolean())) {
                            throw new ProtocolMismatch();
                        }
                        return value;
                    }

                    private JsonNode requiredAt(JsonNode root, String pointer) {
                        JsonNode value = root.at(pointer);
                        if (value.isMissingNode()) {
                            throw new ProtocolMismatch();
                        }
                        return value;
                    }

                    private boolean matches(JsonNode observed, List<JsonNode> expectedValues) {
                        for (JsonNode expected : expectedValues) {
                            if (observed.isNumber() && expected.isNumber()
                                    && observed.decimalValue().compareTo(expected.decimalValue()) == 0) {
                                return true;
                            }
                            if (observed.isTextual() && expected.isTextual()
                                    && observed.stringValue().equals(expected.stringValue())) {
                                return true;
                            }
                            if (observed.isBoolean() && expected.isBoolean()
                                    && observed.booleanValue() == expected.booleanValue()) {
                                return true;
                            }
                        }
                        return false;
                    }

                    private JsonNode successEnvelope(
                            JsonNode data,
                            JsonNode totalCount,
                            JsonNode providerCode,
                            String providerMessage,
                            ResponseNormalizationPolicy policy,
                            List<String> secretNames,
                            List<String> secretValues) {
                        ObjectNode result = JsonNodeFactory.instance.objectNode();
                        result.set("data", data);
                        if (policy.totalCountPointer() != null) {
                            ObjectNode page = JsonNodeFactory.instance.objectNode();
                            page.set("totalCount", totalCount);
                            result.set("page", page);
                        }
                        if (policy.successCodePointer() != null || policy.errorMessagePointer() != null) {
                            ObjectNode provider = JsonNodeFactory.instance.objectNode();
                            if (policy.successCodePointer() != null) {
                                provider.set("code", providerCode);
                            }
                            if (policy.errorMessagePointer() != null) {
                                String safeMessage = sanitize(providerMessage, secretNames, secretValues);
                                if (safeMessage == null) {
                                    provider.putNull("message");
                                } else {
                                    provider.put("message", safeMessage);
                                }
                            }
                            result.set("provider", provider);
                        }
                        return result;
                    }

                    private ProviderErrorCategory statusCategory(int status) {
                        if (status >= 200 && status < 300) {
                            return null;
                        }
                        if (status >= 400 && status < 500) {
                            return ProviderErrorCategory.UPSTREAM_CLIENT;
                        }
                        if (status >= 500 && status < 600) {
                            return ProviderErrorCategory.UPSTREAM_SERVER;
                        }
                        return ProviderErrorCategory.UPSTREAM_PROTOCOL;
                    }

                    private boolean retryable(ProviderErrorCategory category, Integer status) {
                        return switch (category) {
                            case UPSTREAM_SERVER, UPSTREAM_TIMEOUT, UPSTREAM_UNAVAILABLE -> true;
                            case UPSTREAM_CLIENT -> status != null
                                    && (status == 408 || status == 425 || status == 429);
                            default -> false;
                        };
                    }

                    private String sanitize(
                            String message, List<String> secretNames, List<String> secretValues) {
                        if (message == null) {
                            return null;
                        }
                        try {
                            if (message.codePoints().anyMatch(Character::isISOControl)) {
                                return UNSAFE_MESSAGE;
                            }
                            String safe = message;
                            safe = mask(safe, secretValues, false);
                            List<String> names = new ArrayList<>(SENSITIVE_NAMES);
                            if (secretNames != null) {
                                names.addAll(secretNames);
                            }
                            safe = mask(safe, names, true);
                            int codePoints = safe.codePointCount(0, safe.length());
                            if (codePoints > 512) {
                                safe = safe.substring(0, safe.offsetByCodePoints(0, 512));
                            }
                            return safe;
                        } catch (RuntimeException failure) {
                            return UNSAFE_MESSAGE;
                        }
                    }

                    private String mask(String source, List<String> values, boolean ignoreCase) {
                        if (values == null) {
                            return source;
                        }
                        String result = source;
                        List<String> ordered = values.stream()
                                .filter(value -> value != null && !value.isBlank())
                                .distinct()
                                .sorted(Comparator.comparingInt(String::length).reversed())
                                .toList();
                        for (String value : ordered) {
                            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                            result = Pattern.compile(Pattern.quote(value), flags)
                                    .matcher(result)
                                    .replaceAll("***");
                        }
                        return result;
                    }

                    private String traceId() {
                        byte[] bytes = new byte[16];
                        secureRandom.nextBytes(bytes);
                        char[] result = new char[32];
                        char[] hex = "0123456789abcdef".toCharArray();
                        for (int index = 0; index < bytes.length; index++) {
                            int value = bytes[index] & 0xff;
                            result[index * 2] = hex[value >>> 4];
                            result[index * 2 + 1] = hex[value & 0x0f];
                        }
                        return new String(result);
                    }

                    private static final class ProtocolMismatch extends RuntimeException {
                        private ProtocolMismatch() {
                            super("Generated provider response did not match its contract");
                        }
                    }
                }
                """.formatted(packageName);
    }
}
