package io.gen2spring.mcp.app.runtime.security;

import io.gen2spring.mcp.app.runtime.server.RuntimeServerHandleRegistry;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccessAuthenticator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
final class RuntimeSecurityConfiguration {
    @Bean
    RuntimeBearerFilter runtimeBearerFilter(
            RuntimeAccessAuthenticator authenticator,
            RuntimeServerHandleRegistry handles) {
        return new RuntimeBearerFilter(authenticator, handles::invalidate);
    }

    @Bean
    SecurityFilterChain runtimeSecurity(HttpSecurity http, RuntimeBearerFilter filter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/mcp/**", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
