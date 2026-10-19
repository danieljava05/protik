package com.protik.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request and response bodies of the auth API. Passwords are redacted from toString(). */
final class AuthDtos {

    private AuthDtos() {
    }

    record RegisterRequest(
            @NotBlank @Size(min = 3, max = 30) @Pattern(regexp = "[A-Za-z0-9_.]+",
                    message = "must contain only letters, digits, underscore and dot") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "RegisterRequest[username=" + username + ", email=" + email + ", password=<redacted>]";
        }
    }

    record RegisterResponse(UUID id, String username) {
    }

    record LoginRequest(
            @NotBlank @Size(max = 254) String identifier,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "LoginRequest[identifier=" + identifier + ", password=<redacted>]";
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
            long refreshExpiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[tokens=<redacted>]";
        }
    }

    record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=<redacted>]";
        }
    }

    record LogoutRequest(@Size(max = 200) String refreshToken) {

        @Override
        public String toString() {
            return "LogoutRequest[refreshToken=<redacted>]";
        }
    }

    record MeResponse(UUID id, String username, String displayName) {
    }
}
