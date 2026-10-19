package com.protik.shared.security;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Deny by default: everything needs a valid access token except the explicit
 * allow-list below. Stateless: no session, no cookies, so CSRF protection has
 * nothing to protect and is off on purpose (ADR 0011 decision 4).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemSecurityHandlers problems) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh",
                                "/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // CORS preflight carries no token
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Idempotent-Replayed"));
        cors.setAllowCredentials(false); // tokens are sent in a header, never as cookies
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
