package io.gen2spring.mcp.policy;

import static io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource.SERVER_SECRET;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource.USER_INPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.config.GenerationRequest.ParameterOverride;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation;
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
}
