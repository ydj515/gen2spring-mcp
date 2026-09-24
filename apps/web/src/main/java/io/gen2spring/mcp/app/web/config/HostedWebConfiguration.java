package io.gen2spring.mcp.app.web.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.gen2spring.mcp.adapter.cryptography.AesGcmCredentialProtector;
import io.gen2spring.mcp.adapter.cryptography.AesGcmImportTargetProtector;
import io.gen2spring.mcp.adapter.cryptography.HmacRuntimeTokenCodec;
import io.gen2spring.mcp.adapter.persistence.account.PostgresAccountStore;
import io.gen2spring.mcp.adapter.persistence.catalog.PostgresToolCatalogStore;
import io.gen2spring.mcp.adapter.persistence.credential.PostgresManagedCredentialStore;
import io.gen2spring.mcp.adapter.persistence.job.PostgresJobQueue;
import io.gen2spring.mcp.adapter.persistence.policy.PostgresRuntimePolicyStore;
import io.gen2spring.mcp.adapter.persistence.query.PostgresHostedResourceStore;
import io.gen2spring.mcp.adapter.persistence.runtime.PostgresManagedRuntimeStore;
import io.gen2spring.mcp.adapter.persistence.runtime.PostgresRuntimeCatalogTransitionStore;
import io.gen2spring.mcp.adapter.persistence.specification.PostgresSpecificationCatalog;
import io.gen2spring.mcp.adapter.persistence.worker.PostgresWorkerHeartbeatStore;
import io.gen2spring.mcp.adapter.storage.S3BucketReadinessProbe;
import io.gen2spring.mcp.adapter.storage.S3ObjectStorage;
import io.gen2spring.mcp.app.web.application.hosted.port.out.HostedSpecificationProcessor;
import io.gen2spring.mcp.app.web.application.hosted.port.out.HostedSubmissionSnapshotCodec;
import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedAccountService;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedArtifactDownloadService;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedResourceQueryService;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedSubmissionService;
import io.gen2spring.mcp.app.web.infrastructure.hosted.artifact.TempFileVerifiedArtifactReader;
import io.gen2spring.mcp.app.web.infrastructure.hosted.submission.GeneratorHostedSpecificationProcessor;
import io.gen2spring.mcp.app.web.infrastructure.hosted.submission.JacksonHostedSubmissionSnapshotCodec;
import io.gen2spring.mcp.application.hosted.account.port.out.AccountStore;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiffService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore;
import io.gen2spring.mcp.application.hosted.imports.port.out.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.job.port.out.JobQueue;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.specification.port.out.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.hosted.worker.port.out.WorkerHeartbeatStore;
import io.gen2spring.mcp.application.managed.audit.RuntimeAuditService;
import io.gen2spring.mcp.application.managed.credential.ManagedCredentialService;
import io.gen2spring.mcp.application.managed.credential.port.out.CredentialProtector;
import io.gen2spring.mcp.application.managed.credential.port.out.ManagedCredentialStore;
import io.gen2spring.mcp.application.managed.policy.RuntimeGrantService;
import io.gen2spring.mcp.application.managed.policy.port.out.RuntimePolicyStore;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeService;
import io.gen2spring.mcp.application.managed.runtime.port.out.ManagedRuntimeStore;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeCatalogTransitionStore;
import io.gen2spring.mcp.application.managed.runtime.port.out.RuntimeTokenCodec;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.Cookie;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
@EnableConfigurationProperties(HostedWebProperties.class)
public class HostedWebConfiguration {
    @Bean
    HostedRuntimeInvariant hostedRuntimeInvariant(ServerProperties server) {
        var session = server.getServlet().getSession();
        var cookie = session.getCookie();
        if (server.getAddress() == null || server.getAddress().isLoopbackAddress()
                || server.getForwardHeadersStrategy() != ServerProperties.ForwardHeadersStrategy.NATIVE
                || session.getTimeout() == null || session.getTimeout().compareTo(Duration.ofMinutes(1)) < 0
                || session.getTimeout().compareTo(Duration.ofHours(24)) > 0
                || !Boolean.TRUE.equals(cookie.getHttpOnly())
                || !Boolean.TRUE.equals(cookie.getSecure())
                || cookie.getSameSite() != Cookie.SameSite.LAX) {
            throw invalid();
        }
        return new HostedRuntimeInvariant();
    }

