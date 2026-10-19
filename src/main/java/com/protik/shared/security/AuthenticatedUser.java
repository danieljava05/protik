package com.protik.shared.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the caller's identity from the verified access token. */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    /** The account ID, which is the token's {@code sub} claim. */
    public static UUID id(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}
