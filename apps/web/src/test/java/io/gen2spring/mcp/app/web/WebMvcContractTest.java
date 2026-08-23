package io.gen2spring.mcp.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.app.web.job.GenerationJobManager;
import io.gen2spring.mcp.app.web.security.WebSecurityConfiguration;
import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import io.gen2spring.mcp.application.usecase.GenerationProgress;
import io.gen2spring.mcp.application.usecase.ProgressStatus;
import io.gen2spring.mcp.application.validation.ValidationStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(classes = Gen2SpringWebApplication.class)
@AutoConfigureMockMvc
@Import(WebMvcContractTest.FastJobConfiguration.class)
class WebMvcContractTest {
    private static final int PORT = 18443;
    private static final String HOST = "127.0.0.1:" + PORT;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    void rendersTheThreeStepThymeleafEditorAtBothLocalRoutes() throws Exception {
        for (String route : List.of("/", "/editor")) {
            mockMvc.perform(get(route).with(localRequest()))
                .andExpect(status().isOk())
                .andExpect(view().name("editor"))
                .andExpect(content().string(containsString(">1. OpenAPI 파일</h2>")))
                .andExpect(content().string(containsString(">2. API endpoint 선택</h2>")))
                .andExpect(content().string(containsString(">3. 생성 설정</h2>")))
                .andExpect(content().string(containsString("id=\"selected-tool-list\"")))
                .andExpect(content().string(containsString("id=\"generation-summary\"")))
                .andExpect(content().string(not(containsString("<h2>4."))))
                .andExpect(content().string(not(containsString("<h2>5."))))
                .andExpect(content().string(containsString("name=\"app-mode\" content=\"local\"")))
                .andExpect(content().string(containsString("name=\"csrf-token\"")))
                .andExpect(content().string(containsString("name=\"csrf-header\"")))
                .andExpect(content().string(not(containsString("__GEN2SPRING_TOKEN__"))))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy",
                        WebSecurityConfiguration.CONTENT_SECURITY_POLICY));
        }
    }

    @Test
    void preservesTheProfilesUploadAndPreviewApi() throws Exception {
        mockMvc.perform(get("/api/profiles").with(localRequest()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.profiles.length()").value(8))
                .andExpect(jsonPath("$.profiles[0].id")
                        .value("spring-ai-1.1-java17-maven-mvc-streamable"))
                .andExpect(jsonPath("$.profiles[0].buildTool.type").value("MAVEN"))
                .andExpect(jsonPath("$.profiles[0].buildTool.distributionVersion").value("3.9.16"))
                .andExpect(jsonPath("$.profiles[0].buildTool.wrapperVersion").value("3.3.4"))
                .andExpect(jsonPath("$.profiles[0].gradleVersion").doesNotExist());

        JsonNode uploaded = json.readTree(mockMvc.perform(post("/api/specifications")
                        .with(localRequest())
                        .with(csrf())
                        .header("X-Specification-Name", "weather.yaml")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes(specification())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operations[0].operationId").value("getForecast"))
                .andExpect(jsonPath("$.operations[0].status").value("SUPPORTED"))
                .andExpect(jsonPath("$.operations[0].supported").value(true))
                .andExpect(jsonPath("$.operations[0].issues.length()").value(0))
                .andExpect(jsonPath("$.counts.total").value(1))
                .andExpect(jsonPath("$.counts.supported").value(1))
                .andExpect(jsonPath("$.counts.supportedWithWarning").value(0))
                .andExpect(jsonPath("$.counts.unsupported").value(0))
                .andExpect(jsonPath("$.file.name").value("weather.yaml"))
                .andExpect(jsonPath("$.file.byteSize").value(
                        specification().getBytes(StandardCharsets.UTF_8).length))
                .andExpect(jsonPath("$.securitySchemes").isMap())
                .andReturn().getResponse().getContentAsByteArray());
        String specificationId = uploaded.path("id").textValue();
        assertTrue(specificationId.matches("[a-f0-9]{64}"));

        mockMvc.perform(post("/api/specifications/{id}/preview", specificationId)
                        .with(localRequest())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(configuration())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id")
                        .value("spring-ai-2.0-java21-mvc-streamable"))
                .andExpect(jsonPath("$.profile.buildTool.type").value("GRADLE_KOTLIN"))
                .andExpect(jsonPath("$.profile.buildTool.distributionVersion").value("9.6.1"))
                .andExpect(jsonPath("$.profile.gradleVersion").value("9.6.1"))
                .andExpect(jsonPath("$.tools[0].name").value("weather_get_forecast"))
                .andExpect(content().string(not(containsString("representative-private-value"))));
    }

    @Test
    void localAnalysisKeepsTheSuppliedOpenApiVersionsDecisionEquivalent() throws Exception {
        JsonNode analysis30 = uploadPairedFixture("swagger-3.0.yml", "3.0.4");
        JsonNode analysis31 = uploadPairedFixture("swagger-3.1.yml", "3.1.2");

        assertEquals(analysis30.path("operations"), analysis31.path("operations"));
        assertEquals(analysis30.path("securitySchemes"), analysis31.path("securitySchemes"));
        assertEquals(analysis30.path("warnings"), analysis31.path("warnings"));
    }

    @Test
    void preservesJobStatusArtifactAndDeleteRoutes() throws Exception {
        String specificationId = uploadSpecification();
        JsonNode accepted = json.readTree(mockMvc.perform(post("/api/specifications/{id}/jobs", specificationId)
                        .with(localRequest())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(configuration())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andReturn().getResponse().getContentAsByteArray());
        String jobId = accepted.path("id").textValue();

        JsonNode terminal = awaitTerminal(jobId);
        assertEquals("VALIDATED", terminal.path("state").textValue());
        assertEquals(List.of("archive", "manifest", "report"),
                json.convertValue(terminal.path("downloads"), json.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));

        mockMvc.perform(get("/api/jobs/{id}/archive", jobId).with(localRequest()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + jobId + ".zip\""))
                .andExpect(content().bytes("zip".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        mockMvc.perform(delete("/api/jobs/{id}", jobId)
                        .with(localRequest())
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/jobs/{id}", jobId).with(localRequest()))
                .andExpect(status().isNotFound());
    }

    @Test
    void mapsSpringAndGeneratorFailuresToFixedNonLeakingJson() throws Exception {
        mockMvc.perform(post("/api/specifications")
                        .with(localRequest())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes(specification())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));

        mockMvc.perform(post("/api/specifications")
                        .with(localRequest())
                        .with(csrf())
                        .header("X-Specification-Name", "private-marker.yaml")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(bytes("private-body")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("CONTENT_TYPE_UNSUPPORTED"))
                .andExpect(content().string(not(containsString("private-marker"))))
                .andExpect(content().string(not(containsString("private-body"))));

        mockMvc.perform(get("/api/private-marker").with(localRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"))
                .andExpect(content().string(not(containsString("private-marker"))));

        mockMvc.perform(get("/api/profiles?private-marker=private-value").with(localRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"))
                .andExpect(content().string(not(containsString("private-marker"))))
                .andExpect(content().string(not(containsString("private-value"))));

        mockMvc.perform(post("/api/specifications")
                        .with(localRequest())
                        .with(csrf())
                        .header("X-Specification-Name", "weather.yaml")
                        .contentType("application/octet-stream; private=marker")
                        .content(bytes(specification())))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("CONTENT_TYPE_UNSUPPORTED"))
                .andExpect(content().string(not(containsString("marker"))));

        mockMvc.perform(post("/api/specifications/{id}/preview", "0".repeat(64))
                        .with(localRequest())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes("{\"private-marker\":\"private-value\"}")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("private-marker"))))
                .andExpect(content().string(not(containsString("private-value"))));
    }

    private String uploadSpecification() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/specifications")
                        .with(localRequest())
                        .with(csrf())
                        .header("X-Specification-Name", "weather.yaml")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes(specification())))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("id").textValue();
    }

    private JsonNode uploadPairedFixture(String fileName, String version) throws Exception {
        byte[] source = Files.readAllBytes(repositoryRoot().resolve(fileName));
        JsonNode analysis = json.readTree(mockMvc.perform(post("/api/specifications")
                        .with(localRequest())
                        .with(csrf())
                        .header("X-Specification-Name", fileName)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(source))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.openApiVersion").value(version))
                .andExpect(jsonPath("$.counts.total").value(38))
                .andExpect(jsonPath("$.counts.supported").value(34))
                .andExpect(jsonPath("$.counts.supportedWithWarning").value(0))
                .andExpect(jsonPath("$.counts.unsupported").value(4))
                .andExpect(jsonPath("$.file.name").value(fileName))
                .andExpect(jsonPath("$.file.byteSize").value(source.length))
                .andReturn().getResponse().getContentAsByteArray());
        JsonNode customers = java.util.stream.StreamSupport.stream(
                        analysis.path("operations").spliterator(), false)
                .filter(operation -> operation.path("operationId").asText().equals("getCustomers"))
                .findFirst().orElseThrow();
        assertEquals("SUPPORTED", customers.path("status").asText());
        assertTrue(customers.path("issues").isEmpty());
        assertOperationDecision(analysis, "listSchemaFixtures", "SUPPORTED", null);
        assertOperationDecision(analysis, "getNullablePathFixture", "UNSUPPORTED",
                "PARAMETER_NULLABLE_PATH_UNSUPPORTED");
        assertOperationDecision(analysis, "submitConflictingAllOf", "UNSUPPORTED",
                "SCHEMA_CONSTRAINT_UNSUPPORTED");
        assertOperationDecision(analysis, "submitCompositionBudgetOverflow", "UNSUPPORTED",
                "SCHEMA_COMPOSITION_UNSUPPORTED");
        return analysis;
    }

    private void assertOperationDecision(JsonNode analysis, String operationId, String status, String issueCode) {
        JsonNode operation = java.util.stream.StreamSupport.stream(
                        analysis.path("operations").spliterator(), false)
                .filter(candidate -> candidate.path("operationId").asText().equals(operationId))
                .findFirst().orElseThrow();
        assertEquals(status, operation.path("status").asText());
        if (issueCode == null) {
            assertTrue(operation.path("issues").isEmpty());
        } else {
            assertEquals(issueCode, operation.path("issues").get(0).path("code").asText());
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate the repository root");
        }
        return current;
    }

    private JsonNode awaitTerminal(String jobId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mockMvc.perform(get("/api/jobs/{id}", jobId).with(localRequest()))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode status = json.readTree(result.getResponse().getContentAsByteArray());
            if (List.of("VALIDATED", "UNVERIFIED", "FAILED").contains(status.path("state").textValue())) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("MVC job did not finish");
    }

    private RequestPostProcessor localRequest() {
        return request -> {
            request.setRemoteAddr("127.0.0.1");
            request.setLocalAddr("127.0.0.1");
            request.setLocalPort(PORT);
            request.addHeader("Host", HOST);
            request.addHeader("Origin", "http://" + HOST);
            return request;
        };
    }

    private String specification() {
        return """
                openapi: 3.0.3
                info: {title: Weather, version: 1.0.0}
                servers: [{url: https://weather.example.test}]
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      summary: Get forecast
                      parameters:
                        - name: city
                          in: query
                          required: true
                          schema: {type: string}
                      responses:
                        '200': {description: Success}
                """;
    }

    private String configuration() {
        return """
                {
                  "project":{"groupId":"com.example","artifactId":"weather-mcp-server",
                    "packageName":"com.example.weather"},
                  "provider":"weather","domain":"forecast",
                  "targetProfileId":"spring-ai-2.0-java21-mvc-streamable",
                  "validationLevel":"MCP_PROTOCOL",
                  "validation":{"toolCall":{"operationId":"getForecast",
                    "arguments":{"city":"representative-private-value"}}},
                  "operations":[{"operationId":"getForecast","enabled":true,
                    "toolName":"weather_get_forecast","toolDescription":"Get forecast","parameters":{}}]
                }
                """;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FastJobConfiguration {
        @Bean(destroyMethod = "close")
        @Primary
        GenerationJobManager fastJobManager(Path privateTemporaryParent, Clock webClock) {
            return new GenerationJobManager(
                    privateTemporaryParent,
                    (specification, request, output, progress) -> {
                        for (String stage : GenerationProgress.STAGES) {
                            progress.onProgress(new GenerationProgress(stage, ProgressStatus.RUNNING));
                            progress.onProgress(new GenerationProgress(stage, ProgressStatus.SUCCESS));
                        }
                        Files.createDirectory(output);
                        Files.writeString(output.resolve("GENERATION_MANIFEST.json"), "{}");
                        Files.writeString(output.resolve("VALIDATION_REPORT.json"), "{}");
                        Path archive = Files.writeString(
                                output.resolveSibling(output.getFileName() + ".zip"), "zip");
                        return new GenerationOutcome(
                                output, archive, ValidationStatus.VALIDATED, "checksum");
                    },
                    webClock,
                    Duration.ofHours(1),
                    ignored -> {});
        }
    }
}
