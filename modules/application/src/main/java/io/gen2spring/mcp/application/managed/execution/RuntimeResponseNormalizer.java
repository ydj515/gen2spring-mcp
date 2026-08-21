package io.gen2spring.mcp.application.managed.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public final class RuntimeResponseNormalizer {
    private static final String UNSAFE_MESSAGE = "Provider returned an unsafe error message";
    private static final List<String> SENSITIVE_NAMES = List.of(
            "authorization", "api key", "api_key", "apikey", "servicekey", "clientsecret", "cookie");
    private static final List<Pattern> SENSITIVE_PATTERNS = SENSITIVE_NAMES.stream()
            .map(name -> Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .toList();

    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final SecureRandom random = new SecureRandom();

    public ManagedToolResult normalize(RuntimeTool tool, ProviderCallResponse response) {
        if (tool == null || response == null) {
            throw new IllegalArgumentException("Managed provider response is invalid");
        }
        ManagedToolResult.ErrorCategory statusCategory = statusCategory(response.status());
        if (statusCategory != null) {
            return statusError(tool, response, statusCategory);
        }
        byte[] body = response.body();
        ResponseNormalizationPolicy policy = tool.responseNormalization();
        if (body.length == 0) {
            if (policy == null) {
                return success(NullNode.getInstance(), response.status());
            }
            if (empty(policy)) {
                return success(envelope(NullNode.getInstance(), null, null, null, policy), response.status());
            }
            return error(tool, ManagedToolResult.ErrorCategory.UPSTREAM_PROTOCOL, response.status(), null, null);
        }
        JsonNode providerCode = null;
        String providerMessage = null;
        try {
            JsonNode root = parse(response.firstHeader("Content-Type"), body);
            if (policy == null) {
                return success(root, response.status());
            }
            if (policy.successCodePointer() != null) {
                providerCode = scalar(root, policy.successCodePointer());
            }
            if (policy.errorMessagePointer() != null) {
                JsonNode message = at(root, policy.errorMessagePointer());
                if (!message.isTextual()) {
                    throw new ProtocolMismatch();
                }
                providerMessage = message.textValue();
            }
            if (providerCode != null && !matches(providerCode, policy.successValues())) {
                return error(tool, ManagedToolResult.ErrorCategory.PROVIDER_BUSINESS,
                        response.status(), providerCode, providerMessage);
            }
            JsonNode data = policy.dataPointer() == null ? root : at(root, policy.dataPointer());
            JsonNode total = null;
            if (policy.totalCountPointer() != null) {
                total = at(root, policy.totalCountPointer());
                if (!total.isIntegralNumber() || !total.canConvertToLong() || total.longValue() < 0) {
                    throw new ProtocolMismatch();
                }
            }
            return success(envelope(data, total, providerCode, providerMessage, policy), response.status());
        } catch (ProtocolMismatch | java.io.IOException failure) {
            return error(tool, ManagedToolResult.ErrorCategory.UPSTREAM_PROTOCOL,
                    response.status(), providerCode, providerMessage);
        }
    }

    public ManagedToolResult error(
            RuntimeTool tool,
            ManagedToolResult.ErrorCategory category,
            Integer status,
            JsonNode providerCode,
            String providerMessage) {
        ObjectNode detail = json.createObjectNode();
        detail.put("category", category.name());
        detail.set("providerCode", providerCode == null ? NullNode.getInstance() : providerCode);
        String safe = sanitize(providerMessage);
        if (safe == null) {
            detail.putNull("providerMessage");
        } else {
            detail.put("providerMessage", safe);
        }
        detail.put("retryable", retryable(category, status));
        if (status == null) {
            detail.putNull("httpStatus");
        } else {
            detail.put("httpStatus", status);
        }
        detail.put("operationId", tool.operationId());
        detail.put("traceId", traceId());
        ObjectNode root = json.createObjectNode();
        root.set("error", detail);
        return ManagedToolResult.providerError(write(root), category, status);
    }

    private ManagedToolResult statusError(
            RuntimeTool tool,
            ProviderCallResponse response,
            ManagedToolResult.ErrorCategory category) {
        JsonNode code = null;
        String message = null;
        ResponseNormalizationPolicy policy = tool.responseNormalization();
        if (policy != null && response.body().length > 0) {
            try {
                JsonNode root = parse(response.firstHeader("Content-Type"), response.body());
                if (policy.successCodePointer() != null) {
                    code = scalar(root, policy.successCodePointer());
                }
                if (policy.errorMessagePointer() != null) {
                    JsonNode value = at(root, policy.errorMessagePointer());
                    if (!value.isTextual()) {
                        throw new ProtocolMismatch();
                    }
                    message = value.textValue();
                }
            } catch (RuntimeException | java.io.IOException ignored) {
                code = null;
                message = null;
            }
        }
        return error(tool, category, response.status(), code, message);
    }

    private JsonNode parse(String contentType, byte[] body) throws java.io.IOException {
        if (!jsonMedia(contentType)) {
            throw new ProtocolMismatch();
        }
        try (JsonParser parser = json.createParser(body)) {
            JsonNode root = json.readTree(parser);
            if (root == null || root.isMissingNode() || parser.nextToken() != null) {
                throw new ProtocolMismatch();
            }
            return root;
        }
    }

    private boolean jsonMedia(String contentType) {
        if (contentType == null) {
            return false;
        }
        String media = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return "application/json".equals(media)
                || media.startsWith("application/") && media.endsWith("+json");
    }

    private JsonNode at(JsonNode root, String pointer) {
        JsonNode value = root.at(pointer);
        if (value.isMissingNode()) {
            throw new ProtocolMismatch();
        }
        return value;
    }

    private JsonNode scalar(JsonNode root, String pointer) {
        JsonNode value = at(root, pointer);
        if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) {
            throw new ProtocolMismatch();
        }
        return value;
    }

    private boolean matches(JsonNode observed, List<Object> expected) {
        for (Object candidate : expected) {
            if (observed.isNumber() && candidate instanceof Number number
                    && observed.decimalValue().compareTo(new BigDecimal(number.toString())) == 0) {
                return true;
            }
            if (observed.isTextual() && candidate instanceof String stringValue
                    && observed.textValue().equals(stringValue)) {
                return true;
            }
            if (observed.isBoolean() && candidate instanceof Boolean booleanValue
                    && observed.booleanValue() == booleanValue) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode envelope(
            JsonNode data,
            JsonNode total,
            JsonNode code,
            String message,
            ResponseNormalizationPolicy policy) {
        ObjectNode result = json.createObjectNode();
        result.set("data", data);
        if (policy.totalCountPointer() != null) {
            result.putObject("page").set("totalCount", total);
        }
        if (policy.successCodePointer() != null || policy.errorMessagePointer() != null) {
            ObjectNode provider = result.putObject("provider");
            if (policy.successCodePointer() != null) {
                provider.set("code", code);
            }
            if (policy.errorMessagePointer() != null) {
                String safe = sanitize(message);
                if (safe == null) {
                    provider.putNull("message");
                } else {
                    provider.put("message", safe);
                }
            }
        }
        return result;
    }

    private ManagedToolResult success(JsonNode node, int status) {
        return ManagedToolResult.success(write(node), status);
    }

    private byte[] write(JsonNode node) {
        try {
            return json.writeValueAsBytes(node);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Managed provider response could not be serialized");
        }
    }

    private ManagedToolResult.ErrorCategory statusCategory(int status) {
        if (status >= 200 && status < 300) return null;
        if (status >= 400 && status < 500) return ManagedToolResult.ErrorCategory.UPSTREAM_CLIENT;
        if (status >= 500 && status < 600) return ManagedToolResult.ErrorCategory.UPSTREAM_SERVER;
        return ManagedToolResult.ErrorCategory.UPSTREAM_PROTOCOL;
    }

    private boolean retryable(ManagedToolResult.ErrorCategory category, Integer status) {
        return switch (category) {
            case UPSTREAM_SERVER, UPSTREAM_TIMEOUT, UPSTREAM_UNAVAILABLE, RATE_LIMITED -> true;
            case UPSTREAM_CLIENT -> status != null && (status == 408 || status == 425 || status == 429);
            default -> false;
        };
    }

    private boolean empty(ResponseNormalizationPolicy policy) {
        return policy.dataPointer() == null && policy.successCodePointer() == null
                && policy.errorMessagePointer() == null && policy.totalCountPointer() == null;
    }

    private String sanitize(String message) {
        if (message == null) return null;
        if (message.codePoints().anyMatch(Character::isISOControl)) return UNSAFE_MESSAGE;
        if (SENSITIVE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(message).find())) return UNSAFE_MESSAGE;
        String safe = message;
        if (safe.codePointCount(0, safe.length()) > 512) {
            safe = safe.substring(0, safe.offsetByCodePoints(0, 512));
        }
        return safe;
    }

    private String traceId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static final class ProtocolMismatch extends RuntimeException {
        private ProtocolMismatch() {
            super(null, null, false, false);
        }
    }
}
