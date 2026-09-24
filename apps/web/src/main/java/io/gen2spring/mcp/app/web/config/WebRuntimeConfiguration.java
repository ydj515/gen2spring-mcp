package io.gen2spring.mcp.app.web.config;

import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.app.web.presentation.local.ArtifactHandler;
import io.gen2spring.mcp.app.web.presentation.local.JobHandler;
import io.gen2spring.mcp.app.web.presentation.local.PreviewHandler;
import io.gen2spring.mcp.app.web.infrastructure.local.specification.SpecificationStore;
import io.gen2spring.mcp.app.web.presentation.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.infrastructure.local.job.GenerationJobManager;
import io.gen2spring.mcp.app.web.application.local.service.LocalGenerationService;
import io.gen2spring.mcp.app.web.application.local.port.out.GenerationConfigurationDecoder;
import io.gen2spring.mcp.app.web.infrastructure.local.configuration.GenerationConfigurationAdapter;
import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
class WebRuntimeConfiguration {
    @Bean
    GeneratorRuntime generatorApplication() {
        return GeneratorRuntime.defaults();
    }

    @Bean
    Clock webClock() {
        return Clock.systemUTC();
    }

    @Bean
    Path privateTemporaryParent() {
        try {
            return Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        } catch (IOException | RuntimeException exception) {
            throw WebErrorMapper.failure(
                    500, "WORKSPACE_CREATE_FAILED", "WEB_START",
                    "The private workspace parent could not be verified");
        }
    }

    @Bean(destroyMethod = "close")
    SpecificationStore specificationStore(
            Path privateTemporaryParent,
            GeneratorRuntime application) {
        return new SpecificationStore(privateTemporaryParent, application.analyzer());
    }

    @Bean(destroyMethod = "close")
    GenerationJobManager generationJobManager(
            Path privateTemporaryParent,
            GeneratorRuntime application,
            Clock webClock) {
        return new GenerationJobManager(
                privateTemporaryParent,
                application.pipeline()::generate,
                webClock,
                Duration.ofHours(1),
                ignored -> {});
    }

    @Bean
    GenerationConfigurationDecoder generationConfigurationDecoder(GeneratorRuntime application) {
        return new GenerationConfigurationAdapter(application.configurationParser());
    }

    @Bean
    LocalGenerationService localGenerationService(
            SpecificationStore specifications,
            GenerationJobManager jobs,
            GeneratorRuntime application,
            GenerationConfigurationDecoder configuration) {
        return new LocalGenerationService(specifications, jobs, application.pipeline(), configuration);
    }

    @Bean
    PreviewHandler previewHandler(
            LocalGenerationService generation,
            ObjectMapper json) {
        return new PreviewHandler(generation, json, GenerationConfigurationParser.MAX_BYTES);
    }

    @Bean
    JobHandler jobHandler(
            LocalGenerationService generation,
            ObjectMapper json) {
        return new JobHandler(generation, json, GenerationConfigurationParser.MAX_BYTES);
    }

    @Bean
    ArtifactHandler artifactHandler(LocalGenerationService generation) {
        return new ArtifactHandler(generation);
    }

    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> loopbackOnly() {
        InetAddress loopback = numericLoopback();
        return factory -> factory.setAddress(loopback);
    }

    private InetAddress numericLoopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("Numeric loopback address is unavailable", impossible);
        }
    }
}
