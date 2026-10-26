package com.protik.identity.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SessionProperties.class, ThrottleProperties.class, MailProperties.class})
class IdentityConfiguration {

    /** Delivers nothing (the default); tests and the smtp sender replace it (ADR 0021). */
    @Bean
    @ConditionalOnProperty(name = "protik.mail.sender", havingValue = "log", matchIfMissing = true)
    EmailSender emailSender(MailProperties mail) {
        return new LoggingEmailSender(mail.logLinks());
    }

    /** Real delivery through spring.mail.*; startup fails if no mail server is configured, rather than silently dropping mail. */
    @Bean
    @ConditionalOnProperty(name = "protik.mail.sender", havingValue = "smtp")
    EmailSender smtpEmailSender(JavaMailSender mailSender, MailProperties mail) {
        return new SmtpEmailSender(mailSender, mail.from());
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
