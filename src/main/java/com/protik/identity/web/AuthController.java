package com.protik.identity.web;

import com.protik.identity.service.AccessThrottle;
import com.protik.identity.service.AccountService;
import com.protik.identity.service.SessionService;
import com.protik.identity.service.TokenService;
import com.protik.shared.idempotency.IdempotencyService;
import com.protik.shared.idempotency.IdempotentAction;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.protik.shared.security.AuthenticatedUser;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
class AuthController {

    private final AccountService accounts;
    private final TokenService tokens;
    private final SessionService sessions;
    private final AccessThrottle throttle;
    private final IdempotencyService idempotency;

    AuthController(AccountService accounts, TokenService tokens, SessionService sessions, AccessThrottle throttle,
            IdempotencyService idempotency) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.sessions = sessions;
        this.throttle = throttle;
        this.idempotency = idempotency;
    }

    /**
     * Creates an account. Supports {@code Idempotency-Key}, so a retry after a dropped
     * connection returns the original 201 instead of a confusing 409.
     */
    @PostMapping("/register")
    ResponseEntity<AuthDtos.RegisterResponse> register(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AuthDtos.RegisterRequest request, HttpServletRequest http) {
        // Before the idempotency transaction, so a blocked request holds no database connection.
        // getRemoteAddr() is the TCP peer: X-Forwarded-For is client-controlled and deliberately ignored.
        throttle.beginRegistration(http.getRemoteAddr());
        return idempotency.execute(IdempotencyService.ANONYMOUS_SCOPE, idempotencyKey, "POST /v1/auth/register",
                        request, AuthDtos.RegisterResponse.class, () -> {
                            var created = accounts.register(request.username(), request.email(), request.password());
                            return new IdempotentAction.Outcome<>(201,
                                    new AuthDtos.RegisterResponse(created.id(), created.username()));
                        })
                .toResponseEntity();
    }

    /**
     * Exchanges credentials for an access token and a refresh token. Deliberately <em>not</em>
     * idempotency-keyed (ADR 0009): the response holds credentials, which must not be stored for replay.
     */
    @PostMapping("/login")
    ResponseEntity<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
            HttpServletRequest http) {
        var accountId = accounts.authenticate(request.identifier(), request.password(), http.getRemoteAddr());
        return tokenResponse(sessions.start(accountId));
    }

    /** Rotates the refresh token: the one presented stops working, a new pair is returned (ADR 0012). */
    @PostMapping("/refresh")
    ResponseEntity<AuthDtos.TokenResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return tokenResponse(sessions.refresh(request.refreshToken()));
    }

    /** Ends the session of the refresh token given. Always 204: it never reveals whether the token was valid. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody AuthDtos.LogoutRequest request) {
        sessions.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Ends every session of the caller. Requires a valid access token. */
    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt token) {
        sessions.logoutAll(AuthenticatedUser.id(token));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<AuthDtos.TokenResponse> tokenResponse(SessionService.Session session) {
        var access = tokens.issue(session.accountId(), session.sessionId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AuthDtos.TokenResponse(access.value(), "Bearer", access.expiresInSeconds(),
                        session.refreshToken(), session.refreshTtl().toSeconds()));
    }
}
