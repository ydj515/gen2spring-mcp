package io.gen2spring.mcp.springai2;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ProjectFileRenderer {
    private static final CompatibilityProfile PINNED_PROFILE = CompatibilityProfile.p0();
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SECRET_PROPERTY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,127}");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final YAMLMapper YAML = YAMLMapper.builder(new YAMLFactory())
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();

    private final CompatibilityProfile profile;

    public ProjectFileRenderer(CompatibilityProfile profile) {
        if (!PINNED_PROFILE.equals(profile)) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                    "The Spring AI 2 renderer requires the pinned compatibility profile");
        }
        this.profile = profile;
    }

    public String buildGradle(ProjectCoordinates coordinates) {
        ProjectCoordinates safeCoordinates = requireCoordinates(coordinates);
        var target = profile.target();
        return """
                plugins {
                    java
                    id(\"org.springframework.boot\") version \"%s\"
                }

                group = \"%s\"
                version = \"0.1.0\"

                repositories {
                    mavenCentral()
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(%d)
                    }
                }

                dependencies {
                    implementation(platform(\"org.springframework.boot:spring-boot-dependencies:%s\"))
                    implementation(platform(\"org.springframework.ai:spring-ai-bom:%s\"))
                    implementation(\"org.springframework.ai:spring-ai-starter-mcp-server-webmvc\")
                    implementation(\"org.springframework.boot:spring-boot-restclient\")
                    implementation(\"org.springframework.boot:spring-boot-starter-validation\")
                    testImplementation(\"org.springframework.boot:spring-boot-starter-test\")
                }

                tasks.bootJar {
                    archiveFileName.set(\"%s.jar\")
                }

                tasks.withType<JavaCompile> {
                    options.compilerArgs.add("-parameters")
                }

                tasks.test {
                    useJUnitPlatform()
                }
                """.formatted(
                target.springBootVersion(), safeCoordinates.groupId(), target.javaVersion(),
                target.springBootVersion(), target.springAiVersion(), safeCoordinates.artifactId());
    }

    public String settingsGradle(ProjectCoordinates coordinates) {
        return "rootProject.name = \"%s\"\n".formatted(requireCoordinates(coordinates).artifactId());
    }

    public String gradleProperties() {
        return """
                org.gradle.caching=true
                org.gradle.configuration-cache=true
                """;
    }

    public String applicationYaml(GenerationContext context) {
        ProjectCoordinates coordinates = requireContext(context);
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> spring = new LinkedHashMap<>();
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("name", coordinates.artifactId());
        spring.put("application", application);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", coordinates.artifactId());
        server.put("version", profile.runtimeVersion());
        server.put("type", "SYNC");
        server.put("protocol", "STREAMABLE");
        server.put("annotation-scanner", Map.of("enabled", !JavaSourceRenderer.requiresExplicitToolSchema(context.tools())));
        server.put("streamable-http", Map.of("mcp-endpoint", "/mcp"));
        spring.put("ai", Map.of("mcp", Map.of("server", server)));
        root.put("spring", spring);

        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("base-url", "${PROVIDER_BASE_URL:https://api.example.test}");
        provider.put("response-max-bytes", 1_048_576);
        provider.put("connect-timeout-millis", 2_000);
        provider.put("read-timeout-millis", 5_000);
        provider.put("total-timeout-millis", 10_000);
        provider.put("max-concurrent-requests", 16);
        provider.put("max-queued-requests", 64);
        Map<String, String> secrets = secretProperties(context.tools());
        if (!secrets.isEmpty()) {
            provider.put("secrets", secrets);
        }
        root.put("provider", provider);
        try {
            return YAML.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw GeneratorException.system(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                    "Failed to render application configuration", exception);
        }
    }

    public String dockerfile(ProjectCoordinates coordinates) {
        String artifactId = requireCoordinates(coordinates).artifactId();
        return """
                FROM eclipse-temurin:21-jre
                WORKDIR /app
                COPY build/libs/%s.jar /app/app.jar
                ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]
                """.formatted(artifactId);
    }

    public String readme(GenerationContext context) {
        ProjectCoordinates coordinates = requireContext(context);
        List<String> environmentVariables = secretProperties(context.tools()).values().stream()
                .map(placeholder -> placeholder.substring(2, placeholder.length() - 2))
                .toList();
        String secrets = environmentVariables.isEmpty() ? "No provider secrets are required."
                : "Set these environment variables before starting:\n\n"
                        + environmentVariables.stream().map(value -> "- `" + value + "`").reduce("", (left, right) -> left + right + "\n");
        String tools = renderedTools(context.tools());
        String dockerEnvironment = dockerEnvironment(context.tools());
        var target = profile.target();
        return """
                # %s

                Generated Spring AI MCP server. It exposes the selected OpenAPI operations as
                deterministic MCP tools and invokes the configured provider over HTTP.

                ## Run

                Requirements: Java %d.

                ```bash
                ./gradlew bootRun
                ```

                The Streamable HTTP MCP endpoint is `http://localhost:8080/mcp`.

                ## Provider configuration

                %s

                Provider calls use a 2 second connect timeout, 5 second response-read timeout,
                10 second total timeout, 1 MiB response limit, 16 concurrent requests, and a
                64 request queue by default. Override the `provider` values in `application.yml`
                only with positive timeout values up to 300000 milliseconds.

                ## MCP client configuration

                ```json
                {
                  "mcpServers": {
                    "%s": {
                      "url": "http://localhost:8080/mcp"
                    }
                  }
                }
                ```

                ## Tools

                %s

                ## Test

                ```bash
                ./gradlew test
                ```

                ## Docker

                ```bash
                ./gradlew bootJar
                docker build -t %s .
                docker run --rm -p 8080:8080%s %s
                ```

                ## Generator information

                - Compatibility profile: `%s`
                - Template: `%s`
                - Generator module: `%s`
                - Runtime version: `%s`
                - Java %d
                - Spring Boot %s
                - Spring AI %s

                ## Known P0 limits

                - Supports the generated P0 Spring MVC Streamable HTTP server only.
                - Requires JSON request and successful response bodies when present; empty successful responses are supported.
                - Does not generate OAuth flows, streaming provider responses, or non-JSON provider payload mappings.
                """.formatted(
                coordinates.artifactId(), target.javaVersion(), secrets, coordinates.artifactId(), tools,
                coordinates.artifactId(), dockerEnvironment, coordinates.artifactId(), profile.id(), profile.templateVersion(),
                profile.generatorModule(), profile.runtimeVersion(), target.javaVersion(), target.springBootVersion(),
                target.springAiVersion());
    }

    public String gitignore() {
        return "/.gradle/\n/build/\n";
    }

    public byte[] wrapperAsset(String fileName) {
        try (InputStream resource = ProjectFileRenderer.class.getResourceAsStream("/wrapper/" + fileName)) {
            if (resource == null) {
                throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                        "A required Gradle wrapper asset is missing");
            }
            return resource.readAllBytes();
        } catch (IOException exception) {
            throw GeneratorException.system(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                    "Failed to load a Gradle wrapper asset", exception);
        }
    }

    ProjectCoordinates requireContext(GenerationContext context) {
        if (context == null || !PINNED_PROFILE.equals(context.profile()) || context.request() == null) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                    "A generation context for the pinned compatibility profile is required");
        }
        return requireCoordinates(context.request().project());
    }

    private ProjectCoordinates requireCoordinates(ProjectCoordinates coordinates) {
        if (coordinates == null) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-2-render", "Project coordinates are required");
        }
        String groupId = JavaIdentifier.requirePackage(coordinates.groupId());
        String packageName = JavaIdentifier.requirePackage(coordinates.packageName());
        String artifactId = coordinates.artifactId();
        if (artifactId == null || !ARTIFACT_ID.matcher(artifactId).matches()) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                    "Project artifact IDs must use only letters, digits, dots, hyphens, and underscores");
        }
        return new ProjectCoordinates(groupId, artifactId, packageName);
    }

    private Map<String, String> secretProperties(List<McpToolDefinition> tools) {
        List<SecretBinding> bindings = new ArrayList<>();
        if (tools != null) {
            for (McpToolDefinition tool : tools) {
                if (tool != null && tool.secretBindings() != null) {
                    bindings.addAll(tool.secretBindings());
                }
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (SecretBinding binding : bindings) {
            if (binding == null || binding.propertyName() == null
                    || !SECRET_PROPERTY.matcher(binding.propertyName()).matches()
                    || binding.environmentVariable() == null
                    || !ENVIRONMENT_VARIABLE.matcher(binding.environmentVariable()).matches()) {
                throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                        "Generated secret configuration requires safe property and environment variable names");
            }
        }
        bindings.sort(Comparator.comparing(SecretBinding::propertyName)
                .thenComparing(SecretBinding::environmentVariable)
                .thenComparing(SecretBinding::targetName));
        for (SecretBinding binding : bindings) {
            String placeholder = "${" + binding.environmentVariable() + ":}";
            String previous = values.putIfAbsent(binding.propertyName(), placeholder);
            if (previous != null && !previous.equals(placeholder)) {
                throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-2-render",
                        "Generated secret property names must be unique");
            }
        }
        return values;
    }

    private String renderedTools(List<McpToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "No tools were generated.";
        }
        return tools.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(McpToolDefinition::name))
                .map(tool -> "- `" + tool.name() + "`: " + markdownText(tool.description()))
                .reduce("", (left, right) -> left + right + "\n")
                .stripTrailing();
    }

    private String markdownText(String value) {
        if (value == null || value.isBlank()) {
            return "No description provided.";
        }
        StringBuilder plain = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n') {
                plain.append(' ');
            } else if (current == '&') {
                plain.append("&amp;");
            } else if (current == '<') {
                plain.append("&lt;");
            } else if (current == '>') {
                plain.append("&gt;");
            } else if ("\\`*_{}[]()#+-!|~".indexOf(current) >= 0) {
                plain.append('\\').append(current);
            } else {
                plain.append(current);
            }
        }
        return plain.toString();
    }

    private String dockerEnvironment(List<McpToolDefinition> tools) {
        java.util.TreeSet<String> values = new java.util.TreeSet<>();
        if (tools != null) {
            for (McpToolDefinition tool : tools) {
                if (tool == null || tool.secretBindings() == null) {
                    continue;
                }
                for (SecretBinding binding : tool.secretBindings()) {
                    if (binding != null && binding.required()
                            && binding.environmentVariable() != null
                            && ENVIRONMENT_VARIABLE.matcher(binding.environmentVariable()).matches()) {
                        values.add(binding.environmentVariable());
                    }
                }
            }
        }
        StringBuilder command = new StringBuilder(" -e PROVIDER_BASE_URL=https://api.example.test");
        values.forEach(value -> command.append(" -e ").append(value));
        return command.toString();
    }
}
