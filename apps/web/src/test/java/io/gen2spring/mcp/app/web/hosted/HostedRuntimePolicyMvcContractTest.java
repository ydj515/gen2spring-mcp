package io.gen2spring.mcp.app.web.hosted;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.gen2spring.mcp.application.hosted.account.AccountStore;
import io.gen2spring.mcp.application.managed.audit.RuntimeAuditService;
import io.gen2spring.mcp.application.managed.credential.ManagedCredentialService;
import io.gen2spring.mcp.application.managed.policy.IssuedRuntimeGrant;
import io.gen2spring.mcp.application.managed.policy.RuntimeGrantService;
import io.gen2spring.mcp.application.managed.policy.RuntimePolicyStore.AuditPage;
import io.gen2spring.mcp.app.web.config.JobEventStreamConfiguration;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import io.gen2spring.mcp.app.web.security.HostedSecurityConfiguration;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeGrant;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(
        controllers = {HostedCredentialController.class, HostedRuntimePolicyController.class},
        properties = "gen2spring.mode=hosted")
@Import({HostedSecurityConfiguration.class, HostedManagedRuntimeMvcContractTest.SecurityBeans.class,
        WebErrorMapper.class, WebErrorResponseWriter.class, JobEventStreamConfiguration.class})
class HostedRuntimePolicyMvcContractTest {
    private static final String ISSUER = "https://issuer.example";
    private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");
    private static final AccountId OWNER = new AccountId(UUID.randomUUID());
    private static final ManagedCredentialId CREDENTIAL = new ManagedCredentialId(UUID.randomUUID());
    private static final RuntimeInstanceId RUNTIME = new RuntimeInstanceId(UUID.randomUUID());
    private static final RuntimeGrantId GRANT = new RuntimeGrantId(UUID.randomUUID());

    @Autowired MockMvc mvc;
    @MockitoBean AccountStore accounts;
    @MockitoBean ManagedCredentialService credentials;
    @MockitoBean RuntimeGrantService grants;
    @MockitoBean RuntimeAuditService audits;

    @BeforeEach
    void account() {
        when(accounts.findOrCreate(eq(ISSUER), eq("subject-1"), any())).thenReturn(OWNER);
    }

    @Test
    void createsAndListsWriteOnlyCredentialsWithoutSecretMaterial() throws Exception {
        ManagedCredential credential = new ManagedCredential(
                CREDENTIAL, OWNER, "weather-key", ManagedCredentialKind.BEARER, 1,
                NOW, NOW, Optional.empty());
        when(credentials.create(eq(OWNER), eq("weather-key"), any())).thenReturn(credential);
        when(credentials.list(OWNER)).thenReturn(List.of(credential));

        mvc.perform(post("/api/credentials").with(user()).with(csrf())
                        .contentType("application/json")
                        .content("{\"label\":\"weather-key\",\"kind\":\"BEARER\",\"value\":\"private-marker\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.credentialId").value(CREDENTIAL.value().toString()))
                .andExpect(content().string(not(containsString("private-marker"))))
                .andExpect(content().string(not(containsString("cipher"))));
        mvc.perform(get("/api/credentials").with(user()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$[0].kind").value("BEARER"))
                .andExpect(content().string(not(containsString("value"))));
        when(credentials.require(OWNER, CREDENTIAL)).thenReturn(credential);
        mvc.perform(get("/api/credentials/{credentialId}", CREDENTIAL.value()).with(user()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.credentialId").value(CREDENTIAL.value().toString()));

        assertFalse(
                new HostedCredentialController.CredentialRequest(
                        "label", "BASIC", null, "private-user", "private-password")
                        .toString().contains("private"));
    }

    @Test
    void returnsGrantTokenOnlyAtCreationAndExposesSafeAuditPage() throws Exception {
        ManagedRuntimeGrant grant = new ManagedRuntimeGrant(
                GRANT, RUNTIME, OWNER, "client-a", Set.of("weather"), 10,
                NOW, NOW.plusSeconds(600), Optional.empty());
        when(grants.create(eq(OWNER), eq(RUNTIME), eq("client-a"), eq(Set.of("weather")),
                eq(10), eq(Duration.ofSeconds(600))))
                .thenReturn(new IssuedRuntimeGrant(grant, "g2s_rt_one-time-token"));
        when(grants.list(OWNER, RUNTIME)).thenReturn(List.of(grant));
        when(audits.list(eq(OWNER), eq(RUNTIME), eq(50), eq(Optional.empty())))
                .thenReturn(new AuditPage(List.of(), Optional.empty()));

        mvc.perform(post("/api/runtimes/{runtime}/grants", RUNTIME.value()).with(user()).with(csrf())
                        .contentType("application/json")
                        .content("{\"principal\":\"client-a\",\"allowedTools\":[\"weather\"],"
                                + "\"requestsPerMinute\":10,\"lifetimeSeconds\":600}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.token").value("g2s_rt_one-time-token"));
        mvc.perform(get("/api/runtimes/{runtime}/grants", RUNTIME.value()).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].grantId").value(GRANT.value().toString()))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(content().string(not(containsString("one-time-token"))));
        mvc.perform(get("/api/runtimes/{runtime}/audit", RUNTIME.value()).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(content().string(not(containsString("arguments"))))
                .andExpect(content().string(not(containsString("headers"))));
    }

    private RequestPostProcessor user() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1"));
    }
}
