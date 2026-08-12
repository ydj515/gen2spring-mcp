package io.gen2spring.mcp.app.web.config;

import io.gen2spring.mcp.adapter.cryptography.AesGcmImportTargetProtector;
import io.gen2spring.mcp.adapter.persistence.PostgresAccountStore;
import io.gen2spring.mcp.adapter.persistence.PostgresHostedResourceStore;
import io.gen2spring.mcp.adapter.persistence.PostgresJobQueue;
import io.gen2spring.mcp.adapter.persistence.PostgresSpecificationCatalog;
import io.gen2spring.mcp.adapter.persistence.PostgresWorkerHeartbeatStore;
import io.gen2spring.mcp.adapter.storage.S3ObjectStorage;
import io.gen2spring.mcp.application.hosted.account.AccountStore;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.job.JobQueue;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.worker.WorkerHeartbeatStore;
import io.gen2spring.mcp.app.web.hosted.HostedSubmissionService;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetBucketAclRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

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
                || session.getTimeout() == null || session.getTimeout().compareTo(java.time.Duration.ofMinutes(1)) < 0
                || session.getTimeout().compareTo(java.time.Duration.ofHours(24)) > 0
                || !Boolean.TRUE.equals(cookie.getHttpOnly())
                || !Boolean.TRUE.equals(cookie.getSecure())
                || cookie.getSameSite() != org.springframework.boot.web.server.Cookie.SameSite.LAX) {
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
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
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
            hostedS3Client.headBucket(HeadBucketRequest.builder().bucket(properties.storage().bucket()).build());
            var grants = hostedS3Client.getBucketAcl(
                    GetBucketAclRequest.builder().bucket(properties.storage().bucket()).build()).grants();
            if (grants == null || grants.stream().anyMatch(grant ->
                    grant == null || grant.grantee() == null || grant.grantee().uri() != null)) {
                throw invalid();
            }
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
    HostedSubmissionService hostedSubmissionService(
            GeneratorRuntime hostedGeneratorRuntime,
            ObjectStorage hostedObjectStorage,
            SpecificationCatalog hostedSpecificationCatalog,
            HostedResourceStore hostedResourceStore,
            HostedJobService hostedJobService,
            ImportTargetProtector hostedImportTargetProtector,
            HostedWebProperties properties,
            Clock hostedClock) {
        return new HostedSubmissionService(
                hostedGeneratorRuntime, hostedObjectStorage, hostedSpecificationCatalog,
                hostedResourceStore, hostedJobService, hostedImportTargetProtector,
                properties.workRoot(), hostedClock);
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
