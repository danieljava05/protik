package com.protik.identity.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SessionProperties.class, ThrottleProperties.class, MailProperties.class})
class IdentityConfiguration {

    /** Delivers nothing until a real provider is chosen (ADR 0021 decision 2); tests and a future provider replace it. */
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender emailSender(MailProperties mail) {
        return new LoggingEmailSender(mail.logLinks());
    }

    /**
     * Argon2id at the OWASP minimum: 19 MiB memory, 2 iterations, 1 lane. The encoded
     * hash records its own parameters, so raising them later keeps old hashes valid.
     * Spring's constructor order is (salt bytes, hash bytes, parallelism, memory KiB, iterations).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    }
}
