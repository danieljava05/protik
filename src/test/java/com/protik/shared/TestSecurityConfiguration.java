package com.protik.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Opens only the test-only endpoints so cross-cutting tests need no token. It matches
 * paths that exist nowhere in production code, ahead of the real (deny-by-default) chain.
 */
@Configuration(proxyBeanMethods = false)
class TestSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain testOnlySecurityChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/v1/_test/**", "/internal/_test/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
