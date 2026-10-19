package com.protik.identity.web;

import com.protik.identity.service.AccountService;
import com.protik.identity.service.TokenService;
import com.protik.shared.idempotency.IdempotencyService;
import com.protik.shared.idempotency.IdempotentAction;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
    private final IdempotencyService idempotency;

    AuthController(AccountService accounts, TokenService tokens, IdempotencyService idempotency) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.idempotency = idempotency;
    }

    /**
     * Creates an account. Supports {@code Idempotency-Key}, so a retry after a dropped
     * connection returns the original 201 instead of a confusing 409.
     */
    @PostMapping("/register")
    ResponseEntity<AuthDtos.RegisterResponse> register(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AuthDtos.RegisterRequest request) {
        return idempotency.execute(IdempotencyService.ANONYMOUS_SCOPE, idempotencyKey, "POST /v1/auth/register",
                        request, AuthDtos.RegisterResponse.class, () -> {
                            var created = accounts.register(request.username(), request.email(), request.password());
                            return new IdempotentAction.Outcome<>(201,
                                    new AuthDtos.RegisterResponse(created.id(), created.username()));
                        })
                .toResponseEntity();
    }

    /**
     * Exchanges credentials for an access token. Deliberately <em>not</em> idempotency-keyed
     * (ADR 0009): the response holds a token, which must not be stored for replay.
     */
    @PostMapping("/login")
    ResponseEntity<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        var accountId = accounts.authenticate(request.identifier(), request.password());
        var token = tokens.issue(accountId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AuthDtos.TokenResponse(token.value(), "Bearer", token.expiresInSeconds()));
    }
}
