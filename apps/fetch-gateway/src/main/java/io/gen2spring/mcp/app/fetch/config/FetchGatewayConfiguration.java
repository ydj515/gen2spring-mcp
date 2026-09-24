package io.gen2spring.mcp.app.fetch.config;

import io.gen2spring.mcp.app.fetch.application.fetch.BoundedFetcher;
import io.gen2spring.mcp.app.fetch.infrastructure.client.fetch.ApacheFetchTransport;
import io.gen2spring.mcp.app.fetch.infrastructure.client.fetch.ValidatedDnsResolver;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
final class FetchGatewayConfiguration {
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    @Bean(destroyMethod = "close")
    ApacheFetchTransport fetchTransport() {
        return new ApacheFetchTransport(new ValidatedDnsResolver(), Duration.ofSeconds(5));
    }

    @Bean
    BoundedFetcher boundedFetcher(ApacheFetchTransport transport) {
        return new BoundedFetcher(
                transport,
                MAX_IMPORT_BYTES,
                MAX_IMPORT_BYTES,
                3,
                Duration.ofSeconds(30));
    }
}
