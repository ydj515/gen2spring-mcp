package io.gen2spring.mcp.app.fetch;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class FetchGatewayApplication {
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    public static void main(String[] args) {
        SpringApplication.run(FetchGatewayApplication.class, args);
    }

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
