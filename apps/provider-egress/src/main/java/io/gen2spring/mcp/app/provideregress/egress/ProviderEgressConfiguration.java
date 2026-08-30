package io.gen2spring.mcp.app.provideregress.egress;

import io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
final class ProviderEgressConfiguration {
    @Bean(destroyMethod = "close")
    ApacheProviderTransport providerTransport() {
        return new ApacheProviderTransport(new ValidatedProviderResolver(), Duration.ofSeconds(5));
    }

    @Bean
    ProviderEgressService providerEgressService(ApacheProviderTransport transport) {
        return new ProviderEgressService(transport, new ProviderEgressCodec());
    }
}
