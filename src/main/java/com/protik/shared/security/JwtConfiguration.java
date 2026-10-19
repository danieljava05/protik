package com.protik.shared.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Issuing and verifying access tokens with the vetted Nimbus JOSE library
 * (never hand-rolled crypto). The decoder accepts HS256 only, so tokens using
 * {@code alg: none} or any other algorithm are rejected, and it checks expiry
 * and issuer.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

    @Bean
    SecretKey jwtSigningKey(SecurityProperties props) {
        byte[] secret;
        if (props.jwtSecret().isBlank()) {
            secret = new byte[48];
            new SecureRandom().nextBytes(secret);
            log.warn("protik.security.jwt-secret is not set: using a random secret for this process. "
                    + "Tokens will stop working on restart. Set JWT_SECRET (32+ bytes) in any real deployment.");
        } else {
            secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer())));
        return decoder;
    }
}
