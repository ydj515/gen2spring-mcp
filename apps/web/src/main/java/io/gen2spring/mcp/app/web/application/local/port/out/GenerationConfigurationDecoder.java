package io.gen2spring.mcp.app.web.application.local.port.out;

import io.gen2spring.mcp.application.generation.command.GenerationCommand;

/** Decodes and validates a local generation request without exposing its wire format. */
public interface GenerationConfigurationDecoder {
    GenerationCommand decode(byte[] bytes);
}
