package io.gen2spring.mcp.application.managed.execution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.managed.credential.service.RuntimeCredentialResolver.ResolvedCredentials;
import io.gen2spring.mcp.application.managed.credential.service.RuntimeCredentialResolver.WireCredential;
import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.toolmodel.schema.SchemaValueValidator;
import io.gen2spring.mcp.domain.platform.runtime.ProviderTarget;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import java.lang.reflect.Array;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RuntimeHttpRequestFactory {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaValueValidator SCHEMA_VALUES = new SchemaValueValidator();
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "host", "content-length", "content-type", "accept",
            "connection", "keep-alive", "proxy-authenticate", "te", "trailer", "transfer-encoding", "upgrade",
            "traceparent", "tracestate", "baggage", "b3");

    public ProviderCallRequest create(
            RuntimeTool tool,
            Optional<ProviderTarget> override,
            Map<String, Object> arguments) {
        return createInternal(tool, override, arguments, null);
    }

    public ProviderCallRequest create(
            RuntimeTool tool,
            Optional<ProviderTarget> override,
            Map<String, Object> arguments,
            ResolvedCredentials credentials) {
        if (credentials == null) throw invalid();
        return createInternal(tool, override, arguments, credentials);
    }

    private ProviderCallRequest createInternal(
            RuntimeTool tool,
            Optional<ProviderTarget> override,
            Map<String, Object> arguments,
            ResolvedCredentials credentials) {
        try {
            if (tool == null || override == null || arguments == null) {
                throw invalid();
            }
            if (credentials == null && !tool.credentials().isEmpty()) {
                throw invalid();
            }
            RuntimeHttp http = tool.http();
            validateBindings(http.bindings());
            validateArguments(tool, http.bindings(), arguments);
            URI base = base(http.baseUrl(), override);
            String path = join(base.getRawPath(), relativeBasePath(http.baseUrl()), http.path());
            Map<String, Object> pathVariables = new LinkedHashMap<>();
            List<String> query = new ArrayList<>();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            RequestBodyValue body = http.objectRequestBody() && http.requestBodyRequired()
                    ? new RequestBodyValue(true, new LinkedHashMap<String, Object>())
                    : RequestBodyValue.absent();

            for (ParameterBinding binding : http.bindings()) {
                if (!arguments.containsKey(binding.sourceName())) {
                    continue;
                }
                Object value = arguments.get(binding.sourceName());
                if (value == null) {
                    if (binding.targetLocation() == ParameterLocation.QUERY
                            || binding.targetLocation() == ParameterLocation.HEADER) {
                        continue;
                    }
                    if (binding.targetLocation() == ParameterLocation.PATH) {
                        throw invalid();
                    }
                }
                switch (binding.targetLocation()) {
                    case PATH -> pathVariables.put(binding.targetName(), scalar(value));
                    case QUERY -> addValues(query, binding.targetName(), value, true);
                    case HEADER -> addHeader(headers, binding.targetName(), value);
                    case BODY -> body = new RequestBodyValue(
                            true, bindBody(body.value(), binding.targetName(), value, http.objectRequestBody()));
                }
            }
            for (Map.Entry<String, Object> entry : pathVariables.entrySet()) {
                path = path.replace("{" + entry.getKey() + "}", encode(entry.getValue().toString()));
            }
            if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
                throw invalid();
            }
            if (credentials != null) {
                injectCredentials(tool, http.bindings(), credentials, query, headers);
            }
            String uri = base.getScheme() + "://" + authority(base) + path;
            if (!query.isEmpty()) {
                uri += "?" + String.join("&", query);
            }
            byte[] bytes = body.present() ? JSON.writeValueAsBytes(body.value()) : new byte[0];
            if (bytes.length > 0 && !allowsBody(http.method())) {
                throw invalid();
            }
            return new ProviderCallRequest(http.method(), URI.create(uri), headers, bytes);
        } catch (RuntimeRequestInvalid failure) {
            throw failure;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw invalid();
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private void injectCredentials(
            RuntimeTool tool,
            List<ParameterBinding> bindings,
            ResolvedCredentials resolved,
            List<String> query,
            Map<String, List<String>> headers) {
        Map<String, io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential> expected =
                new LinkedHashMap<>();
        tool.credentials().forEach(value -> expected.put(value.credentialSlot(), value));
        Map<String, WireCredential> supplied = new LinkedHashMap<>();
        for (WireCredential value : resolved.values()) {
            if (supplied.putIfAbsent(value.slot(), value) != null) throw invalid();
        }
        if (!expected.keySet().containsAll(supplied.keySet())
                || expected.values().stream().anyMatch(value -> value.required()
                        && !supplied.containsKey(value.credentialSlot()))) throw invalid();
        Set<String> userTargets = bindings.stream()
                .map(binding -> binding.targetLocation().name() + ":" + canonicalTarget(binding))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> credentialTargets = new HashSet<>();
        for (Map.Entry<String, WireCredential> entry : supplied.entrySet()) {
            var contract = expected.get(entry.getKey());
            WireCredential wire = entry.getValue();
            if (wire.location() != contract.targetLocation()
                    || !sameTarget(wire.location(), wire.targetName(), contract.targetName())) throw invalid();
            String canonical = wire.location().name() + ":" + canonicalTarget(wire.location(), wire.targetName());
            if (userTargets.contains(canonical) || !credentialTargets.add(canonical)) throw invalid();
            if (wire.location() == ParameterLocation.HEADER && forbiddenCredentialHeader(wire.targetName())) {
                throw invalid();
            }
            String value = decodeWire(wire.wireValue());
            if (wire.location() == ParameterLocation.QUERY) {
                query.add(encode(wire.targetName()) + "=" + encode(value));
            } else {
                headers.put(wire.targetName(), List.of(value));
            }
        }
    }

    private boolean sameTarget(ParameterLocation location, String left, String right) {
        return location == ParameterLocation.HEADER ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    private String canonicalTarget(ParameterLocation location, String name) {
        return location == ParameterLocation.HEADER ? name.toLowerCase(Locale.ROOT) : name;
    }

    private String decodeWire(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl)) throw invalid();
            return value;
        } catch (RuntimeRequestInvalid failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private boolean forbiddenCredentialHeader(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return !"authorization".equals(value)
                && (RESERVED_HEADERS.contains(value) || value.startsWith("x-b3-"));
    }

    private URI base(String metadataBase, Optional<ProviderTarget> override) {
        URI metadata = URI.create(metadataBase);
        if (metadata.getRawQuery() != null || metadata.getRawFragment() != null) {
            throw invalid();
        }
        if (metadata.isAbsolute()) {
            if (override.isPresent()) {
                throw invalid();
            }
            return ProviderTarget.parse(metadataBase).uri();
        }
        return override.orElseThrow(RuntimeHttpRequestFactory::invalid).uri();
    }

    private String relativeBasePath(String metadataBase) {
        URI metadata = URI.create(metadataBase);
        return metadata.isAbsolute() ? "" : metadata.getRawPath();
    }

    private String authority(URI base) {
        int port = base.getPort();
        return base.getHost() + (port < 0 ? "" : ":" + port);
    }

    private String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty() || "/".equals(value)) {
                continue;
            }
            if (result.isEmpty() || result.charAt(result.length() - 1) != '/') {
                result.append('/');
            }
            result.append(value.startsWith("/") ? value.substring(1) : value);
        }
        return result.isEmpty() ? "/" : result.toString();
    }

    private void validateBindings(List<ParameterBinding> bindings) {
        Set<String> targets = new LinkedHashSet<>();
        for (ParameterBinding binding : bindings) {
            String target = binding.targetLocation().name() + ":" + canonicalTarget(binding);
            if (!targets.add(target)) {
                throw invalid();
            }
            if (binding.targetLocation() == ParameterLocation.HEADER && reserved(binding.targetName())) {
                throw invalid();
            }
        }
    }

    private void validateArguments(RuntimeTool tool, List<ParameterBinding> bindings, Map<String, Object> arguments) {
        Set<String> names = bindings.stream().map(ParameterBinding::sourceName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!names.containsAll(arguments.keySet()) || arguments.keySet().stream().anyMatch(name -> name == null)) {
            throw invalid();
        }
        try {
            SCHEMA_VALUES.validate(tool.inputSchema(), arguments);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
        Object requiredValue = tool.inputSchema().get("required");
        if (requiredValue instanceof List<?> required
                && required.stream().anyMatch(name -> !(name instanceof String string) || !arguments.containsKey(string))) {
            throw invalid();
        }
    }

    private Object bindBody(Object current, String target, Object value, boolean objectBody) {
        if (!objectBody) {
            return value;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (current instanceof Map<?, ?> existing) {
            existing.forEach((key, nested) -> result.put((String) key, nested));
        }
        result.put(target, value);
        return result;
    }

    private void addHeader(Map<String, List<String>> headers, String name, Object value) {
        List<String> values = new ArrayList<>();
        addScalars(values, value);
        headers.put(name, List.copyOf(values));
    }

    private void addValues(List<String> query, String name, Object value, boolean encodeValues) {
        List<String> values = new ArrayList<>();
        addScalars(values, value);
        for (String item : values) {
            query.add(encode(name) + "=" + (encodeValues ? encode(item) : item));
        }
    }

    private void addScalars(List<String> target, Object value) {
        if (value instanceof Iterable<?> values) {
            values.forEach(item -> target.add(scalar(item)));
        } else if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                target.add(scalar(Array.get(value, index)));
            }
        } else {
            target.add(scalar(value));
        }
        if (target.isEmpty()) {
            throw invalid();
        }
    }

    private String scalar(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            throw invalid();
        }
        String text = String.valueOf(value);
        if (text.length() > 8192 || text.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return text;
    }

    private String canonicalTarget(ParameterBinding binding) {
        return binding.targetLocation() == ParameterLocation.HEADER
                ? binding.targetName().toLowerCase(Locale.ROOT) : binding.targetName();
    }

    private boolean reserved(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return RESERVED_HEADERS.contains(value) || value.startsWith("x-b3-");
    }

    private String encode(String value) {
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = current & 0xff;
            if (unsigned >= 'a' && unsigned <= 'z' || unsigned >= 'A' && unsigned <= 'Z'
                    || unsigned >= '0' && unsigned <= '9' || unsigned == '-' || unsigned == '.'
                    || unsigned == '_' || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%').append(String.format(Locale.ROOT, "%02X", unsigned));
            }
        }
        return result.toString();
    }

    private boolean allowsBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT
                || method == HttpMethod.PATCH || method == HttpMethod.DELETE;
    }

    private static RuntimeRequestInvalid invalid() {
        return new RuntimeRequestInvalid();
    }

    public static final class RuntimeRequestInvalid extends RuntimeException {
        public RuntimeRequestInvalid() {
            super("Managed provider request is invalid", null, false, false);
        }
    }

    private record RequestBodyValue(boolean present, Object value) {
        private static RequestBodyValue absent() {
            return new RequestBodyValue(false, null);
        }
    }
}
