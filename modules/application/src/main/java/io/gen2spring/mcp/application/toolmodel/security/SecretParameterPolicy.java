package io.gen2spring.mcp.application.toolmodel.security;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SECRET_EXPOSURE_DETECTED;
import static io.gen2spring.mcp.domain.tool.ParameterSource.SERVER_SECRET;
import static io.gen2spring.mcp.domain.tool.ParameterSource.USER_INPUT;

import io.gen2spring.mcp.application.command.GenerationCommand.ParameterOverride;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.tool.ParameterSource;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SecretParameterPolicy {
    private static final Set<String> SECRET_CANDIDATES = Set.of(
            "apikey", "servicekey", "accesstoken", "clientsecret", "authorization", "xapikey");
    private static final Set<String> RESERVED_APPLICATION_ENVIRONMENT_VARIABLES = Set.of(
            "PROVIDER_BASE_URL",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "SPRING_APPLICATION_JSON");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    public ParameterSource classify(String name, ParameterLocation location, boolean apiKeyParameter) {
        return classify(name, location, apiKeyParameter, null);
    }

    public ParameterSource classify(
            String name,
            ParameterLocation location,
            boolean apiKeyParameter,
            ParameterOverride override) {
        if (override != null && override.source() != null) {
            return override.source();
        }
        if (apiKeyParameter || isApprovedCandidate(name)) {
            return SERVER_SECRET;
        }
        return USER_INPUT;
    }

    public boolean isApprovedCandidate(String name) {
        return SECRET_CANDIDATES.contains(normalizeName(name));
    }

    public String requireEnvironmentVariable(String environmentVariable) {
        if (environmentVariable == null || !ENVIRONMENT_VARIABLE.matcher(environmentVariable).matches()) {
            throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                    "Server secrets require a valid environment variable name");
        }
        if (RESERVED_APPLICATION_ENVIRONMENT_VARIABLES.contains(environmentVariable)) {
            throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                    "Server secrets cannot use an application-reserved environment variable name");
        }
        return environmentVariable;
    }

    private String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return ascii.replaceAll("[^a-z0-9]", "");
    }
}
