package io.gen2spring.mcp.app.cli.application.port.out;

import io.gen2spring.mcp.application.command.GenerationCommand;
import java.nio.file.Path;

public interface ConfigurationPort {
    GenerationCommand read(Path configuration);
}
