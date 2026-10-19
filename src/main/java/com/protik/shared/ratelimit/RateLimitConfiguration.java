package com.protik.shared.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class RateLimitConfiguration {

    @Bean
    RateLimitStore rateLimitStore(JdbcClient jdbcClient) {
        return new RateLimitStore(jdbcClient);
    }
}
