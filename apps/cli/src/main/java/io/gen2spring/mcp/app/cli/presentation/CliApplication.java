package io.gen2spring.mcp.app.cli.presentation;

import static io.gen2spring.mcp.application.generation.validation.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.INTERNAL_ERROR;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.app.cli.application.CliUseCases;
import io.gen2spring.mcp.app.cli.application.exception.CliConfigurationException;
import io.gen2spring.mcp.application.generation.result.GenerationOutcome;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import java.io.PrintWriter;
import java.util.Objects;

public final class CliApplication {
    private static final String REPORT_FILE = "VALIDATION_REPORT.json";

    private final CommandLine commandLine;
    private final CliUseCases useCases;
    private final ObjectMapper json;
    private final CliOutput console;

    public CliApplication(CommandLine commandLine, CliUseCases useCases, ObjectMapper json) {
        this.commandLine = Objects.requireNonNull(commandLine, "commandLine");
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.json = Objects.requireNonNull(json, "json").copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.console = new CliOutput(this.json);
    }

    public int run(String[] arguments, PrintWriter stdout, PrintWriter stderr) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        try {
            CommandLine.Parsed parsed = commandLine.parse(arguments);
            return switch (parsed.command()) {
                case PROFILES -> profiles(stdout);
                case INSPECT -> inspect(parsed, stdout);
                case GENERATE -> generate(parsed, stdout);
            };
        } catch (CliUsageException | CliConfigurationException exception) {
            console.error(stderr, "CLI_CONFIGURATION_ERROR", "CLI", exception.getMessage());
            return 2;
        } catch (GeneratorException exception) {
            console.error(stderr, exception.code().name(), exception.stage(), exception.safeMessage());
            return exitCode(exception);
        } catch (RuntimeException exception) {
            console.error(stderr, INTERNAL_ERROR.name(), "CLI", "The command failed safely");
            return 4;
        }
    }

    private int profiles(PrintWriter stdout) {
        ObjectNode root = json.createObjectNode();
        var items = root.putArray("profiles");
        for (CompatibilityProfile profile : useCases.profiles()) {
            ObjectNode item = items.addObject();
            item.put("id", profile.id());
            var implementations = item.putArray("mcpImplementations");
            for (McpImplementation implementation : McpImplementation.values()) {
                if (implementation.supports(profile)) implementations.add(implementation.name());
            }
            item.put("generatorModule", profile.generatorModule());
            item.put("templateVersion", profile.templateVersion());
            item.put("runtimeVersion", profile.runtimeVersion());
            ObjectNode buildTool = item.putObject("buildTool");
            buildTool.put("type", profile.target().buildTool());
            buildTool.put("distributionVersion", profile.buildToolchain().distributionVersion());
            buildTool.put("wrapperVersion", profile.buildToolchain().wrapperVersion());
            if (profile.gradleVersion() != null) {
                item.put("gradleVersion", profile.gradleVersion());
            }
            item.put("containerImage", profile.containerImage());
            ObjectNode target = item.putObject("target");
            target.put("javaVersion", profile.target().javaVersion());
            target.put("springBootVersion", profile.target().springBootVersion());
            target.put("springAiVersion", profile.target().springAiVersion());
            target.put("buildTool", profile.target().buildTool());
            target.put("webStack", profile.target().webStack());
            target.put("programmingModel", profile.target().programmingModel());
            target.put("transport", profile.target().transport());
        }
        console.success(stdout, root);
        return 0;
    }

    private int inspect(CommandLine.Parsed parsed, PrintWriter stdout) {
        CliUseCases.Inspection inspection = useCases.inspect(parsed.specification(), parsed.output());
        ObjectNode response = json.createObjectNode();
        response.put("analysis", inspection.analysis().toString());
        response.put("checksum", inspection.checksum());
        console.success(stdout, response);
        return 0;
    }

    private int generate(CommandLine.Parsed parsed, PrintWriter stdout) {
        GenerationOutcome outcome = useCases.generate(parsed.specification(), parsed.configuration(), parsed.output());
        ObjectNode response = json.createObjectNode();
        response.put("project", outcome.projectRoot().toAbsolutePath().normalize().toString());
        response.put("report", outcome.projectRoot().resolve(REPORT_FILE).toAbsolutePath().normalize().toString());
        if (outcome.archive() == null) {
            response.putNull("archive");
        } else {
            response.put("archive", outcome.archive().toAbsolutePath().normalize().toString());
        }
        response.put("status", outcome.validationStatus().name());
        response.put("sourceChecksum", outcome.sourceChecksum());
        console.success(stdout, response);
        return outcome.validationStatus() == UNVERIFIED ? 5 : 0;
    }

    private int exitCode(GeneratorException exception) {
        return switch (exception.code()) {
            case SPEC_FILE_UNSUPPORTED, SPEC_TOO_LARGE, SPEC_PARSE_FAILED, SPEC_REFERENCE_UNRESOLVED,
                    SPEC_VERSION_UNSUPPORTED, OPERATION_ID_DUPLICATED, OPERATION_UNSUPPORTED,
                    VALIDATION_ARGUMENT_INVALID, SECRET_EXPOSURE_DETECTED -> 3;
            case TARGET_PROFILE_NOT_FOUND, TARGET_COMBINATION_UNSUPPORTED -> 2;
            case RUNTIME_METADATA_INVALID, SOURCE_GENERATION_FAILED -> 4;
            case COMPILE_TIMEOUT, COMPILE_FAILED, APPLICATION_CONTEXT_FAILED,
                    MCP_INITIALIZE_FAILED, MCP_TOOLS_LIST_FAILED, MCP_TOOL_CALL_FAILED -> 5;
            case ARTIFACT_PACKAGE_FAILED -> 6;
            case INTERNAL_ERROR -> internalExitCode(exception.stage());
        };
    }

    private int internalExitCode(String stage) {
        if (stage != null && (stage.startsWith("COMPILE") || stage.startsWith("APPLICATION")
                || stage.startsWith("MCP") || stage.startsWith("VALIDATION"))) {
            return 5;
        }
        return stage != null && stage.startsWith("PACKAGE") ? 6 : 4;
    }

}
