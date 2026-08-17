package io.gen2spring.mcp.app.web.hosted;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.gen2spring.mcp.application.hosted.account.AccountStore;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.job.JobView;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import io.gen2spring.mcp.app.web.config.JobEventStreamConfiguration;
import io.gen2spring.mcp.app.web.security.HostedSecurityConfiguration;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

@WebMvcTest(
        controllers = {HostedSpecificationController.class, HostedJobController.class, HostedArtifactController.class},
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

    @BeforeEach
    void account() {
        when(accounts.findOrCreate(eq(ISSUER), eq("subject-1"), any())).thenReturn(OWNER);
    }

    @Test
    void requiresOidcAndCsrfThenMapsIssuerAndSubjectToTheOwnedRequest() throws Exception {
        when(resources.specifications(OWNER, 51, Optional.empty())).thenReturn(List.of());

        mvc.perform(get("/api/specifications"))
                .andExpect(status().is3xxRedirection());
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
    void exposesOwnerAuthorizedUploadAnalysisAndPlanningPreview() throws Exception {
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        var analysis = new SpecificationAnalysisView(
                "a".repeat(64), "3.1.1", "yaml", URI.create("https://weather.example.test"),
                new SpecificationAnalysisView.Counts(0, 0, 0, 0),
                List.of(), java.util.Map.of(), List.of());
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
        JobView job = new JobView(
                new JobId(UUID.randomUUID()), OWNER, JobKind.GENERATION, JobStatus.QUEUED,
                Optional.of(specificationId), 0, false);
        when(submissions.generate(eq(OWNER), eq(specificationId), eq("editor-request-1"), any()))
                .thenReturn(new CreateJobResult(job, false));

        mvc.perform(post("/api/jobs")
                        .with(user()).with(csrf())
                        .header("Idempotency-Key", "editor-request-1")
                        .contentType("application/json")
                        .content("""
                                {"specificationId":"%s","configuration":{"operations":[]}}
                                """.formatted(specificationId.value())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(job.id().value().toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.replayed").value(false));
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

    private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1"));
    }

    private HostedSubmissionService.HostedSpecificationAnalysis hostedFixture(String fileName) throws Exception {
        Path source = repositoryRoot().resolve(fileName);
        var analysis = SpecificationAnalysisView.from(
                new SwaggerOpenApiAnalyzer().analyze(source, 10L * 1024L * 1024L).document());
        return new HostedSubmissionService.HostedSpecificationAnalysis(
                new SpecificationId(UUID.randomUUID()), fileName, Files.size(source), analysis);
    }

    private com.fasterxml.jackson.databind.JsonNode uploadHostedFixture(String fileName, String version)
            throws Exception {
        byte[] source = Files.readAllBytes(repositoryRoot().resolve(fileName));
        var response = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(
                        post("/api/specifications/uploads")
                                .with(user()).with(csrf())
                                .header("X-Specification-Name", fileName)
                                .contentType("application/yaml")
                                .content(source))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.openApiVersion").value(version))
                .andExpect(jsonPath("$.counts.total").value(26))
                .andExpect(jsonPath("$.counts.supported").value(26))
                .andExpect(jsonPath("$.counts.supportedWithWarning").value(0))
                .andExpect(jsonPath("$.counts.unsupported").value(0))
                .andExpect(jsonPath("$.file.name").value(fileName))
                .andExpect(jsonPath("$.file.byteSize").value(source.length))
                .andReturn().getResponse().getContentAsByteArray());
        var customers = java.util.stream.StreamSupport.stream(response.path("operations").spliterator(), false)
                .filter(operation -> operation.path("operationId").asText().equals("getCustomers"))
                .findFirst().orElseThrow();
        assertEquals("SUPPORTED", customers.path("status").asText());
        assertTrue(customers.path("issues").isEmpty());
        return response;
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
