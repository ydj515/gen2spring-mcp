package io.gen2spring.mcp.adapter.emitter.support;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class MavenProjectScaffold implements BuildProjectScaffold {
    @Override
    public Map<String, byte[]> render(ProjectScaffoldModel model) {
        requireModel(model);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(".dockerignore", utf8(dockerignore(model.artifactId())));
        files.put(".gitignore", utf8(gitignore()));
        files.put(".mvn/wrapper/maven-wrapper.properties", wrapperAsset("maven-wrapper.properties"));
        files.put("Dockerfile", utf8(dockerfile(model)));
        files.put("README.md", utf8(readme(model)));
        files.put("mvnw", wrapperAsset("mvnw"));
        files.put("mvnw.cmd", wrapperAsset("mvnw.cmd"));
        files.put("pom.xml", utf8(pom(model)));
        files.put("src/main/resources/application.yml", utf8(model.applicationYaml()));
        return Collections.unmodifiableMap(files);
    }

    public String pom(ProjectScaffoldModel model) {
        requireModel(model);
        var target = model.profile().target();
        String dependencies = model.dependencies().stream()
                .map(this::dependency)
                .collect(Collectors.joining("\n"));
        String source = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>

                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>%s</version>
                    <relativePath/>
                  </parent>

                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.1.0</version>

                  <properties>
                    <maven.compiler.release>%d</maven.compiler.release>
                    <spring-ai.version>%s</spring-ai.version>
                  </properties>

                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.ai</groupId>
                        <artifactId>spring-ai-bom</artifactId>
                        <version>${spring-ai.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>

                  <dependencies>
                %s
                  </dependencies>

                  <build>
                    <finalName>${project.artifactId}</finalName>
                    <plugins>
                      <plugin>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-maven-plugin</artifactId>
                        <configuration>
                          <mainClass>%s.application.%s</mainClass>
                        </configuration>
                        <executions>
                          <execution>
                            <goals>
                              <goal>repackage</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <configuration>
                          <useModulePath>false</useModulePath>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(
                target.springBootVersion(),
                model.groupId(),
                model.artifactId(),
                target.javaVersion(),
                target.springAiVersion(),
                dependencies,
                model.packageName(),
                model.applicationClassName());
        if (model.dependencies().stream().anyMatch(dependency -> dependency.groupId().equals("io.modelcontextprotocol.sdk"))) {
            source = source.replace("    <spring-ai.version>" + target.springAiVersion() + "</spring-ai.version>\n", "");
            int start = source.indexOf("  <dependencyManagement>");
            int end = source.indexOf("  </dependencyManagement>", start) + "  </dependencyManagement>\n".length();
            source = source.substring(0, start) + source.substring(end);
        }
        return source;
    }

    private String dependency(ProjectScaffoldModel.Dependency dependency) {
        String scope = switch (dependency.scope()) {
            case IMPLEMENTATION -> "";
            case RUNTIME_ONLY -> "\n      <scope>runtime</scope>";
            case TEST_IMPLEMENTATION -> "\n      <scope>test</scope>";
        };
        return """
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>%s
                    </dependency>""".formatted(
                dependency.groupId(), dependency.artifactId(),
                (dependency.version() == null ? "" : "\n      <version>" + dependency.version() + "</version>") + scope);
    }

    private String readme(ProjectScaffoldModel model) {
        var documentation = model.projectDescription();
        var profile = model.profile();
        var target = profile.target();
        String secrets = documentation.providerEnvironmentVariables().isEmpty()
                ? "No provider secrets are required."
                : "Set these environment variables before starting:\n\n"
                        + documentation.providerEnvironmentVariables().stream()
                                .map(value -> "- `" + value + "`")
                                .reduce("", (left, right) -> left + right + "\n");
        String observability = documentation.observability()
                .replace("./gradlew bootRun", "./mvnw spring-boot:run");
        return """
                # %s

                %s

                ## Run

                Requirements: Java %d.

                ```bash
                ./mvnw spring-boot:run
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
                ./mvnw test
                ```

                ## Docker

                ```bash
                ./mvnw package
                docker build -t %s .
                docker run --rm -p 8080:8080%s %s
                ```

                ## Generator information

                - Compatibility profile: `%s`
                - Template: `%s`
                - Generator module: `%s`
                - Runtime version: `%s`
                - Maven %s (Wrapper %s)
                - Container image: `%s`
                - Java %d
                - Spring Boot %s
                - Spring AI %s

                %s
                """.formatted(
                model.artifactId(),
                documentation.summary(),
                target.javaVersion(),
                observability,
                secrets,
                model.artifactId(),
                documentation.tools(),
                model.artifactId(),
                documentation.dockerEnvironment(),
                model.artifactId(),
                profile.id(),
                profile.templateVersion(),
                profile.generatorModule(),
                profile.runtimeVersion(),
                profile.buildToolchain().distributionVersion(),
                profile.buildToolchain().wrapperVersion(),
                profile.containerImage(),
                target.javaVersion(),
                target.springBootVersion(),
                target.springAiVersion(),
                documentation.responseHandling());
    }

    private String dockerfile(ProjectScaffoldModel model) {
        return """
                FROM %s
                WORKDIR /app
                COPY target/%s.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(model.profile().containerImage(), model.artifactId());
    }

    private String dockerignore(String artifactId) {
        return """
                **
                !Dockerfile
                !target/
                !target/%s.jar
                """.formatted(artifactId);
    }

    private String gitignore() {
        return "/target/\n";
    }

    private byte[] wrapperAsset(String fileName) {
        try (InputStream resource = MavenProjectScaffold.class
                .getResourceAsStream("/wrapper/maven/" + fileName)) {
            if (resource == null) {
                throw GeneratorException.user(
                        SOURCE_GENERATION_FAILED,
                        "project-scaffold",
                        "A required Maven wrapper asset is missing");
            }
            return resource.readAllBytes();
        } catch (IOException exception) {
            throw GeneratorException.system(
                    SOURCE_GENERATION_FAILED,
                    "project-scaffold",
                    "Failed to load a Maven wrapper asset",
                    exception);
        }
    }

    private void requireModel(ProjectScaffoldModel model) {
        if (model == null || model.profile().target() == null
                || !"MAVEN".equals(model.profile().target().buildTool())) {
            throw GeneratorException.user(
                    SOURCE_GENERATION_FAILED,
                    "project-scaffold",
                    "A Maven project scaffold model is required");
        }
    }

    private byte[] utf8(String value) {
        return value.getBytes(UTF_8);
    }
}
