package io.gen2spring.mcp.app.web;

import io.gen2spring.mcp.app.web.config.WebModeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = {
        UserDetailsServiceAutoConfiguration.class,
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
@EnableConfigurationProperties(WebModeProperties.class)
public class Gen2SpringWebApplication {
    private Gen2SpringWebApplication() {}

    public static void main(String[] arguments) {
        SpringApplication.run(Gen2SpringWebApplication.class, arguments);
    }
}
