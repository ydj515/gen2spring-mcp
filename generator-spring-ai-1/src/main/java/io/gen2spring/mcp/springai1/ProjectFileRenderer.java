package io.gen2spring.mcp.springai1;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.command.GenerationCommand.ProjectCoordinates;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.usecase.GenerationContext;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.tool.OutputKind;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class ProjectFileRenderer {
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SECRET_PROPERTY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,127}");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final YAMLMapper YAML = YAMLMapper.builder(new YAMLFactory())
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CompatibilityProfile profile;

    public ProjectFileRenderer(CompatibilityProfile profile) {
        if (!supports(profile)) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                    "The compatibility profile is not supported by the Spring AI 1 renderer");
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
                    implementation(\"org.springframework.boot:spring-boot-starter-web\")
                    implementation(\"org.springframework.boot:spring-boot-starter-validation\")
                    implementation(\"org.springframework.boot:spring-boot-starter-actuator\")
                    implementation(\"io.micrometer:micrometer-registry-prometheus\")
                    implementation(\"io.micrometer:micrometer-registry-otlp\")
                    implementation(\"io.micrometer:micrometer-tracing-bridge-otel\")
                    implementation(\"io.opentelemetry:opentelemetry-exporter-otlp\")
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
        server.put("annotation-scanner", Map.of("enabled", false));
        server.put("streamable-http", Map.of("mcp-endpoint", "/mcp"));
        spring.put("ai", Map.of("mcp", Map.of("server", server)));
        root.put("spring", spring);
        root.put("logging", Map.of(
                "level", Map.of("org.springframework.ai.tool.method.MethodToolCallback", "ERROR")));
        root.put("management", managementConfiguration());

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
            throw GeneratorException.system(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                    "Failed to render application configuration", exception);
        }
    }

    private Map<String, Object> managementConfiguration() {
        Map<String, Object> tracing = new LinkedHashMap<>();
        tracing.put("propagation", Map.of("type", "W3C"));
        tracing.put("sampling", Map.of(
                "probability", "${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}"));

        Map<String, Object> otlp = new LinkedHashMap<>();
        otlp.put("metrics", Map.of("export", Map.of(
                "enabled", "${MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED:false}")));
        otlp.put("tracing", Map.of("export", Map.of(
                "enabled", "${MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED:false}")));

        Map<String, Object> management = new LinkedHashMap<>();
        management.put("endpoints", Map.of("web", Map.of("exposure", Map.of(
                "include", "${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health}"))));
        management.put("endpoint", Map.of("health", Map.of("show-details", "never")));
        management.put("tracing", tracing);
        management.put("otlp", otlp);
        return management;
    }

    public String dockerfile(ProjectCoordinates coordinates) {
        String artifactId = requireCoordinates(coordinates).artifactId();
        return """
                FROM %s
                WORKDIR /app
                COPY build/libs/%s.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]
                """.formatted(profile.containerImage(), artifactId);
    }

    public String dockerignore(ProjectCoordinates coordinates) {
        String artifactId = requireCoordinates(coordinates).artifactId();
        return """
                **
                !Dockerfile
                !build/
                !build/libs/
                !build/libs/%s.jar
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
        String responseHandling = renderedResponseHandling(context.tools());
        String observability = renderedObservability();
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

                %s

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
                - Gradle %s
                - Container image: `%s`
                - Java %d
                - Spring Boot %s
                - Spring AI %s

                %s
                """.formatted(
                coordinates.artifactId(), target.javaVersion(), observability, secrets, coordinates.artifactId(), tools,
                coordinates.artifactId(), dockerEnvironment, coordinates.artifactId(), profile.id(), profile.templateVersion(),
                profile.generatorModule(), profile.runtimeVersion(), profile.gradleVersion(), profile.containerImage(),
                target.javaVersion(), target.springBootVersion(), target.springAiVersion(), responseHandling);
    }

    private String renderedObservability() {
        return """
                ## Observability

                The runtime records the `gen2spring.runtime.mcp.tool.call` and
                `gen2spring.runtime.provider.request` observations. It also records
                `gen2spring.runtime.provider.response.bytes` and the
                `gen2spring.runtime.provider.executor.active` and
                `gen2spring.runtime.provider.executor.queued` gauges. Metrics use only the finite
                `target.profile`, `outcome`, `error.category`, and `http.status.class` tag keys, and each
                meter uses its documented subset.
                Tool names and operation IDs are trace attributes, never metric tags.

                Only the health endpoint is exposed and OTLP export is disabled by default. To opt in to a
                loopback Prometheus endpoint on a separate port:

                ```bash
                MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \\
                MANAGEMENT_SERVER_PORT=9464 \\
                MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus \\
                MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true \\
                ./gradlew bootRun
                ```

                To opt in to OTLP metrics and traces, configure trusted collector endpoints and credentials,
                then enable the Boot 3 exporters explicitly:

                ```bash
                MANAGEMENT_OTLP_METRICS_EXPORT_URL="$OTLP_METRICS_URL" \\
                MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true \\
                MANAGEMENT_OTLP_TRACING_ENDPOINT="$OTLP_TRACES_URL" \\
                MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true \\
                ./gradlew bootRun
                ```

                Provider error envelopes reuse the active OpenTelemetry trace ID. If no valid span is active,
                they use a locally generated 32-character lowercase hexadecimal trace ID.
                """;
    }

    public String gitignore() {
        return "/.gradle/\n/build/\n";
    }

    public byte[] wrapperAsset(String fileName) {
        try (InputStream resource = ProjectFileRenderer.class.getResourceAsStream("/wrapper/" + fileName)) {
            if (resource == null) {
                throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                        "A required Gradle wrapper asset is missing");
            }
            return resource.readAllBytes();
        } catch (IOException exception) {
            throw GeneratorException.system(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                    "Failed to load a Gradle wrapper asset", exception);
        }
    }

    ProjectCoordinates requireContext(GenerationContext context) {
        if (context == null || !profile.equals(context.profile()) || context.request() == null) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                    "A generation context for the renderer compatibility profile is required");
        }
        return requireCoordinates(context.request().project());
    }

    private boolean supports(CompatibilityProfile candidate) {
        if (candidate == null || candidate.target() == null) {
            return false;
        }
        var target = candidate.target();
        return "generator-spring-ai-1".equals(candidate.generatorModule())
                && "9.6.1".equals(candidate.gradleVersion())
                && (target.javaVersion() == 17 || target.javaVersion() == 21)
                && "3.5.16".equals(target.springBootVersion())
                && "1.1.8".equals(target.springAiVersion())
                && "GRADLE_KOTLIN".equals(target.buildTool())
                && "MVC".equals(target.webStack())
                && "SYNC".equals(target.programmingModel())
                && "STREAMABLE_HTTP".equals(target.transport())
                && CompatibilityProfileRegistry.defaults()
                        .find(candidate.id())
                        .filter(candidate::equals)
                        .isPresent();
    }

    private ProjectCoordinates requireCoordinates(ProjectCoordinates coordinates) {
        if (coordinates == null) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render", "Project coordinates are required");
        }
        String groupId = JavaIdentifier.requirePackage(coordinates.groupId());
        String packageName = JavaIdentifier.requirePackage(coordinates.packageName());
        String artifactId = coordinates.artifactId();
        if (artifactId == null || !ARTIFACT_ID.matcher(artifactId).matches()) {
            throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                    "Project artifact IDs must use only letters, digits, dots, hyphens, and underscores");
        }
        return new ProjectCoordinates(groupId, artifactId, packageName);
    }

    private Map<String, String> secretProperties(List<ToolDefinition> tools) {
        List<SecretBinding> bindings = new ArrayList<>();
        if (tools != null) {
            for (ToolDefinition tool : tools) {
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
                throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
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
                throw GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                        "Generated secret property names must be unique");
            }
        }
        return values;
    }

    private String renderedTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "No tools were generated.";
        }
        return tools.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ToolDefinition::name))
                .map(tool -> "- `" + tool.name() + "`: " + markdownText(tool.description()))
                .reduce("", (left, right) -> left + right + "\n")
                .stripTrailing();
    }

    private String renderedResponseHandling(List<ToolDefinition> tools) {
        String contract = """
                ## Response handling

                - Operations without response normalization return the provider's successful JSON body unchanged.
                - Configured operations return `data`, optional `page.totalCount`, and optional `provider` metadata.
                - Expected provider, HTTP, timeout, availability, protocol, and local-capacity failures return one MCP Tool error JSON payload with a local trace ID.
                - Every provider response page and the aggregated Tool result remain bounded to 1 MiB.
                - Retry and pagination are disabled unless an operation policy below enables them.
                - Limit violations fail the Tool call without returning partial items.
                """.stripTrailing();
        if (tools == null || tools.isEmpty()) {
            return contract;
        }
        String policies = tools.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ToolDefinition::operationId))
                .map(this::renderedResponsePolicy)
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        if (policies.isEmpty()) {
            return contract;
        }
        return contract + "\n\n### Operation response policies\n\n" + policies;
    }

    private String renderedResponsePolicy(ToolDefinition tool) {
        StringBuilder rendered = new StringBuilder("- ")
                .append(markdownCodeSpan(tool.operationId()))
                .append("\n");
        appendPolicyValue(rendered, "output.mode",
                tool.outputKind() == OutputKind.TYPED_DTO ? "TYPED" : "GENERIC_JSON");
        var retry = tool.execution() == null ? null : tool.execution().retryPolicy();
        if (retry == null) {
            appendPolicyValue(rendered, "retry", "disabled (one attempt)");
        } else {
            try {
                appendPolicyValue(rendered, "retry.statusCodes", JSON.writeValueAsString(retry.statusCodes()));
            } catch (JsonProcessingException exception) {
                throw GeneratorException.system(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                        "Failed to render retry metadata", exception);
            }
            appendPolicyValue(rendered, "retry.networkErrors", Boolean.toString(retry.networkErrors()));
            appendPolicyValue(rendered, "retry.maxRetries", Integer.toString(retry.maxRetries()));
            appendPolicyValue(rendered, "retry.initialBackoffMillis", Long.toString(retry.initialBackoffMillis()));
            appendPolicyValue(rendered, "retry.maxBackoffMillis", Long.toString(retry.maxBackoffMillis()));
            appendPolicyValue(rendered, "retry.respectRetryAfter", Boolean.toString(retry.respectRetryAfter()));
        }
        var pagination = tool.execution() == null ? null : tool.execution().paginationPolicy();
        if (pagination == null) {
            appendPolicyValue(rendered, "pagination", "disabled (one request)");
        } else {
            appendPolicyValue(rendered, "pagination.requestParameter", pagination.requestParameter());
            appendPolicyValue(rendered, "pagination.itemsPath", pagination.itemsPointer());
            appendPolicyValue(rendered, "pagination.nextValuePath", pagination.nextValuePointer());
            appendPolicyValue(rendered, "pagination.maxPages", Integer.toString(pagination.maxPages()));
            appendPolicyValue(rendered, "pagination.maxItems", Integer.toString(pagination.maxItems()));
        }
        ResponseNormalizationPolicy policy = tool.execution() == null
                ? null : tool.execution().responseNormalization();
        if (policy != null) {
            appendPolicyPointer(rendered, "dataPath", policy.dataPointer());
            appendPolicyPointer(rendered, "successCodePath", policy.successCodePointer());
            if (!policy.successValues().isEmpty()) {
                try {
                    appendPolicyValue(rendered, "successValues", JSON.writeValueAsString(policy.successValues()));
                } catch (JsonProcessingException exception) {
                    throw GeneratorException.system(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                            "Failed to render response normalization metadata", exception);
                }
            }
            appendPolicyPointer(rendered, "errorMessagePath", policy.errorMessagePointer());
            appendPolicyPointer(rendered, "totalCountPath", policy.totalCountPointer());
        }
        return rendered.toString().stripTrailing();
    }

    private void appendPolicyPointer(StringBuilder rendered, String name, String value) {
        if (value != null) {
            appendPolicyValue(rendered, name, value);
        }
    }

    private void appendPolicyValue(StringBuilder rendered, String name, String value) {
        rendered.append("  - `").append(name).append("`: ")
                .append(markdownCodeSpan(value)).append("\n");
    }

    private String markdownCodeSpan(String value) {
        int longestRun = 0;
        int currentRun = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '`') {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        String delimiter = "`".repeat(longestRun + 1);
        boolean needsPadding = value.startsWith("`") || value.endsWith("`")
                || value.startsWith(" ") || value.endsWith(" ");
        String content = needsPadding ? " " + value + " " : value;
        return delimiter + content + delimiter;
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

    private String dockerEnvironment(List<ToolDefinition> tools) {
        java.util.TreeSet<String> values = new java.util.TreeSet<>();
        if (tools != null) {
            for (ToolDefinition tool : tools) {
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
