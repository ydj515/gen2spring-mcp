package io.gen2spring.mcp.app.worker.config;

import io.gen2spring.mcp.adapter.container.BoundedDockerCommandRunner;
import io.gen2spring.mcp.adapter.container.DockerCliSandboxRuntime;
import io.gen2spring.mcp.adapter.container.DockerCliImportRuntime;
import io.gen2spring.mcp.adapter.container.DockerCommandRunner;
import io.gen2spring.mcp.adapter.cryptography.AesGcmImportTargetProtector;
import io.gen2spring.mcp.adapter.persistence.PostgresJobQueue;
import io.gen2spring.mcp.adapter.persistence.PostgresArtifactRetentionStore;
import io.gen2spring.mcp.adapter.persistence.PostgresWorkerHeartbeatStore;
import io.gen2spring.mcp.adapter.storage.S3ObjectStorage;
import io.gen2spring.mcp.app.worker.execution.WorkerHeartbeatPublisher;
import io.gen2spring.mcp.app.worker.execution.WorkerLoop;
import io.gen2spring.mcp.app.worker.execution.WorkerReadiness;
import io.gen2spring.mcp.application.hosted.job.JobQueue;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.job.WorkerLeaseService;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.ArtifactRetentionStore;
import io.gen2spring.mcp.application.hosted.worker.ArtifactRetentionService;
import io.gen2spring.mcp.application.hosted.worker.HostedWorker;
import io.gen2spring.mcp.application.hosted.worker.ImportRuntime;
import io.gen2spring.mcp.application.hosted.worker.SandboxRuntime;
import io.gen2spring.mcp.application.hosted.worker.WorkerHeartbeatStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {
    @Bean
    Clock workerClock() {
        return Clock.systemUTC();
    }

    @Bean
    JobQueue jobQueue(DataSource dataSource, Clock workerClock) {
        return new PostgresJobQueue(dataSource, workerClock);
    }

    @Bean
    WorkerHeartbeatStore workerHeartbeatStore(DataSource dataSource) {
        return new PostgresWorkerHeartbeatStore(dataSource);
    }

    @Bean
    ArtifactRetentionStore artifactRetentionStore(DataSource dataSource) {
        return new PostgresArtifactRetentionStore(dataSource);
    }

    @Bean
    ArtifactRetentionService artifactRetentionService(
            ArtifactRetentionStore artifactRetentionStore,
            ObjectStorage objectStorage,
            Clock workerClock) {
        return new ArtifactRetentionService(artifactRetentionStore, objectStorage, workerClock);
    }

    @Bean(destroyMethod = "close")
    S3Client workerS3Client(WorkerProperties properties) {
        char[] accessKey = secret(properties.storage().accessKeyFile());
        char[] secretKey = secret(properties.storage().secretKeyFile());
        try {
            return S3Client.builder()
                    .endpointOverride(properties.storage().endpoint())
                    .region(Region.of(properties.storage().region()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            new String(accessKey),
                            new String(secretKey))))
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build();
        } catch (RuntimeException failure) {
            throw invalid();
        } finally {
            Arrays.fill(accessKey, '\0');
            Arrays.fill(secretKey, '\0');
        }
    }

    @Bean
    ObjectStorage objectStorage(S3Client workerS3Client, WorkerProperties properties) {
        return new S3ObjectStorage(
                workerS3Client,
                properties.storage().bucket(),
                properties.storage().maxObjectBytes());
    }

    @Bean
    DockerCommandRunner dockerCommandRunner() {
        return new BoundedDockerCommandRunner();
    }

    @Bean
    SandboxRuntime sandboxRuntime(
            WorkerProperties properties,
            ObjectStorage objectStorage,
            DockerCommandRunner dockerCommandRunner) {
        return new DockerCliSandboxRuntime(
                properties.docker().executable(),
                properties.docker().socket(),
                properties.docker().generationImage(),
                properties.docker().workspaceRoot(),
                objectStorage,
                dockerCommandRunner);
    }

    @Bean
    ImportTargetProtector importTargetProtector(WorkerProperties properties) {
        return new AesGcmImportTargetProtector(
                properties.encryption().keyFiles(),
                properties.encryption().activeKeyId());
    }

    @Bean
    ImportRuntime importRuntime(
            WorkerProperties properties,
            ImportTargetProtector importTargetProtector,
            DockerCommandRunner dockerCommandRunner) {
        return new DockerCliImportRuntime(
                properties.docker().executable(),
                properties.docker().socket(),
                properties.docker().importImage(),
                properties.docker().importNetwork(),
                properties.gateway().endpoint(),
                properties.docker().workspaceRoot(),
                properties.docker().secretRoot(),
                properties.gateway().keyStore(),
                properties.gateway().keyStorePasswordFile(),
                properties.gateway().trustStore(),
                properties.gateway().trustStorePasswordFile(),
                importTargetProtector,
                dockerCommandRunner);
    }

    @Bean
    HostedWorker hostedWorker(
            JobQueue jobQueue,
            SandboxRuntime sandboxRuntime,
            ImportRuntime importRuntime,
            ObjectStorage objectStorage,
            WorkerProperties properties,
            Clock workerClock) {
        return new HostedWorker(
                jobQueue,
                sandboxRuntime,
                importRuntime,
                objectStorage,
                new WorkerId(properties.workerId()),
                properties.limits().sandboxLimits(),
                workerClock,
                properties.leaseDuration(),
                properties.artifactRetention());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    WorkerLoop workerLoop(
            WorkerReadiness workerReadiness,
            HostedWorker hostedWorker,
            WorkerHeartbeatStore workerHeartbeatStore,
            JobQueue jobQueue,
            ArtifactRetentionService artifactRetentionService,
            Clock workerClock,
            WorkerProperties properties) {
        WorkerId workerId = new WorkerId(properties.workerId());
        WorkerHeartbeatPublisher heartbeats = new WorkerHeartbeatPublisher(
                workerHeartbeatStore, workerId, workerClock, java.time.Duration.ofSeconds(10));
        WorkerLeaseService leases = new WorkerLeaseService(
                jobQueue, workerClock, properties.leaseDuration(), 3);
        return new WorkerLoop(
                workerReadiness,
                () -> hostedWorker.pollOnce() != HostedWorker.PollResult.EMPTY,
                properties.pollInterval(),
                heartbeats::publishIfDue,
                java.time.Duration.ofSeconds(10),
                () -> {
                    leases.recoverExpired();
                    artifactRetentionService.sweep(100);
                },
                java.time.Duration.ofSeconds(10));
    }

    private char[] secret(Path file) {
        byte[] bytes = null;
        try {
            if (file == null
                    || !file.isAbsolute()
                    || Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) < 1
                    || Files.size(file) > 1024) {
                throw invalid();
            }
            bytes = Files.readAllBytes(file);
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
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Hosted worker configuration is invalid");
    }
}
