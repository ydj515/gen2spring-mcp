package io.gen2spring.mcp.app.provideregress;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ProviderEgressApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProviderEgressApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    ApacheProviderTransport providerTransport() {
        return new ApacheProviderTransport(new ValidatedProviderResolver(), Duration.ofSeconds(5));
    }
}
