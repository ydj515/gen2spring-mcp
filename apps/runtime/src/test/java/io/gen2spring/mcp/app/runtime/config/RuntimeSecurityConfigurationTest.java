package io.gen2spring.mcp.app.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.gen2spring.mcp.app.runtime.presentation.mcp.RuntimeServerHandleRegistry;
import io.gen2spring.mcp.app.runtime.presentation.security.RuntimeBearerFilter;
import io.gen2spring.mcp.application.managed.runtime.service.RuntimeAccessAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

class RuntimeSecurityConfigurationTest {
    @Test
    void registersBearerAuthenticationOnlyInTheSecurityChain() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RuntimeAccessAuthenticator.class, () -> mock(RuntimeAccessAuthenticator.class));
            context.registerBean(RuntimeServerHandleRegistry.class, () -> mock(RuntimeServerHandleRegistry.class));
            context.register(SecurityInfrastructure.class, RuntimeSecurityConfiguration.class);
            context.refresh();

            RuntimeBearerFilter filter = context.getBean(RuntimeBearerFilter.class);
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertFalse(registration.isEnabled());
            assertSame(filter, registration.getFilter());
            assertEquals(1, context.getBean(SecurityFilterChain.class).getFilters().stream()
                    .filter(candidate -> candidate == filter).count());
        }
    }

    @EnableWebSecurity
    static class SecurityInfrastructure {}
}
