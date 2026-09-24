package io.gen2spring.mcp.app.web.config;

import io.gen2spring.mcp.app.web.application.local.service.LocalProfileService;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProfileConfiguration {
    @Bean
    LocalProfileService localProfileService(GeneratorRuntime generator) {
        return new LocalProfileService(generator.compatibilityCatalog());
    }
}
