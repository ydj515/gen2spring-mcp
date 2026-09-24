package io.gen2spring.mcp.application.architecturefixture.cycle.first;

import io.gen2spring.mcp.application.architecturefixture.cycle.second.Second;

public record First(Second next) {}
