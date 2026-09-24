package io.gen2spring.mcp.adapter.validation.upstream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.generation.validation.ExpectedToolCall;
import io.gen2spring.mcp.application.generation.validation.ExpectedUpstreamInteraction;
import io.gen2spring.mcp.application.generation.validation.ExpectedUpstreamOutcome;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record UpstreamCallExpectation(
        String operationId,
        String method,
        String rawPath,
        Map<String, List<String>> query,
        Map<String, List<String>> headers,
        Object body,
        Map<String, String> environmentOverrides,
        int responseStatus,
        String responseContentType,
        Object responseBody,
        ExpectedUpstreamOutcome outcome) {
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^{}]+)}");
    private static final String SECRET_PREFIX = "mcp-validation-secret-";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public UpstreamCallExpectation {
        if (outcome == null) {
            throw new IllegalArgumentException("Expected Tool call response fixture is invalid");
        }
        if (outcome == ExpectedUpstreamOutcome.RESPONSE
                && (responseStatus < 100 || responseStatus > 599 || !validContentType(responseContentType))) {
            throw new IllegalArgumentException("Expected Tool call response fixture is invalid");
        }
        if (outcome == ExpectedUpstreamOutcome.RESPONSE) {
            requireBoundedResponse(responseBody);
        }
        operationId = requireNonBlank(operationId, "Upstream expectation operation ID is missing");
        method = requireNonBlank(method, "Upstream expectation HTTP method is missing");
        rawPath = requireNonBlank(rawPath, "Upstream expectation path is missing");
        query = immutableStringLists(query);
        headers = immutableStringLists(headers);
        body = immutableJson(body);
        environmentOverrides = immutableStrings(environmentOverrides);
        responseBody = outcome == ExpectedUpstreamOutcome.RESPONSE
                ? immutableResponseJson(responseBody)
                : null;
    }

    public UpstreamCallExpectation(
            String operationId,
            String method,
            String rawPath,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Object body,
            Map<String, String> environmentOverrides,
            int responseStatus,
            String responseContentType,
            Object responseBody) {
        this(operationId, method, rawPath, query, headers, body, environmentOverrides,
                responseStatus, responseContentType, responseBody, ExpectedUpstreamOutcome.RESPONSE);
    }

    public UpstreamCallExpectation(
            String operationId,
            String method,
            String rawPath,
            Map<String, List<String>> query,
            Map<String, List<String>> headers,
            Object body,
            Map<String, String> environmentOverrides) {
        this(
                operationId,
                method,
                rawPath,
                query,
                headers,
                body,
                environmentOverrides,
                200,
                "application/json",
                legacyResponse(operationId),
                ExpectedUpstreamOutcome.RESPONSE);
    }

    public static UpstreamCallExpectation from(ExpectedToolCall expectedCall) {
        return allFrom(expectedCall).getFirst();
    }

    public static List<UpstreamCallExpectation> allFrom(ExpectedToolCall expectedCall) {
        Objects.requireNonNull(expectedCall, "expectedCall");
        var tool = Objects.requireNonNull(expectedCall.tool(), "expectedCall.tool");
        HttpExecution execution = Objects.requireNonNull(
                tool.execution(), "Expected Tool call execution is missing");
        if (execution.method() == null) {
            throw new IllegalArgumentException("Expected Tool call HTTP method is missing");
        }
        String operationId = requireNonBlank(tool.operationId(), "Expected Tool call operation ID is missing");
        String pathTemplate = requirePath(execution.path());
        List<ParameterBinding> parameters = execution.bindings() == null ? List.of() : execution.bindings();
        List<SecretBinding> secrets = executionSecrets(tool.secretBindings());
        validateBindings(parameters, secrets, execution.objectRequestBody());
        validateBodyMethod(execution, parameters, secrets);

        Map<String, String> overrides = secretOverrides(secrets);
        Derivation derivation = new Derivation(execution.objectRequestBody(), execution.requestBodyRequired());
        for (ParameterBinding binding : parameters) {
            validateParameter(binding);
            Object value = expectedCall.arguments().get(binding.sourceName());
            if (value == null) {
                if (binding.targetLocation() == ParameterLocation.PATH) {
                    throw new IllegalArgumentException("Expected Tool call path argument is missing");
                }
                continue;
            }
            derivation.bind(binding.targetLocation(), binding.targetName(), value);
        }
        for (SecretBinding binding : secrets) {
            validateSecret(binding);
            String value = overrides.get(binding.environmentVariable());
            if (value == null || value.isBlank()) {
                if (binding.required()) {
                    throw new IllegalArgumentException("Expected Tool call required secret is missing");
                }
                continue;
            }
            derivation.bind(binding.targetLocation(), binding.targetName(), value);
        }

        String rawPath = expandPath(pathTemplate, derivation.pathValues());
        List<UpstreamCallExpectation> expectations = new ArrayList<>();
        for (ExpectedUpstreamInteraction interaction : expectedCall.upstreamInteractions()) {
            Map<String, List<String>> query = interactionQuery(
                    derivation.query(), execution.paginationPolicy(), interaction);
            if (interaction.outcome() == ExpectedUpstreamOutcome.RESPONSE) {
                expectations.add(new UpstreamCallExpectation(
                        operationId,
                        execution.method().name(),
                        rawPath,
                        query,
                        derivation.headers(),
                        derivation.body(),
                        overrides,
                        interaction.response().status(),
                        interaction.response().contentType(),
                        interaction.response().body(),
                        ExpectedUpstreamOutcome.RESPONSE));
            } else {
                expectations.add(new UpstreamCallExpectation(
                        operationId,
                        execution.method().name(),
                        rawPath,
                        query,
                        derivation.headers(),
                        derivation.body(),
                        overrides,
                        200,
                        "application/json",
                        null,
                        ExpectedUpstreamOutcome.DISCONNECT));
            }
        }
        return List.copyOf(expectations);
    }

    private static Map<String, List<String>> interactionQuery(
            Map<String, List<String>> base,
            PaginationPolicy pagination,
            ExpectedUpstreamInteraction interaction) {
        Map<String, Object> internal = interaction.internalParameters();
        if (pagination == null) {
            if (!internal.isEmpty()) {
                throw new IllegalArgumentException("Expected Tool call internal parameters are invalid");
            }
            return base;
        }
        if (internal.isEmpty() && pagination.initialValue() == null) {
            return base;
        }
        String name = pagination.requestParameter();
        if (internal.size() != 1 || !internal.containsKey(name) || base.containsKey(name)) {
            throw new IllegalArgumentException("Expected Tool call internal parameters are invalid");
        }
        Object value = internal.get(name);
        String wireValue;
        if (value instanceof String text && !text.isEmpty()) {
            wireValue = text;
        } else if (value instanceof BigInteger integer) {
            wireValue = integer.toString();
        } else {
            throw new IllegalArgumentException("Expected Tool call internal parameters are invalid");
        }
        Map<String, List<String>> result = new LinkedHashMap<>(base);
        result.put(name, List.of(wireValue));
        return result;
    }

    private static List<SecretBinding> executionSecrets(List<SecretBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        List<SecretBinding> sorted = new ArrayList<>(bindings);
        sorted.forEach(UpstreamCallExpectation::validateSecret);
        sorted.sort(Comparator
                .comparing(SecretBinding::environmentVariable, Comparator.nullsFirst(String::compareTo))
                .thenComparing(binding -> binding.targetLocation() == null ? "" : binding.targetLocation().name())
                .thenComparing(SecretBinding::targetName, Comparator.nullsFirst(String::compareTo)));
        return List.copyOf(sorted);
    }

    private static Map<String, String> secretOverrides(List<SecretBinding> secrets) {
        Set<String> environments = new TreeSet<>();
        for (SecretBinding binding : secrets) {
            validateSecret(binding);
            environments.add(binding.environmentVariable());
        }
        Map<String, String> result = new LinkedHashMap<>();
        int index = 1;
        for (String environment : environments) {
            result.put(environment, SECRET_PREFIX + index++);
        }
        return result;
    }

    private static void validateBindings(
            List<ParameterBinding> parameters,
            List<SecretBinding> secrets,
            boolean objectBody) {
        Set<String> targets = new LinkedHashSet<>();
        for (ParameterBinding binding : parameters) {
            validateParameter(binding);
            if (!targets.add(targetKey(binding.targetLocation(), binding.targetName(), objectBody))) {
                throw new IllegalArgumentException("Expected Tool call contains a duplicate wire binding");
            }
        }
        for (SecretBinding binding : secrets) {
            validateSecret(binding);
            if (!targets.add(targetKey(binding.targetLocation(), binding.targetName(), objectBody))) {
                throw new IllegalArgumentException("Expected Tool call contains a duplicate wire binding");
            }
        }
    }

    private static String targetKey(ParameterLocation location, String targetName, boolean objectBody) {
        String target = location == ParameterLocation.HEADER
                ? targetName.toLowerCase(Locale.ROOT)
                : targetName;
        if (location == ParameterLocation.BODY && !objectBody) {
            target = "*";
        }
        return location.name() + ':' + target;
    }

    private static void validateBodyMethod(
            HttpExecution execution,
            List<ParameterBinding> parameters,
            List<SecretBinding> secrets) {
        boolean hasBodyBinding = parameters.stream()
                        .anyMatch(binding -> binding.targetLocation() == ParameterLocation.BODY)
                || secrets.stream().anyMatch(binding -> binding.targetLocation() == ParameterLocation.BODY);
        boolean hasBody = hasBodyBinding || execution.objectRequestBody() && execution.requestBodyRequired();
        if (hasBody && switch (execution.method()) {
            case GET, HEAD, OPTIONS, TRACE -> true;
            case POST, PUT, PATCH, DELETE -> false;
        }) {
            throw new IllegalArgumentException("Expected Tool call body is unsupported for the HTTP method");
        }
    }

    private static void validateParameter(ParameterBinding binding) {
        if (binding == null
                || binding.targetLocation() == null
                || blank(binding.sourceName())
                || blank(binding.targetName())) {
            throw new IllegalArgumentException("Expected Tool call parameter binding is incomplete");
        }
    }

    private static void validateSecret(SecretBinding binding) {
        if (binding == null
                || binding.targetLocation() == null
                || blank(binding.environmentVariable())
                || blank(binding.targetName())) {
            throw new IllegalArgumentException("Expected Tool call secret binding is incomplete");
        }
    }

    private static String expandPath(String template, Map<String, Object> values) {
        Matcher matcher = PATH_VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        int previous = 0;
        Set<String> consumed = new LinkedHashSet<>();
        while (matcher.find()) {
            result.append(encodePathLiteral(template.substring(previous, matcher.start())));
            String target = matcher.group(1);
            Object value = values.get(target);
            if (value == null) {
                throw new IllegalArgumentException("Expected Tool call path binding is missing");
            }
            result.append(encodePathVariable(value));
            consumed.add(target);
            previous = matcher.end();
        }
        result.append(encodePathLiteral(template.substring(previous)));
        if (consumed.size() != values.size()) {
            throw new IllegalArgumentException("Expected Tool call path binding has no template target");
        }
        return result.toString();
    }

    private static String encodePathVariable(Object value) {
        if (!scalar(value)) {
            throw new IllegalArgumentException("Expected Tool call path value is unsupported");
        }
        return percentEncode(wireScalar(value), false);
    }

    private static String encodePathLiteral(String value) {
        return percentEncode(value, true);
    }

    private static String percentEncode(String value, boolean pathLiteral) {
        StringBuilder encoded = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint < 128 && (unreserved(codePoint) || pathLiteral && pathCharacter(codePoint))) {
                encoded.append((char) codePoint);
                continue;
            }
            byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            for (byte current : bytes) {
                encoded.append('%');
                int unsigned = current & 0xff;
                encoded.append(HEX[unsigned >>> 4]);
                encoded.append(HEX[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private static boolean unreserved(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static boolean pathCharacter(int value) {
        return value == '/'
                || value == ':' || value == '@'
                || value == '!' || value == '$' || value == '&' || value == '\''
                || value == '(' || value == ')' || value == '*'
                || value == '+' || value == ',' || value == ';' || value == '=';
    }

    private static String requirePath(String path) {
        if (blank(path) || !path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Expected Tool call path is invalid");
        }
        return path;
    }

    private static String requireNonBlank(String value, String message) {
        if (blank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static Map<String, List<String>> immutableStringLists(Map<String, List<String>> source) {
        Objects.requireNonNull(source, "source");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, String> immutableStrings(Map<String, String> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Object immutableJson(Object value) {
        if (value == null || scalar(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name) || name.isBlank()) {
                    throw new IllegalArgumentException("Expected Tool call body contains an invalid property");
                }
                copy.put(name, immutableJson(item));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJson(item)));
            return List.copyOf(copy);
        }
        throw new IllegalArgumentException("Expected Tool call body value is unsupported");
    }

    private static Object immutableResponseJson(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
            return value;
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return value;
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name)
                        || name.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Expected Tool call response fixture is invalid");
                }
                copy.put(name, immutableResponseJson(item));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableResponseJson(item)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Expected Tool call response fixture is invalid");
    }

    private static void requireBoundedResponse(Object value) {
        byte[] serialized;
        try {
            serialized = RESPONSE_MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Expected Tool call response fixture is invalid");
        }
        if (serialized.length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Expected Tool call response fixture exceeded the size limit");
        }
    }

    private static boolean validContentType(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        String[] sections = value.split(";", -1);
        String baseType = sections[0].trim();
        int slash = baseType.indexOf('/');
        if (!(slash > 0
                && slash == baseType.lastIndexOf('/')
                && slash < baseType.length() - 1
                && mediaTypeToken(baseType.substring(0, slash))
                && mediaTypeToken(baseType.substring(slash + 1)))) {
            return false;
        }
        for (int index = 1; index < sections.length; index++) {
            String parameter = sections[index].trim();
            int equals = parameter.indexOf('=');
            if (equals <= 0
                    || equals == parameter.length() - 1
                    || !mediaTypeToken(parameter.substring(0, equals))
                    || !mediaTypeParameterValue(parameter.substring(equals + 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean mediaTypeToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || "!#$%&'*+-.^_`|~".indexOf(character) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean mediaTypeParameterValue(String value) {
        if (mediaTypeToken(value)) {
            return true;
        }
        if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return false;
        }
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> legacyResponse(String operationId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("validated", true);
        response.put("operationId", operationId);
        return response;
    }

    private static final class Derivation {
        private final boolean objectBody;
        private final Map<String, Object> pathValues = new LinkedHashMap<>();
        private final Map<String, List<String>> query = new LinkedHashMap<>();
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private Object body;

        private Derivation(boolean objectBody, boolean bodyRequired) {
            this.objectBody = objectBody;
            body = objectBody && bodyRequired ? new LinkedHashMap<String, Object>() : null;
        }

        private void bind(ParameterLocation location, String targetName, Object value) {
            switch (location) {
                case PATH -> pathValues.put(targetName, requireScalar(value, "path"));
                case QUERY -> putValues(query, targetName, value, "query");
                case HEADER -> putValues(headers, targetName.toLowerCase(Locale.ROOT), value, "header");
                case BODY -> bindBody(targetName, value);
            }
        }

        private void bindBody(String targetName, Object value) {
            if (!objectBody) {
                body = immutableJson(value);
                return;
            }
            Map<String, Object> object;
            if (body == null) {
                object = new LinkedHashMap<>();
                body = object;
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = (Map<String, Object>) body;
                object = existing;
            }
            object.put(targetName, immutableJson(value));
        }

        private static void putValues(
                Map<String, List<String>> target,
                String targetName,
                Object value,
                String location) {
            List<String> values = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    values.add(UpstreamCallExpectation.wireScalar(requireScalar(item, location)));
                }
            } else {
                values.add(UpstreamCallExpectation.wireScalar(requireScalar(value, location)));
            }
            if (!values.isEmpty()) {
                target.put(targetName, List.copyOf(values));
            }
        }

        private static Object requireScalar(Object value, String location) {
            if (!scalar(value)) {
                throw new IllegalArgumentException("Expected Tool call " + location + " value is unsupported");
            }
            return value;
        }

        private Map<String, Object> pathValues() {
            return pathValues;
        }

        private Map<String, List<String>> query() {
            return query;
        }

        private Map<String, List<String>> headers() {
            return headers;
        }

        private Object body() {
            return body;
        }
    }

    private static String wireScalar(Object value) {
        if (!(value instanceof BigDecimal decimal)) {
            return String.valueOf(value);
        }
        BigDecimal normalized = decimal.stripTrailingZeros();
        return decimal.scale() < 0 ? normalized.toString() : normalized.toPlainString();
    }
}
