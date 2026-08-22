package io.gen2spring.mcp.app.web.hosted;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService.MigrationResult;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.RuntimeCatalogTransition;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionKind;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionPage;
import io.gen2spring.mcp.application.managed.runtime.RuntimeActivation;
import io.gen2spring.mcp.app.web.config.JobEventStreamConfiguration;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import io.gen2spring.mcp.app.web.security.HostedSecurityConfiguration;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
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

@WebMvcTest(controllers = HostedManagedRuntimeController.class, properties = "gen2spring.mode=hosted")
@Import({HostedSecurityConfiguration.class, HostedManagedRuntimeMvcContractTest.SecurityBeans.class,
        WebErrorMapper.class, WebErrorResponseWriter.class, JobEventStreamConfiguration.class})
class HostedManagedRuntimeMvcContractTest {
    private static final String ISSUER = "https://issuer.example";
    private static final AccountId OWNER = new AccountId(UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final UUID CATALOG = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
    private static final RuntimeInstanceId RUNTIME =
            new RuntimeInstanceId(UUID.fromString("aa57ce1b-ed73-4505-a8a7-67de1e098ee4"));
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean AccountStore accounts;
    @MockitoBean ManagedRuntimeService runtimes;
    @MockitoBean ManagedRuntimeMigrationService migrations;

    @BeforeEach
    void account() {
        when(accounts.findOrCreate(eq(ISSUER), eq("subject-1"), any())).thenReturn(OWNER);
    }

    @Test
    void protectsMutationWithOidcAndCsrfAndReturnsTheTokenOnlyOnce() throws Exception {
        ManagedRuntimeInstance instance = instance();
        when(runtimes.activate(eq(OWNER), eq(CATALOG), eq(Optional.empty()), eq(Optional.empty()), eq(java.util.Map.of())))
                .thenReturn(new RuntimeActivation(
                        instance, "g2s_rt_private-token", URI.create("https://runtime.example/mcp/" + RUNTIME.value())));
        when(runtimes.require(OWNER, RUNTIME)).thenReturn(instance);

        mvc.perform(post("/api/tool-catalogs/{id}/runtimes", CATALOG)
                        .with(user()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tool-catalogs/{id}/runtimes", CATALOG)
                        .with(user()).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runtimeId").value(RUNTIME.value().toString()))
                .andExpect(jsonPath("$.token").value("g2s_rt_private-token"));
        mvc.perform(get("/api/runtimes/{id}", RUNTIME.value()).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(content().string(not(containsString("digest"))));
        mvc.perform(post("/api/runtimes/{id}/revocation", RUNTIME.value()).with(user()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void mapsBoundedSafeControlFailuresWithoutEchoingInputs() throws Exception {
        String privateMarker = "https://private-marker.example/";
        when(runtimes.activate(eq(OWNER), eq(CATALOG), eq(Optional.of(privateMarker)), any(), eq(java.util.Map.of())))
                .thenThrow(new ManagedRuntimeService.ManagedRuntimeRequestInvalid());
        mvc.perform(post("/api/tool-catalogs/{id}/runtimes", CATALOG)
                        .with(user()).with(csrf()).contentType("application/json")
                        .content("{\"providerBaseUrl\":\"" + privateMarker + "\",\"lifetimeSeconds\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RUNTIME_REQUEST_INVALID"))
                .andExpect(content().string(not(containsString("private-marker"))));

        when(runtimes.require(OWNER, RUNTIME)).thenThrow(new ManagedRuntimeService.ManagedRuntimeNotFound());
        mvc.perform(get("/api/runtimes/{id}", RUNTIME.value()).with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(content().string(not(containsString(RUNTIME.value().toString()))));

        mvc.perform(get("/api/runtimes/not-a-uuid").with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("not-a-uuid"))));
    }

    @Test
    void protectsMigrationAndRollbackAndReturnsBoundedTransitionHistory() throws Exception {
        UUID target = UUID.fromString("8f5a48fd-f34b-4ba5-b749-eb79008370d5");
        ManagedRuntimeInstance migrated = new ManagedRuntimeInstance(
                RUNTIME, OWNER, target, "b".repeat(64), Optional.empty(),
                NOW, NOW.plusSeconds(3600), Optional.empty());
        RuntimeCatalogTransition transition = new RuntimeCatalogTransition(
                1, RUNTIME, CATALOG, "a".repeat(64), target, "b".repeat(64),
                "d".repeat(64), TransitionKind.MIGRATION, NOW);
        MigrationResult result = new MigrationResult(migrated, transition, "d".repeat(64));
        when(migrations.migrate(OWNER, RUNTIME, CATALOG, target, "b".repeat(64))).thenReturn(result);
        when(migrations.history(OWNER, RUNTIME, 25, Optional.empty()))
                .thenReturn(new TransitionPage(List.of(transition), Optional.empty()));
        when(migrations.rollback(OWNER, RUNTIME, target)).thenReturn(result);

        String migrationBody = """
                {"expectedCurrentCatalogId":"%s","targetCatalogId":"%s","targetChecksum":"%s"}
                """.formatted(CATALOG, target, "b".repeat(64));
        mvc.perform(post("/api/runtimes/{id}/migrations", RUNTIME.value())
                        .with(user()).contentType("application/json").content(migrationBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/runtimes/{id}/migrations", RUNTIME.value())
                        .with(user()).with(csrf()).contentType("application/json").content(migrationBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogId").value(target.toString()))
                .andExpect(jsonPath("$.transition.kind").value("MIGRATION"))
                .andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(get("/api/runtimes/{id}/migrations", RUNTIME.value())
                        .queryParam("limit", "25").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sequence").value(1))
                .andExpect(jsonPath("$.items[0].sourceCatalogId").value(CATALOG.toString()));
        mvc.perform(post("/api/runtimes/{id}/rollback", RUNTIME.value())
                        .with(user()).with(csrf()).contentType("application/json")
                        .content("{\"expectedCurrentCatalogId\":\"" + target + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void mapsMigrationConflictsToFixedSafeErrorCodes() throws Exception {
        UUID target = UUID.fromString("8f5a48fd-f34b-4ba5-b749-eb79008370d5");
        when(migrations.migrate(eq(OWNER), eq(RUNTIME), eq(CATALOG), eq(target), any()))
                .thenThrow(new ManagedRuntimeMigrationService.CatalogMigrationBreaking());

        mvc.perform(post("/api/runtimes/{id}/migrations", RUNTIME.value())
                        .with(user()).with(csrf()).contentType("application/json")
                        .content("""
                                {"expectedCurrentCatalogId":"%s","targetCatalogId":"%s","targetChecksum":"%s"}
                                """.formatted(CATALOG, target, "f".repeat(64))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATALOG_MIGRATION_BREAKING"))
                .andExpect(content().string(not(containsString(target.toString()))));

        when(migrations.rollback(OWNER, RUNTIME, target))
                .thenThrow(new ManagedRuntimeMigrationService.CatalogMigrationBlocked());
        mvc.perform(post("/api/runtimes/{id}/rollback", RUNTIME.value())
                        .with(user()).with(csrf()).contentType("application/json")
                        .content("{\"expectedCurrentCatalogId\":\"" + target + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATALOG_MIGRATION_BLOCKED"))
                .andExpect(content().string(not(containsString(target.toString()))));
    }

    @Test
    void rejectsMalformedMigrationJsonAndHistoryBoundsAsBadRequests() throws Exception {
        mvc.perform(post("/api/runtimes/{id}/migrations", RUNTIME.value())
                        .with(user()).with(csrf()).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));

        mvc.perform(get("/api/runtimes/{id}/migrations", RUNTIME.value())
                        .queryParam("limit", "not-a-number").with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
    }

    private ManagedRuntimeInstance instance() {
        return new ManagedRuntimeInstance(
                RUNTIME, OWNER, CATALOG, "a".repeat(64), Optional.empty(), NOW, NOW.plusSeconds(3600), Optional.empty());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).subject("subject-1"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityBeans {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration registration = ClientRegistration.withRegistrationId("hosted")
                    .clientId("client")
                    .clientSecret("secret")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri("https://issuer.example/authorize")
                    .tokenUri("https://issuer.example/token")
                    .jwkSetUri("https://issuer.example/jwks")
                    .issuerUri(ISSUER)
                    .userNameAttributeName("sub")
                    .clientName("Hosted")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
