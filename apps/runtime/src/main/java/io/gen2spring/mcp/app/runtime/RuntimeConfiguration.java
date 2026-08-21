package io.gen2spring.mcp.app.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.gen2spring.mcp.adapter.cryptography.HmacRuntimeTokenCodec;
import io.gen2spring.mcp.adapter.mcp.McpJavaSdkEmitter;
import io.gen2spring.mcp.adapter.persistence.PostgresManagedRuntimeStore;
import io.gen2spring.mcp.adapter.persistence.PostgresToolCatalogStore;
import io.gen2spring.mcp.adapter.provideregress.GatewayProviderCallClient;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore;
import io.gen2spring.mcp.application.managed.execution.ManagedExecutionLimits;
import io.gen2spring.mcp.application.managed.execution.ManagedRuntimeBinding;
import io.gen2spring.mcp.application.managed.execution.ManagedToolExecutor;
import io.gen2spring.mcp.application.managed.execution.ProviderCallClient;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccessAuthenticator;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenCodec;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeProperties.class)
class RuntimeConfiguration {
    @Bean(destroyMethod = "close")
    DataSource runtimeDataSource(RuntimeProperties properties) {
        char[] password = secret(properties.database().passwordFile());
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(properties.database().url());
            config.setUsername(properties.database().username());
            config.setPassword(new String(password));
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(5_000);
            config.setPoolName("gen2spring-runtime");
            return new HikariDataSource(config);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Bean
    Flyway runtimeFlyway(DataSource runtimeDataSource) {
        Flyway flyway = Flyway.configure().dataSource(runtimeDataSource).load();
        flyway.migrate();
        return flyway;
    }

    @Bean ManagedRuntimeStore managedRuntimeStore(DataSource dataSource) {
        return new PostgresManagedRuntimeStore(dataSource);
    }

    @Bean ToolCatalogStore toolCatalogStore(DataSource dataSource) {
        return new PostgresToolCatalogStore(dataSource);
    }

    @Bean ToolCatalogService toolCatalogService(ToolCatalogStore store) {
        return new ToolCatalogService(store);
    }

    @Bean RuntimeTokenCodec runtimeTokenCodec(RuntimeProperties properties) {
        return new HmacRuntimeTokenCodec(properties.tokenPepperFile());
    }

    @Bean Clock runtimeClock() {
        return Clock.systemUTC();
    }

    @Bean RuntimeAccessAuthenticator runtimeAccessAuthenticator(
            ManagedRuntimeStore store, RuntimeTokenCodec tokens, Clock clock) {
        return new RuntimeAccessAuthenticator(store, tokens, clock);
    }

    @Bean ProviderCallClient providerCallClient(RuntimeProperties properties) {
        return new GatewayProviderCallClient(properties.providerEgressEndpoint(), sslContext(properties.tls()));
    }

    @Bean(destroyMethod = "close")
    ManagedToolExecutor managedToolExecutor(ProviderCallClient client) {
        return new ManagedToolExecutor(client, new ManagedExecutionLimits(Duration.ofSeconds(30), 16, 64));
    }

    @Bean(destroyMethod = "close")
    RuntimeServerHandleRegistry runtimeServerHandleRegistry(
            ToolCatalogService catalogs,
            ManagedToolExecutor executor,
            RuntimeProperties properties,
            Clock clock) {
        McpJavaSdkEmitter emitter = new McpJavaSdkEmitter();
        ObjectMapper json = new ObjectMapper();
        JacksonMcpJsonMapper mapper = new JacksonMcpJsonMapper(json);
        return new RuntimeServerHandleRegistry(access -> {
            var instance = access.instance();
            var catalog = catalogs.require(instance.owner(), instance.catalogId());
            ManagedRuntimeBinding binding = new ManagedRuntimeBinding(instance, catalog.metadata());
            var specifications = emitter.emit(catalog.metadata().document().tools(),
                    (toolName, arguments) -> executor.call(binding, toolName, arguments));
            String endpoint = "/mcp/" + instance.id().value();
            var transport = WebMvcStreamableServerTransportProvider.builder()
                    .jsonMapper(mapper).mcpEndpoint(endpoint).disallowDelete(false).build();
            var server = McpServer.sync(transport)
                    .jsonMapper(mapper)
                    .serverInfo("gen2spring-managed-runtime", "1.0")
                    .requestTimeout(Duration.ofSeconds(30))
                    .tools(specifications)
                    .build();
            return new RuntimeServerHandle(instance, transport.getRouterFunction(), () -> {
                try {
                    server.close();
                } finally {
                    transport.closeGracefully().block(Duration.ofSeconds(5));
                }
            });
        }, properties.cacheSize(), clock);
    }

    @Bean RouterFunction<ServerResponse> managedMcpRouter(RuntimeServerHandleRegistry handles) {
        return new ManagedMcpRouter(handles);
    }

    @Bean RuntimeBearerFilter runtimeBearerFilter(
            RuntimeAccessAuthenticator authenticator,
            RuntimeServerHandleRegistry handles) {
        return new RuntimeBearerFilter(authenticator, handles::invalidate);
    }

    @Bean SecurityFilterChain runtimeSecurity(HttpSecurity http, RuntimeBearerFilter filter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/mcp/**", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    private SSLContext sslContext(RuntimeProperties.Tls properties) {
        char[] keyPassword = secret(properties.keyStorePasswordFile());
        char[] trustPassword = secret(properties.trustStorePasswordFile());
        try {
            KeyStore keys = loadStore(properties.keyStore(), keyPassword);
            KeyStore trust = loadStore(properties.trustStore(), trustPassword);
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, keyPassword);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
            return context;
        } catch (Exception failure) {
            throw new IllegalStateException("Managed runtime TLS configuration is invalid");
        } finally {
            Arrays.fill(keyPassword, '\0');
            Arrays.fill(trustPassword, '\0');
        }
    }

    private KeyStore loadStore(java.nio.file.Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private char[] secret(java.nio.file.Path path) {
        try {
            if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                throw new IllegalStateException();
            }
            return Files.readString(path, StandardCharsets.UTF_8).strip().toCharArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Managed runtime secret configuration is invalid");
        }
    }
}
