package com.protik.identity.service;

import com.protik.shared.id.Ids;
import com.protik.shared.security.SecurityProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived access tokens (ADR 0011 decision 3). */
@Service
public class TokenService {

    public record AccessToken(String value, long expiresInSeconds) {
    }

    private final JwtEncoder encoder;
    private final SecurityProperties props;
    private final JdbcClient db;

    TokenService(JwtEncoder encoder, SecurityProperties props, JdbcClient db) {
        this.encoder = encoder;
        this.props = props;
        this.db = db;
    }

    public AccessToken issue(UUID accountId, UUID sessionId) {
        Instant now = Instant.now();
        List<String> roles = db.sql("SELECT role FROM identity.account_roles WHERE account_id = :id ORDER BY role")
                .param("id", accountId).query(String.class).list();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(accountId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(Ids.newId().toString())
                .claim("sid", sessionId.toString())
                .claim("roles", roles)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AccessToken(token, props.accessTokenTtl().toSeconds());
    }
}
