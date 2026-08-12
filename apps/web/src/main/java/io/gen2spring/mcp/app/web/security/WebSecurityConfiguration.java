package io.gen2spring.mcp.app.web.security;

import java.util.Objects;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import io.gen2spring.mcp.app.web.error.WebErrorResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
public class WebSecurityConfiguration {
    public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; "
            + "style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain localSecurity(
            HttpSecurity http,
            LocalRequestSecurityFilter localRequests,
            WebErrorResponseWriter writer) throws Exception {
        Objects.requireNonNull(http, "http");
        WebErrorMapper.WebFailure forbidden = new WebErrorMapper.WebFailure(
                403, "REQUEST_FORBIDDEN", "HTTP", "The request is not allowed");
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(
                        (request, response, failure) -> writer.write(response, forbidden)))
                .headers(headers -> headers
                        .cacheControl(Customizer.withDefaults())
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                .addFilterBefore(localRequests, CsrfFilter.class);
        return http.build();
    }
}
