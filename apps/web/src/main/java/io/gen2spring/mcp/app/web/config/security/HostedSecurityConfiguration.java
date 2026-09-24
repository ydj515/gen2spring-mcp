package io.gen2spring.mcp.app.web.config.security;

import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import io.gen2spring.mcp.application.hosted.account.port.out.AccountStore;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
public class HostedSecurityConfiguration {
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "font-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Bean
    HostedAccountResolver hostedAccountResolver(AccountStore accounts, Clock clock) {
        return new HostedAccountResolver(accounts, clock);
    }

    @Bean
    SecurityFilterChain hostedSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/login/**", "/oauth2/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.migrateSession()))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(Customizer.withDefaults())
                .headers(headers -> headers
                        .cacheControl(Customizer.withDefaults())
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)));
        return http.build();
    }
}
