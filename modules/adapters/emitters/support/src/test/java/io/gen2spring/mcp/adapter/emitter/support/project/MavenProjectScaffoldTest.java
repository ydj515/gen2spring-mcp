package io.gen2spring.mcp.adapter.emitter.support.project;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.BuildToolchain;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MavenProjectScaffoldTest {
    @Test
    void rendersVerifiedOnlyScriptWrapperAndDeterministicMavenFiles() {
        Map<String, byte[]> files = new MavenProjectScaffold().render(model());

        assertEquals(Set.of(
                ".dockerignore",
                ".gitignore",
                ".mvn/wrapper/maven-wrapper.properties",
                "Dockerfile",
                "README.md",
                "mvnw",
                "mvnw.cmd",
                "pom.xml",
                "src/main/resources/application.yml"), files.keySet());
        assertEquals("2430eaa983c5b683466567b54a43ff8efe2f52e29cd5704f419b1b2683c2b99b",
                sha256(files.get("mvnw")));
        assertEquals("290eb2329eb1e189f2b6aca12ef34dbd055faff30213fac4ba58b8457d40c458",
                sha256(files.get("mvnw.cmd")));
        assertTrue(text(files, ".mvn/wrapper/maven-wrapper.properties")
                .contains("distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip"));
        assertTrue(text(files, ".mvn/wrapper/maven-wrapper.properties")
                .contains("distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce"));
        assertFalse(files.containsKey(".mvn/wrapper/maven-wrapper.jar"));
        assertTrue(text(files, "pom.xml").contains("<maven.compiler.release>21</maven.compiler.release>"));
        assertTrue(text(files, "pom.xml").contains("<version>4.1.0</version>"));
        assertTrue(text(files, "pom.xml").contains("<spring-ai.version>2.0.0</spring-ai.version>"));
        assertTrue(text(files, "pom.xml").contains(
                "<mainClass>com.example.weather.application.WeatherMcpApplication</mainClass>"));
        assertTrue(text(files, "pom.xml").contains("<finalName>${project.artifactId}</finalName>"));
        assertTrue(text(files, "README.md").contains("./mvnw spring-boot:run"));
        assertTrue(text(files, "README.md").contains("./mvnw test"));
        assertTrue(text(files, "Dockerfile").contains("COPY target/weather-mcp-server.jar /app/app.jar"));
    }

    @Test
    void rendersIdenticalMavenBytesAcrossRepeatedCalls() {
        var scaffold = BuildProjectScaffoldRegistry.defaults().require("MAVEN");

        Map<String, byte[]> first = scaffold.render(model());
        Map<String, byte[]> second = scaffold.render(model());

        assertEquals(first.keySet(), second.keySet());
        first.forEach((path, bytes) -> assertEquals(sha256(bytes), sha256(second.get(path)), path));
    }

    private ProjectScaffoldModel model() {
        CompatibilityProfile gradle = CompatibilityProfile.p0();
        CompatibilityProfile maven = new CompatibilityProfile(
                "spring-ai-2.0-java21-maven-mvc-streamable",
                new CompatibilityProfile.TargetPlatform(
                        21, "4.1.0", "2.0.0", "MAVEN", "MVC", "SYNC", "STREAMABLE_HTTP"),
                gradle.generatorModule(),
                gradle.templateVersion(),
                gradle.runtimeVersion(),
                new BuildToolchain("3.9.16", "3.3.4"),
                gradle.containerImage());
        return new ProjectScaffoldModel(
                "com.example",
                "weather-mcp-server",
                "com.example.weather",
                "WeatherMcpApplication",
                maven,
                List.of(
                        dependency("org.springframework.ai", "spring-ai-starter-mcp-server-webmvc"),
                        dependency("org.springframework.boot", "spring-boot-restclient"),
                        dependency("org.springframework.boot", "spring-boot-starter-validation"),
                        dependency("org.springframework.boot", "spring-boot-starter-actuator"),
                        dependency("org.springframework.boot", "spring-boot-starter-opentelemetry"),
                        dependency("io.micrometer", "micrometer-registry-prometheus"),
                        dependency("io.micrometer", "micrometer-registry-otlp"),
                        new ProjectScaffoldModel.Dependency(
                                "org.springframework.boot",
                                "spring-boot-starter-test",
                                ProjectScaffoldModel.Scope.TEST_IMPLEMENTATION)),
                "spring:\n  application:\n    name: weather-mcp-server\n",
                new ProjectScaffoldModel.ProjectDocumentation(
                        "Generated Spring AI MCP server. It exposes the selected OpenAPI operations as\n"
                                + "deterministic MCP tools and invokes the configured provider over HTTP.",
                        List.of("KMA_SERVICE_KEY"),
                        " -e PROVIDER_BASE_URL=https://api.example.test -e KMA_SERVICE_KEY",
                        "- `weather_get_forecast`: Get a weather forecast.",
                        "## Observability\n\nObservability details.",
                        "## Response handling\n\nResponse handling details."));
    }

    private ProjectScaffoldModel.Dependency dependency(String groupId, String artifactId) {
        return new ProjectScaffoldModel.Dependency(
                groupId, artifactId, ProjectScaffoldModel.Scope.IMPLEMENTATION);
    }

    private String text(Map<String, byte[]> files, String path) {
        return new String(files.get(path), UTF_8);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
