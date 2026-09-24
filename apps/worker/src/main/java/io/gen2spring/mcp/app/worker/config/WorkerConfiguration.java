package io.gen2spring.mcp.app.worker.config;

import io.gen2spring.mcp.adapter.container.BoundedDockerCommandRunner;
import io.gen2spring.mcp.adapter.container.DockerCliImportRuntime;
import io.gen2spring.mcp.adapter.container.DockerCliSandboxRuntime;
import io.gen2spring.mcp.adapter.container.DockerCommandRunner;
import io.gen2spring.mcp.adapter.cryptography.AesGcmImportTargetProtector;
import io.gen2spring.mcp.adapter.persistence.job.PostgresJobQueue;
import io.gen2spring.mcp.adapter.persistence.storage.PostgresArtifactRetentionStore;
import io.gen2spring.mcp.adapter.persistence.worker.PostgresWorkerHeartbeatStore;
import io.gen2spring.mcp.adapter.storage.S3ObjectStorage;
import io.gen2spring.mcp.app.worker.application.worker.port.in.WorkerTasks;
import io.gen2spring.mcp.app.worker.application.worker.service.WorkerHeartbeatPublisher;
import io.gen2spring.mcp.app.worker.application.worker.service.WorkerTaskService;
import io.gen2spring.mcp.app.worker.infrastructure.readiness.WorkerReadiness;
import io.gen2spring.mcp.app.worker.infrastructure.scheduling.ExecutorLeaseMonitorScheduler;
import io.gen2spring.mcp.app.worker.infrastructure.scheduling.WorkerLoop;
import io.gen2spring.mcp.application.hosted.imports.port.out.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.job.port.out.JobQueue;
import io.gen2spring.mcp.application.hosted.job.service.WorkerLeaseService;
import io.gen2spring.mcp.application.hosted.storage.port.out.ArtifactRetentionStore;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.hosted.worker.port.out.ImportRuntime;
import io.gen2spring.mcp.application.hosted.worker.port.out.LeaseMonitorScheduler;
import io.gen2spring.mcp.application.hosted.worker.port.out.SandboxRuntime;
import io.gen2spring.mcp.application.hosted.worker.port.out.WorkerHeartbeatStore;
import io.gen2spring.mcp.application.hosted.worker.service.ArtifactRetentionService;
import io.gen2spring.mcp.application.hosted.worker.service.HostedWorker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
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
        char[] accessKey = null;
        char[] secretKey = null;
        try {
            accessKey = secret(properties.storage().accessKeyFile());
            secretKey = secret(properties.storage().secretKeyFile());
            return S3Client.builder()
                    .endpointOverride(properties.storage().endpoint())
                    .region(Region.of(properties.storage().region()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            new String(accessKey),
                            new String(secretKey))))
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .chunkedEncodingEnabled(false)
                            .build())
                    .build();
        } catch (RuntimeException failure) {
            throw invalid();
        } finally {
            if (accessKey != null) Arrays.fill(accessKey, '\0');
            if (secretKey != null) Arrays.fill(secretKey, '\0');
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
    LeaseMonitorScheduler leaseMonitorScheduler() {
        return new ExecutorLeaseMonitorScheduler();
    }

    @Bean
    HostedWorker hostedWorker(
            JobQueue jobQueue,
            SandboxRuntime sandboxRuntime,
            ImportRuntime importRuntime,
            ObjectStorage objectStorage,
            WorkerProperties properties,
            Clock workerClock,
            LeaseMonitorScheduler leaseMonitorScheduler) {
        return new HostedWorker(
                jobQueue,
                sandboxRuntime,
                importRuntime,
                objectStorage,
                new WorkerId(properties.workerId()),
                properties.limits().sandboxLimits(),
                workerClock,
                properties.leaseDuration(),
                properties.artifactRetention(),
                leaseMonitorScheduler);
    }

    @Bean
    WorkerTasks workerTasks(
            HostedWorker hostedWorker,
            WorkerHeartbeatStore workerHeartbeatStore,
            JobQueue jobQueue,
            ArtifactRetentionService artifactRetentionService,
            Clock workerClock,
            WorkerProperties properties) {
        WorkerId workerId = new WorkerId(properties.workerId());
        WorkerHeartbeatPublisher heartbeats = new WorkerHeartbeatPublisher(
                workerHeartbeatStore, workerId, workerClock, Duration.ofSeconds(10));
        WorkerLeaseService leases = new WorkerLeaseService(
                jobQueue, workerClock, properties.leaseDuration(), 3);
        return new WorkerTaskService(hostedWorker, heartbeats, leases, artifactRetentionService);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    WorkerLoop workerLoop(
            WorkerReadiness workerReadiness,
            WorkerTasks workerTasks,
            WorkerProperties properties) {
        return new WorkerLoop(
                workerReadiness,
                workerTasks,
                properties.pollInterval(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10));
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
