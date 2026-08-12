package io.gen2spring.mcp.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class Gen2SpringWebApplication {
    private Gen2SpringWebApplication() {}

    public static void main(String[] arguments) {
        SpringApplication.run(Gen2SpringWebApplication.class, arguments);
    }
}
