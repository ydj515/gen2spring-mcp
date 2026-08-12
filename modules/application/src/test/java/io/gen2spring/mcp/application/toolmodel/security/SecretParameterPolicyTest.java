package io.gen2spring.mcp.application.toolmodel.security;

import static io.gen2spring.mcp.domain.tool.ParameterSource.SERVER_SECRET;
import static io.gen2spring.mcp.domain.tool.ParameterSource.USER_INPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.command.GenerationCommand.ParameterOverride;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretParameterPolicyTest {
    private final SecretParameterPolicy policy = new SecretParameterPolicy();

    @Test
    void classifiesServiceKeyAsServerSecret() {
        assertEquals(SERVER_SECRET,
                policy.classify("serviceKey", ParameterLocation.QUERY, false));
    }

    @Test
    void givesAnExplicitOverridePrecedence() {
        assertEquals(SERVER_SECRET, policy.classify("region", ParameterLocation.QUERY, false,
                new ParameterOverride(SERVER_SECRET, "REGION_TOKEN")));
        assertEquals(USER_INPUT, policy.classify("serviceKey", ParameterLocation.QUERY, false,
                new ParameterOverride(USER_INPUT, null)));
    }

    @Test
    void acceptsOnlySafeEnvironmentVariableNamesForSecrets() {
        assertEquals("KMA_SERVICE_KEY", policy.requireEnvironmentVariable("KMA_SERVICE_KEY"));
        assertThrows(GeneratorException.class, () -> policy.requireEnvironmentVariable("kma-service-key"));
    }

    @Test
    void rejectsApplicationReservedEnvironmentVariableNamesWithoutEchoingThem() {
        for (String reserved : List.of(
                "PROVIDER_BASE_URL",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "SPRING_APPLICATION_JSON")) {
            GeneratorException exception = assertThrows(
                    GeneratorException.class,
                    () -> policy.requireEnvironmentVariable(reserved),
                    reserved);

            assertEquals("tool-policy", exception.stage(), reserved);
            assertEquals(
                    "Server secrets cannot use an application-reserved environment variable name",
                    exception.safeMessage(),
                    reserved);
            assertFalse(exception.safeMessage().contains(reserved), reserved);
        }
    }
}
