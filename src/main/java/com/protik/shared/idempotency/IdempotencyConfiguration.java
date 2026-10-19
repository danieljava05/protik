package com.protik.shared.idempotency;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
@EnableScheduling
class IdempotencyConfiguration {

    @Bean
    IdempotencyStore idempotencyStore(JdbcClient jdbcClient) {
        return new IdempotencyStore(jdbcClient);
    }
}
