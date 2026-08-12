package io.gen2spring.mcp.web;

import io.gen2spring.mcp.application.GeneratorApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WebRuntimeConfiguration {
    @Bean
    GeneratorApplication generatorApplication() {
        return GeneratorApplication.defaults();
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
            GeneratorApplication application) {
        return new SpecificationStore(privateTemporaryParent, application.analyzer());
    }

    @Bean(destroyMethod = "close")
    GenerationJobManager generationJobManager(
            Path privateTemporaryParent,
            GeneratorApplication application,
            Clock webClock) {
        return new GenerationJobManager(
                privateTemporaryParent,
                application.pipeline()::generate,
                webClock,
                Duration.ofHours(1),
                ignored -> {});
    }

    @Bean
    PreviewHandler previewHandler(
            GeneratorApplication application,
            SpecificationStore specifications,
            ObjectMapper json) {
        return new PreviewHandler(application, specifications, json);
    }

    @Bean
    JobHandler jobHandler(
            GeneratorApplication application,
            SpecificationStore specifications,
            GenerationJobManager jobs,
            ObjectMapper json) {
        return new JobHandler(application, specifications, jobs, json);
    }

    @Bean
    ArtifactHandler artifactHandler(GenerationJobManager jobs) {
        return new ArtifactHandler(jobs);
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
