package io.gen2spring.mcp.app.worker.config;

import io.gen2spring.mcp.adapter.container.DockerCommandRunner;
import io.gen2spring.mcp.app.worker.infrastructure.readiness.WorkerDependencyProbes;
import io.gen2spring.mcp.app.worker.infrastructure.readiness.WorkerReadiness;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
class WorkerInfrastructureConfiguration {
    @Bean
    WorkerReadiness workerReadiness(
            DataSource dataSource,
            S3Client s3,
            DockerCommandRunner docker,
            WorkerProperties properties) {
        return new WorkerReadiness(List.of(
                WorkerDependencyProbes.database(dataSource),
                WorkerDependencyProbes.storage(s3, properties.storage().bucket()),
                WorkerDependencyProbes.docker(
                        docker,
                        properties.docker().executable(),
                        properties.docker().socket(),
                        properties.docker().generationImage(),
                        properties.docker().importImage())));
    }
}
