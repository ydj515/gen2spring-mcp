package io.gen2spring.mcp.app.worker;

import io.gen2spring.mcp.app.worker.config.WorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WorkerProperties.class)
public class Gen2SpringWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(Gen2SpringWorkerApplication.class, args);
    }
}