    @Bean(destroyMethod = "close")
    DataSource hostedDataSource(HostedWebProperties properties) {
        char[] password = secret(properties.database().passwordFile());
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(properties.database().url());
            config.setUsername(properties.database().username());
            config.setPassword(new String(password));
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(5_000);
            config.setValidationTimeout(2_000);
            config.setPoolName("gen2spring-hosted");
            return new HikariDataSource(config);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Bean
    Flyway hostedFlyway(DataSource hostedDataSource) {
        Flyway flyway = Flyway.configure().dataSource(hostedDataSource).load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    Clock hostedClock() {
        return Clock.systemUTC();
    }

    @Bean
    GeneratorRuntime hostedGeneratorRuntime() {
        return GeneratorRuntime.defaults();
    }

    @Bean
    AccountStore hostedAccountStore(DataSource dataSource) {
        return new PostgresAccountStore(dataSource);
    }

    @Bean
    HostedAccountService hostedAccountService(AccountStore accounts, Clock hostedClock) {
        return new HostedAccountService(accounts, hostedClock);
    }

    @Bean
    SpecificationCatalog hostedSpecificationCatalog(DataSource dataSource) {
        return new PostgresSpecificationCatalog(dataSource);
    }

    @Bean
    JobQueue hostedJobQueue(DataSource dataSource, Clock hostedClock) {
        return new PostgresJobQueue(dataSource, hostedClock);
    }

    @Bean
    HostedResourceStore hostedResourceStore(DataSource dataSource) {
        return new PostgresHostedResourceStore(dataSource);
    }

    @Bean
    HostedResourceQueryService hostedResourceQueryService(HostedResourceStore resources) {
        return new HostedResourceQueryService(resources);
    }

    @Bean
    ToolCatalogStore hostedToolCatalogStore(DataSource dataSource) {
        return new PostgresToolCatalogStore(dataSource);
    }

    @Bean
    ToolCatalogService hostedToolCatalogService(ToolCatalogStore store) {
        return new ToolCatalogService(store);
    }

    @Bean
    CatalogDiffService hostedCatalogDiffService(ToolCatalogStore store) {
        return new CatalogDiffService(store);
    }

    @Bean
    ManagedRuntimeStore hostedManagedRuntimeStore(DataSource dataSource) {
        return new PostgresManagedRuntimeStore(dataSource);
    }

    @Bean
    RuntimeCatalogTransitionStore hostedRuntimeCatalogTransitionStore(DataSource dataSource) {
        return new PostgresRuntimeCatalogTransitionStore(dataSource);
    }

    @Bean
    ManagedRuntimeMigrationService hostedManagedRuntimeMigrationService(
            ToolCatalogStore catalogs,
            ManagedRuntimeStore runtimes,
            RuntimeCatalogTransitionStore transitions,
            Clock hostedClock) {
        return new ManagedRuntimeMigrationService(catalogs, runtimes, transitions, hostedClock);
    }

    @Bean
    ManagedCredentialStore hostedManagedCredentialStore(DataSource dataSource) {
        return new PostgresManagedCredentialStore(dataSource);
    }

    @Bean
    CredentialProtector hostedCredentialProtector(HostedWebProperties properties) {
        return new AesGcmCredentialProtector(
                properties.credentialEncryption().keyFiles(),
                properties.credentialEncryption().activeKeyId());
    }

    @Bean
    ManagedCredentialService hostedManagedCredentialService(
            ManagedCredentialStore store,
            CredentialProtector protector,
            Clock hostedClock) {
        return new ManagedCredentialService(store, protector, hostedClock);
    }

    @Bean
    RuntimePolicyStore hostedRuntimePolicyStore(DataSource dataSource) {
        return new PostgresRuntimePolicyStore(dataSource);
    }

    @Bean
    RuntimeTokenCodec hostedRuntimeTokenCodec(HostedWebProperties properties) {
        return new HmacRuntimeTokenCodec(properties.runtime().tokenPepperFile());
    }

    @Bean
    ManagedRuntimeService hostedManagedRuntimeService(
            ToolCatalogService catalogs,
            ManagedRuntimeStore runtimes,
            ManagedCredentialService credentials,
            RuntimeTokenCodec tokens,
            Clock hostedClock,
            HostedWebProperties properties) {
        return new ManagedRuntimeService(
                catalogs, runtimes, credentials, tokens, hostedClock, properties.runtime().baseUri());
    }

    @Bean
    RuntimeGrantService hostedRuntimeGrantService(
            ManagedRuntimeService runtimes,
            ToolCatalogService catalogs,
            RuntimePolicyStore policies,
            RuntimeTokenCodec tokens,
            Clock hostedClock) {
        return new RuntimeGrantService(runtimes, catalogs, policies, tokens, hostedClock);
    }

    @Bean
    RuntimeAuditService hostedRuntimeAuditService(
            ManagedRuntimeService runtimes,
            RuntimePolicyStore policies) {
        return new RuntimeAuditService(runtimes, policies);
    }

    @Bean
    WorkerHeartbeatStore hostedWorkerHeartbeatStore(DataSource dataSource) {
        return new PostgresWorkerHeartbeatStore(dataSource);
    }

    @Bean
    HostedJobService hostedJobService(JobQueue queue, SpecificationCatalog catalog) {
        return new HostedJobService(queue, catalog);
    }

    @Bean(destroyMethod = "close")
    S3Client hostedS3Client(HostedWebProperties properties) {
        char[] access = secret(properties.storage().accessKeyFile());
        char[] secret = secret(properties.storage().secretKeyFile());
        try {
            return S3Client.builder()
                    .endpointOverride(properties.storage().endpoint())
                    .region(Region.of(properties.storage().region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(new String(access), new String(secret))))
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .chunkedEncodingEnabled(false)
                            .build())
                    .build();
        } finally {
            Arrays.fill(access, '\0');
            Arrays.fill(secret, '\0');
        }
    }

    @Bean
    ObjectStorage hostedObjectStorage(S3Client hostedS3Client, HostedWebProperties properties) {
        return new S3ObjectStorage(
                hostedS3Client, properties.storage().bucket(), properties.storage().maxObjectBytes());
    }

    @Bean
    VerifiedArtifactReader hostedVerifiedArtifactReader(ObjectStorage storage, HostedWebProperties properties) {
        return new TempFileVerifiedArtifactReader(storage, properties.storage().maxObjectBytes());
    }

    @Bean
    HostedArtifactDownloadService hostedArtifactDownloadService(
            HostedResourceStore resources, VerifiedArtifactReader reader) {
        return new HostedArtifactDownloadService(resources, reader);
    }

    @Bean
    HostedPlatformReadiness hostedPlatformReadiness(
            Flyway hostedFlyway,
            S3Client hostedS3Client,
            WorkerHeartbeatStore hostedWorkerHeartbeatStore,
            HostedWebProperties properties,
            Clock hostedClock) {
        try {
            if (hostedFlyway.info().current() == null
                    || !hostedWorkerHeartbeatStore.hasRecentHeartbeat(
                            hostedClock.instant().minus(properties.workerStaleAfter()))) {
                throw invalid();
            }
            S3BucketReadinessProbe.verify(hostedS3Client, properties.storage().bucket());
            return new HostedPlatformReadiness();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    @Bean
    ImportTargetProtector hostedImportTargetProtector(HostedWebProperties properties) {
        return new AesGcmImportTargetProtector(
                properties.encryption().keyFiles(), properties.encryption().activeKeyId());
    }

    @Bean
    HostedSpecificationProcessor hostedSpecificationProcessor(
            GeneratorRuntime hostedGeneratorRuntime,
            HostedWebProperties properties) {
        return new GeneratorHostedSpecificationProcessor(hostedGeneratorRuntime, properties.workRoot());
    }

    @Bean
    HostedSubmissionSnapshotCodec hostedSubmissionSnapshotCodec() {
        return new JacksonHostedSubmissionSnapshotCodec();
    }

    @Bean
    HostedSubmissionService hostedSubmissionService(
            HostedSpecificationProcessor hostedSpecificationProcessor,
            HostedSubmissionSnapshotCodec hostedSubmissionSnapshotCodec,
            ObjectStorage hostedObjectStorage,
            SpecificationCatalog hostedSpecificationCatalog,
            HostedResourceStore hostedResourceStore,
            HostedJobService hostedJobService,
            ImportTargetProtector hostedImportTargetProtector,
            Clock hostedClock) {
        return new HostedSubmissionService(
                hostedSpecificationProcessor, hostedSubmissionSnapshotCodec,
                hostedObjectStorage, hostedSpecificationCatalog,
                hostedResourceStore, hostedJobService, hostedImportTargetProtector,
                hostedClock);
    }

    private char[] secret(Path path) {
        byte[] bytes = null;
        try {
            if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) < 1 || Files.size(path) > 1024) throw invalid();
            bytes = Files.readAllBytes(path);
            String value = new String(bytes, StandardCharsets.UTF_8).strip();
            if (value.isEmpty() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
                throw invalid();
            }
            return value.toCharArray();
        } catch (RuntimeException failure) {
            throw invalid();
        } catch (Exception failure) {
            throw invalid();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Hosted Web configuration is invalid");
    }

    static final class HostedRuntimeInvariant {}
    static final class HostedPlatformReadiness {}
}
