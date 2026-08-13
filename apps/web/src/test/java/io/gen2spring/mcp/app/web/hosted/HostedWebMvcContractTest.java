package io.gen2spring.mcp.app.web.hosted;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
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
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import io.gen2spring.mcp.app.web.security.HostedSecurityConfiguration;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
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
@Import({HostedSecurityConfiguration.class, HostedWebMvcContractTest.SecurityBeans.class,
        WebErrorMapper.class, WebErrorResponseWriter.class})
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

    private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1"));
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
