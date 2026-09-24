package io.gen2spring.mcp.app.web.presentation.hosted;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.app.web.config.JobEventStreamConfiguration;
import io.gen2spring.mcp.app.web.config.security.HostedSecurityConfiguration;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedSubmissionService;
import io.gen2spring.mcp.app.web.presentation.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.presentation.error.WebErrorResponseWriter;
import io.gen2spring.mcp.application.generation.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.hosted.account.port.out.AccountStore;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.CatalogEndpoint;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.Compatibility;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ToolChange;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiffService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogPage;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.ToolDetails;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.job.JobView;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.application.generation.usecase.GenerationPreview;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(
        controllers = {HostedSpecificationController.class, HostedJobController.class,
                HostedArtifactController.class, HostedToolCatalogController.class},
        properties = "gen2spring.mode=hosted")
// A WebMvcTest slice does not load plain @Configuration classes, and the job
// controller needs the shared event stream, so import it the way production does.
@Import({HostedSecurityConfiguration.class, HostedWebMvcContractTest.SecurityBeans.class,
        WebErrorMapper.class, WebErrorResponseWriter.class, JobEventStreamConfiguration.class})
class HostedWebMvcContractTest {
    private static final String ISSUER = "https://issuer.example";
    private static final AccountId OWNER = new AccountId(UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));

    @Autowired MockMvc mvc;
    @MockitoBean AccountStore accounts;
    @MockitoBean HostedSubmissionService submissions;
    @MockitoBean HostedJobService jobs;
    @MockitoBean HostedResourceStore resources;
    @MockitoBean ObjectStorage storage;
    @MockitoBean ToolCatalogService catalogs;
    @MockitoBean CatalogDiffService catalogDiffs;

    @BeforeEach
    void account() {
        when(accounts.findOrCreate(eq(ISSUER), eq("subject-1"), any())).thenReturn(OWNER);
    }

    @Test
    void requiresOidcAndCsrfThenMapsIssuerAndSubjectToTheOwnedRequest() throws Exception {
        when(resources.specifications(OWNER, 51, Optional.empty())).thenReturn(List.of());

        mvc.perform(get("/api/specifications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Content-Security-Policy", containsString("font-src 'self'")));
        mvc.perform(get("/api/specifications").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/specifications/imports")
                        .with(user()).header("Idempotency-Key", "request-1")
                        .contentType("application/json").content("{\"url\":\"https://public.example/openapi.yaml\"}"))
                .andExpect(status().isForbidden());

        JobView job = new JobView(
                new JobId(UUID.fromString("1a803410-a22a-4bc6-b951-7dbc301ae800")), OWNER,
                JobKind.SPEC_IMPORT, JobStatus.QUEUED, Optional.empty(), 0, false);
        when(submissions.importUrl(OWNER, "request-1", "https://public.example/openapi.yaml"))
                .thenReturn(new CreateJobResult(job, false));
        mvc.perform(post("/api/specifications/imports")
                        .with(user()).with(csrf()).header("Idempotency-Key", "request-1")
                        .contentType("application/json").content("{\"url\":\"https://public.example/openapi.yaml\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(job.id().value().toString()));
    }

    @Test
    void returnsOneSafe404ForCrossOwnerJobLookups() throws Exception {
        String id = "1a803410-a22a-4bc6-b951-7dbc301ae800";
        when(resources.job(OWNER, new JobId(UUID.fromString(id)))).thenReturn(Optional.empty());

        mvc.perform(get("/api/jobs/{id}", id).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(content().string(not(containsString(OWNER.value().toString()))));
    }

    @Test
    void exposesAuthenticatedCatalogQueriesWithSafeOwnerScopedFailures() throws Exception {
        when(catalogs.list(OWNER, 50, Optional.empty()))
                .thenReturn(new CatalogPage(List.of(), Optional.empty()));
        when(catalogs.list(OWNER, 100, Optional.empty()))
                .thenReturn(new CatalogPage(List.of(), Optional.empty()));
        when(catalogs.list(OWNER, 101, Optional.empty()))
                .thenThrow(new ToolCatalogService.ToolCatalogQueryInvalid());

        mvc.perform(get("/api/tool-catalogs"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/tool-catalogs").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verify(catalogs).list(OWNER, 50, Optional.empty());
        mvc.perform(get("/api/tool-catalogs").with(user()).param("limit", "100"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tool-catalogs").with(user()).param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CATALOG_QUERY_INVALID"));
        for (String invalidLimit : List.of("abc", "2147483648")) {
            mvc.perform(get("/api/tool-catalogs").with(user()).param("limit", invalidLimit))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CATALOG_QUERY_INVALID"))
                    .andExpect(jsonPath("$.error.stage").value("CATALOG_LOOKUP"))
                    .andExpect(content().string(not(containsString(invalidLimit))));
        }

        mvc.perform(get("/api/tool-catalogs").with(user()).param("cursor", "private-marker"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CATALOG_QUERY_INVALID"))
                .andExpect(content().string(not(containsString("private-marker"))));
        mvc.perform(get("/api/tool-catalogs/not-a-uuid").with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.stage").value("CATALOG_LOOKUP"));

        UUID missing = UUID.randomUUID();
        when(catalogs.require(OWNER, missing)).thenThrow(new ToolCatalogService.ToolCatalogNotFound());
        mvc.perform(get("/api/tool-catalogs/{id}", missing).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(content().string(not(containsString(missing.toString()))))
                .andExpect(content().string(not(containsString(OWNER.value().toString()))));
    }

    @Test
    void exposesOwnerScopedCatalogDiffsWithFixedFailureBoundaries() throws Exception {
        UUID source = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
        UUID target = UUID.fromString("8f5a48fd-f34b-4ba5-b749-eb79008370d5");
        when(catalogDiffs.compare(OWNER, source, target)).thenReturn(new CatalogDiff(
                new CatalogEndpoint(source, 1, "a".repeat(64), "b".repeat(64)),
                new CatalogEndpoint(target, 2, "c".repeat(64), "d".repeat(64)),
                Compatibility.COMPATIBLE,
                List.of(new ToolChange("forecast", ChangeKind.TOOL_ADDED, "tool")),
                "e".repeat(64)));

        mvc.perform(get("/api/tool-catalogs/{catalogId}/diff", source)
                        .queryParam("targetCatalogId", target.toString()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/tool-catalogs/{catalogId}/diff", source)
                        .with(user()).queryParam("targetCatalogId", target.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.revision").value(1))
                .andExpect(jsonPath("$.target.revision").value(2))
                .andExpect(jsonPath("$.compatibility").value("COMPATIBLE"))
                .andExpect(jsonPath("$.changes[0].kind").value("TOOL_ADDED"))
                .andExpect(jsonPath("$.checksum").value("e".repeat(64)));

        when(catalogDiffs.compare(OWNER, source, target))
                .thenThrow(new CatalogDiffService.CatalogDiffNotFound());
        mvc.perform(get("/api/tool-catalogs/{catalogId}/diff", source)
                        .with(user()).queryParam("targetCatalogId", target.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        doThrow(new CatalogDiffService.CatalogDiffUnavailable())
                .when(catalogDiffs).compare(OWNER, source, target);
        mvc.perform(get("/api/tool-catalogs/{catalogId}/diff", source)
                        .with(user()).queryParam("targetCatalogId", target.toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("CATALOG_DIFF_UNAVAILABLE"));
        mvc.perform(get("/api/tool-catalogs/{catalogId}/diff", "invalid")
                        .with(user()).queryParam("targetCatalogId", target.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CATALOG_DIFF_QUERY_INVALID"));
    }

    @Test
    void returnsCanonicalCatalogAndToolDocumentsWithoutPrivateCoordinates() throws Exception {
        RuntimeMetadataArtifact metadata = runtimeMetadata();
        UUID catalogId = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
        CatalogSummary summary = new CatalogSummary(
                catalogId,
                new JobId(UUID.fromString("1a803410-a22a-4bc6-b951-7dbc301ae800")),
                RuntimeMetadataDocument.VERSION,
                metadata.checksum(),
                1,
                Instant.parse("2026-08-21T00:00:00Z"));
        when(catalogs.require(OWNER, catalogId)).thenReturn(new CatalogDetails(
                summary, metadata.document().specificationChecksum(), metadata));
        when(catalogs.requireTool(OWNER, catalogId, "weather"))
                .thenReturn(new ToolDetails(summary, metadata.document().tools().getFirst()));

        mvc.perform(get("/api/tool-catalogs/{id}", catalogId).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.metadataVersion").value("1.0"))
                .andExpect(jsonPath("$.metadata.checksum").value(metadata.checksum()))
                .andExpect(content().string(not(containsString("KMA_SERVICE_KEY"))))
                .andExpect(content().string(not(containsString("objectKey"))))
                .andExpect(content().string(not(containsString("worker-01"))));
        mvc.perform(get("/api/tool-catalogs/{id}/tools/weather", catalogId).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tool.name").value("weather"))
                .andExpect(jsonPath("$.tool.http.path").value("/weather"));

        when(catalogs.requireTool(OWNER, catalogId, "Bad-Tool"))
                .thenThrow(new ToolCatalogService.ToolCatalogQueryInvalid());
        mvc.perform(get("/api/tool-catalogs/{id}/tools/Bad-Tool", catalogId).with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CATALOG_QUERY_INVALID"));
    }

    @Test
    void exposesOwnerAuthorizedUploadAnalysisAndPlanningPreview() throws Exception {
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        var analysis = new SpecificationAnalysisView(
                "a".repeat(64), "3.1.1", "yaml", URI.create("https://weather.example.test"),
                new SpecificationAnalysisView.Counts(0, 0, 0, 0),
                List.of(), Map.of(), List.of());
        var hosted = new HostedSubmissionService.HostedSpecificationAnalysis(
                id, "weather.yml", 123, analysis);
        when(submissions.upload(eq(OWNER), any(), eq("application/yaml"), eq("weather.yml")))
                .thenReturn(hosted);
        when(submissions.analysis(OWNER, id)).thenReturn(hosted);
        when(submissions.preview(eq(OWNER), eq(id), any())).thenReturn(new GenerationPreview(
                CompatibilityProfileRegistry.defaults()
                        .find("spring-ai-2.0-java21-mvc-streamable").orElseThrow(),
                List.of(), List.of(), List.of(), List.of("README.md")));

        mvc.perform(post("/api/specifications/uploads")
                        .with(user()).with(csrf())
                        .header("X-Specification-Name", "weather.yml")
                        .contentType("application/yaml")
                        .content("openapi: 3.1.1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.openApiVersion").value("3.1.1"))
                .andExpect(jsonPath("$.file.name").value("weather.yml"));

        mvc.perform(get("/api/specifications/{id}/analysis", id.value()).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.total").value(0));

        mvc.perform(post("/api/specifications/{id}/preview", id.value())
                        .with(user()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/specifications/{id}/preview", id.value())
                        .with(user()).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id")
                        .value("spring-ai-2.0-java21-mvc-streamable"));
    }

    @Test
    void hostedAnalysisKeepsTheSuppliedOpenApiVersionsDecisionEquivalent() throws Exception {
        var hosted30 = hostedFixture("swagger-3.0.yml");
        var hosted31 = hostedFixture("swagger-3.1.yml");
        when(submissions.upload(eq(OWNER), any(), eq("application/yaml"), eq("swagger-3.0.yml")))
                .thenReturn(hosted30);
        when(submissions.upload(eq(OWNER), any(), eq("application/yaml"), eq("swagger-3.1.yml")))
                .thenReturn(hosted31);

        var response30 = uploadHostedFixture("swagger-3.0.yml", "3.0.4");
        var response31 = uploadHostedFixture("swagger-3.1.yml", "3.1.2");

        assertEquals(response30.path("operations"), response31.path("operations"));
        assertEquals(response30.path("securitySchemes"), response31.path("securitySchemes"));
        assertEquals(response30.path("warnings"), response31.path("warnings"));
    }

    @Test
    void startsOneIdempotentHostedGenerationFromTheGuidedEditor() throws Exception {
        SpecificationId specificationId = new SpecificationId(UUID.randomUUID());
        UUID predecessorCatalogId = UUID.fromString("2c7fab42-1acd-4f90-bd3f-f7de5ec81edb");
        JobView job = new JobView(
                new JobId(UUID.randomUUID()), OWNER, JobKind.GENERATION, JobStatus.QUEUED,
                Optional.of(specificationId), Optional.of(predecessorCatalogId), 0, false);
        when(submissions.generate(
                eq(OWNER), eq(specificationId), eq(Optional.of(predecessorCatalogId)),
                eq("editor-request-1"), any()))
                .thenReturn(new CreateJobResult(job, false));

        mvc.perform(post("/api/jobs")
                        .with(user()).with(csrf())
                        .header("Idempotency-Key", "editor-request-1")
                        .contentType("application/json")
                        .content("""
                                {"specificationId":"%s","predecessorCatalogId":"%s",
                                 "configuration":{"operations":[]}}
                                """.formatted(specificationId.value(), predecessorCatalogId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(job.id().value().toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.replayed").value(false));
        verify(submissions).generate(
                eq(OWNER), eq(specificationId), eq(Optional.of(predecessorCatalogId)),
                eq("editor-request-1"), any());
    }

    @Test
    void returnsOneSafe404ForCrossOwnerSpecificationAnalysis() throws Exception {
        String id = UUID.randomUUID().toString();
        when(submissions.analysis(OWNER, new SpecificationId(UUID.fromString(id))))
                .thenThrow(new HostedSubmissionService.HostedSpecificationNotFound());

        mvc.perform(get("/api/specifications/{id}/analysis", id).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(content().string(not(containsString(OWNER.value().toString()))));
    }

    private RequestPostProcessor user() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1"));
    }

    private HostedSubmissionService.HostedSpecificationAnalysis hostedFixture(String fileName) throws Exception {
        Path source = repositoryRoot().resolve(fileName);
        var analysis = SpecificationAnalysisView.from(
                new SwaggerOpenApiAnalyzer().analyze(source, 10L * 1024L * 1024L).document());
        return new HostedSubmissionService.HostedSpecificationAnalysis(
                new SpecificationId(UUID.randomUUID()), fileName, Files.size(source), analysis);
    }

    private JsonNode uploadHostedFixture(String fileName, String version)
            throws Exception {
        byte[] source = Files.readAllBytes(repositoryRoot().resolve(fileName));
        var response = new ObjectMapper().readTree(mvc.perform(
                        post("/api/specifications/uploads")
                                .with(user()).with(csrf())
                                .header("X-Specification-Name", fileName)
                                .contentType("application/yaml")
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
        var customers = StreamSupport.stream(response.path("operations").spliterator(), false)
                .filter(operation -> operation.path("operationId").asText().equals("getCustomers"))
                .findFirst().orElseThrow();
        assertEquals("SUPPORTED", customers.path("status").asText());
        assertTrue(customers.path("issues").isEmpty());
        assertOperationDecision(response, "listSchemaFixtures", "SUPPORTED", null);
        assertOperationDecision(response, "getNullablePathFixture", "UNSUPPORTED",
                "PARAMETER_NULLABLE_PATH_UNSUPPORTED");
        assertOperationDecision(response, "submitConflictingAllOf", "UNSUPPORTED",
                "SCHEMA_CONSTRAINT_UNSUPPORTED");
        assertOperationDecision(response, "submitCompositionBudgetOverflow", "UNSUPPORTED",
                "SCHEMA_COMPOSITION_UNSUPPORTED");
        return response;
    }

    private void assertOperationDecision(
            JsonNode analysis,
            String operationId,
            String status,
            String issueCode) {
        var operation = StreamSupport.stream(analysis.path("operations").spliterator(), false)
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

    private RuntimeMetadataArtifact runtimeMetadata() {
        RuntimeTool tool = new RuntimeTool(
                "getWeather", "weather", "Get weather",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/weather", List.of(), false, false),
                null, null, null, List.of());
        return new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), List.of(tool)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityBeans {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC); }

        @Bean
        ClientRegistrationRepository clients() {
            ClientRegistration client = ClientRegistration.withRegistrationId("gen2spring")
                    .clientId("client")
                    .clientSecret("test-only")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri(ISSUER + "/authorize")
                    .tokenUri(ISSUER + "/token")
                    .jwkSetUri(ISSUER + "/jwks")
                    .issuerUri(ISSUER)
                    .userNameAttributeName("sub")
                    .clientName("Gen2Spring")
                    .build();
            return new InMemoryClientRegistrationRepository(client);
        }
    }
}
