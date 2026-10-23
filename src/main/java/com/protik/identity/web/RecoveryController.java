package com.protik.identity.web;

import com.protik.identity.service.EmailTokenService;
import com.protik.shared.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Email verification and password reset (ADR 0021). */
@RestController
@RequestMapping("/v1/auth")
class RecoveryController {

    record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    record ConfirmReset(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 128) String newPassword) {

        @Override
        public String toString() {
            return "ConfirmReset[token=<redacted>, newPassword=<redacted>]";
        }
    }

    record VerifyRequest(@NotBlank @Size(max = 100) String token) {

        @Override
        public String toString() {
            return "VerifyRequest[token=<redacted>]";
        }
    }

    private final EmailTokenService tokens;

    RecoveryController(EmailTokenService tokens) {
        this.tokens = tokens;
    }

    /** Always 202, whether or not the address has an account, so it cannot be used to find out who is registered. */
    @PostMapping("/password-reset")
    ResponseEntity<Void> requestReset(@Valid @RequestBody ResetRequest request, HttpServletRequest http) {
        tokens.requestReset(request.email(), http.getRemoteAddr());
        return accepted();
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmReset(@Valid @RequestBody ConfirmReset request) {
        tokens.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Signed in: send another verification mail (nothing is sent if the address is already verified). */
    @PostMapping("/verify-email/request")
    ResponseEntity<Void> requestVerification(@AuthenticationPrincipal Jwt token) {
        tokens.requestVerification(AuthenticatedUser.id(token));
        return accepted();
    }

    @PostMapping("/verify-email")
    ResponseEntity<Void> verify(@Valid @RequestBody VerifyRequest request) {
        tokens.verify(request.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<Void> accepted() {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }
}
