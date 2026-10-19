package com.protik.identity.service;

import com.protik.shared.error.RateLimitedException;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import com.protik.shared.ratelimit.RateLimiter.Limit;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Applies the login and registration limits of ADR 0013. Login attempts are counted
 * <em>before</em> the password is checked (so a burst cannot slip through) and handed back
 * on success, so only failures accumulate.
 */
@Component
public class AccessThrottle {

    /** The counters a login attempt is holding; pass it to {@link #loginSucceeded}. */
    public record LoginAttempt(String pairKey, String accountKey, String ipKey) {
    }

    private final RateLimiter limiter;
    private final ThrottleProperties props;

    AccessThrottle(RateLimiter limiter, ThrottleProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    /**
     * Counts a login attempt against the account+address, account and address limits.
     *
     * @throws RateLimitedException if any limit is exceeded; no password has been checked at that point
     */
    public LoginAttempt beginLogin(String identifier, String clientAddress) {
        String id = identifier.strip().toLowerCase(Locale.ROOT);
        var attempt = new LoginAttempt(
                RateLimitKeys.of("login-pair", id, clientAddress),
                RateLimitKeys.of("login-account", id),
                RateLimitKeys.of("login-ip", clientAddress));
        limiter.acquire(List.of(
                        new Limit(attempt.pairKey(), props.loginPairLimit(), props.loginWindow()),
                        new Limit(attempt.accountKey(), props.loginAccountLimit(), props.loginWindow()),
                        new Limit(attempt.ipKey(), props.loginIpLimit(), props.loginWindow())))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
        return attempt;
    }

    /** A correct password: forget the pair's failures and give the shared counters their attempt back. */
    public void loginSucceeded(LoginAttempt attempt) {
        limiter.refund(List.of(attempt.accountKey(), attempt.ipKey()));
        limiter.reset(List.of(attempt.pairKey()));
    }

    /** Counts a registration attempt from this address. Every attempt counts, successful or not. */
    public void beginRegistration(String clientAddress) {
        limiter.acquire(List.of(new Limit(RateLimitKeys.of("register-ip", clientAddress),
                        props.registerIpLimit(), props.registerWindow())))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
    }
}
