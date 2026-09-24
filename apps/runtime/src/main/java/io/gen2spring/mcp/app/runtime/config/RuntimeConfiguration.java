package io.gen2spring.mcp.app.runtime.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.gen2spring.mcp.adapter.cryptography.AesGcmCredentialProtector;
import io.gen2spring.mcp.adapter.cryptography.HmacRuntimeTokenCodec;
import io.gen2spring.mcp.adapter.persistence.catalog.PostgresToolCatalogStore;
import io.gen2spring.mcp.adapter.persistence.credential.PostgresManagedCredentialStore;
import io.gen2spring.mcp.adapter.persistence.policy.PostgresRuntimePolicyStore;
import io.gen2spring.mcp.adapter.persistence.runtime.PostgresManagedRuntimeStore;
import io.gen2spring.mcp.adapter.provideregress.GatewayProviderCallClient;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore;
import io.gen2spring.mcp.application.managed.credential.port.out.CredentialProtector;
import io.gen2spring.mcp.application.managed.credential.port.out.ManagedCredentialStore;
import io.gen2spring.mcp.application.managed.credential.service.RuntimeCredentialResolver;
import io.gen2spring.mcp.application.managed.execution.ManagedExecutionLimits;
import io.gen2spring.mcp.app.runtime.infrastructure.execution.BoundedManagedExecutionTasks;
import io.gen2spring.mcp.application.managed.execution.port.out.ProviderCallClient;
import io.gen2spring.mcp.application.managed.execution.service.ManagedToolExecutor;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeTokenCodec;
import io.gen2spring.mcp.application.managed.runtime.service.RuntimeAccessAuthenticator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean ManagedCredentialStore managedCredentialStore(DataSource dataSource) {
        return new PostgresManagedCredentialStore(dataSource);
    }

    @Bean CredentialProtector credentialProtector(RuntimeProperties properties) {
        return new AesGcmCredentialProtector(
                properties.encryption().keyFiles(), properties.encryption().activeKeyId());
    }

    @Bean RuntimePolicyStore runtimePolicyStore(DataSource dataSource) {
        return new PostgresRuntimePolicyStore(dataSource);
    }

    @Bean RuntimeCredentialResolver runtimeCredentialResolver(
            ManagedRuntimeStore runtimes,
            ManagedCredentialStore credentials,
            CredentialProtector protector) {
        return new RuntimeCredentialResolver(runtimes, credentials, protector);
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
            ManagedRuntimeStore store,
            RuntimeTokenCodec tokens,
            Clock clock,
            ToolCatalogService catalogs,
            RuntimePolicyStore policies) {
        return new RuntimeAccessAuthenticator(store, tokens, clock, catalogs, policies);
    }

    @Bean ProviderCallClient providerCallClient(RuntimeProperties properties) {
        return new GatewayProviderCallClient(properties.providerEgressEndpoint(), sslContext(properties.tls()));
    }

    @Bean(destroyMethod = "close")
    ManagedToolExecutor managedToolExecutor(
            ProviderCallClient client,
            RuntimePolicyStore policies,
            Clock clock) {
        var limits = new ManagedExecutionLimits(Duration.ofSeconds(30), 16, 64);
        return new ManagedToolExecutor(
                new BoundedManagedExecutionTasks(limits), client, limits,
                policies, clock, UUID::randomUUID);
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
            throw new IllegalStateException("Managed runtime TLS configuration is invalid", failure);
        } finally {
            Arrays.fill(keyPassword, '\0');
            Arrays.fill(trustPassword, '\0');
        }
    }

    private KeyStore loadStore(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private char[] secret(Path path) {
        try {
            if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                throw new IllegalStateException();
            }
            return Files.readString(path, StandardCharsets.UTF_8).strip().toCharArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Managed runtime secret configuration is invalid", failure);
        }
    }
}
