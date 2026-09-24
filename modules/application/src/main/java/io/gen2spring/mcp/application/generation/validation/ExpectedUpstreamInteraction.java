package io.gen2spring.mcp.application.generation.validation;

import java.util.Map;

public record ExpectedUpstreamInteraction(
        Map<String, Object> internalParameters,
        ExpectedUpstreamOutcome outcome,
        ExpectedUpstreamResponse response) {
    public ExpectedUpstreamInteraction {
        if (internalParameters == null || outcome == null
                || outcome == ExpectedUpstreamOutcome.RESPONSE && response == null
                || outcome == ExpectedUpstreamOutcome.DISCONNECT && response != null) {
            throw new IllegalArgumentException("Expected upstream interaction is incomplete");
        }
        internalParameters = ValidationJsonValue.immutableMap(internalParameters, false, false);
    }
}
