package com.protik.identity.web;

import com.protik.identity.service.AccountService;
import com.protik.shared.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private final AccountService accounts;

    MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** The caller's own profile; proves that an access token works end to end. */
    @GetMapping("/v1/me")
    AuthDtos.MeResponse me(@AuthenticationPrincipal Jwt token) {
        var profile = accounts.profile(AuthenticatedUser.id(token));
        return new AuthDtos.MeResponse(profile.accountId(), profile.username(), profile.displayName());
    }
}
