package com.protik.identity.service;

import com.protik.identity.persistence.EmailTokenRepository;
import com.protik.identity.persistence.EmailTokenRepository.Holder;
import com.protik.identity.persistence.SessionRepository;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.error.RateLimitedException;
import com.protik.shared.id.Ids;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Email verification and password reset (ADR 0021). */
@Service
public class EmailTokenService {

    static final String VERIFY = "VERIFY_EMAIL";
    static final String RESET = "RESET_PASSWORD";
    static final Duration VERIFY_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final String TOKEN_PREFIX = "ptk_et_";
    static final int MAX_TOKEN_LENGTH = 100;
    static final int VERIFICATIONS_PER_HOUR = 3;
    static final int RESETS_PER_ADDRESS_PER_HOUR = 3;
    static final int RESETS_PER_CLIENT_PER_HOUR = 20;

    private static final Logger log = LoggerFactory.getLogger(EmailTokenService.class);

    private final EmailTokenRepository tokens;
    private final SessionRepository sessions;
    private final PasswordEncoder encoder;
    private final EmailSender sender;
    private final MailProperties mail;
    private final RateLimiter limiter;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    EmailTokenService(EmailTokenRepository tokens, SessionRepository sessions, PasswordEncoder encoder,
            EmailSender sender, MailProperties mail, RateLimiter limiter, PlatformTransactionManager transactions) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.encoder = encoder;
        this.sender = sender;
        this.mail = mail;
        this.limiter = limiter;
        this.transaction = new TransactionTemplate(transactions);
    }

    // ---- verification ----

    /** Sends the verification mail for a new account. Joins the caller's transaction; mail goes out after it commits. */
    public void sendVerification(UUID accountId) {
        transaction.executeWithoutResult(s -> {
            Holder holder = tokens.findById(accountId).orElse(null);
            if (holder != null) {
                issue(holder, VERIFY, VERIFY_TTL, EmailSender.Kind.VERIFY_EMAIL, "/verify-email");
            }
        });
    }

    /** A signed-in person asks for another verification mail. Nothing is sent if the address is already verified. */
    public void requestVerification(UUID accountId) {
        limit("verify-request", accountId.toString(), VERIFICATIONS_PER_HOUR);
        if (!tokens.emailVerified(accountId)) {
            sendVerification(accountId);
        }
    }

    /** @throws ApiException 400 for any token that is unknown, used, expired or of the wrong kind */
    public void verify(String rawToken) {
        transaction.executeWithoutResult(s -> {
            var token = tokens.lockUsable(hashOrReject(rawToken), VERIFY).orElseThrow(EmailTokenService::badToken);
            tokens.markUsed(token.id());
            tokens.markEmailVerified(token.accountId());
        });
    }

    // ---- password reset ----

    /**
     * Sends a reset mail if the address belongs to an active account. Callers must answer the same way whether or
     * not it does (ADR 0021 decision 3). Not transactional as a whole: the limiter commits on its own.
     */
    public void requestReset(String rawEmail, String clientAddress) {
        String email = rawEmail.strip().toLowerCase(Locale.ROOT);
        limit("reset-address", email, RESETS_PER_ADDRESS_PER_HOUR);
        limit("reset-client", clientAddress, RESETS_PER_CLIENT_PER_HOUR);
        transaction.executeWithoutResult(s -> tokens.findByEmail(email)
                .filter(h -> "ACTIVE".equals(h.status()))
                .ifPresent(h -> issue(h, RESET, RESET_TTL, EmailSender.Kind.RESET_PASSWORD, "/reset-password")));
    }

    /**
     * Sets a new password, marks the address verified and ends every session. A password the policy refuses leaves the
     * token usable, so the person can try again.
     */
    public void confirmReset(String rawToken, String newPassword) {
        byte[] hash = hashOrReject(rawToken);
        transaction.executeWithoutResult(s -> {
            var token = tokens.lockUsable(hash, RESET).orElseThrow(EmailTokenService::badToken);
            Holder holder = tokens.findById(token.accountId()).orElseThrow(EmailTokenService::badToken);
            if (!"ACTIVE".equals(holder.status())) {
                throw badToken();
            }
            PasswordPolicy.check(newPassword, holder.username(), holder.email());
            tokens.markUsed(token.id());
            tokens.replacePassword(holder.accountId(), encoder.encode(newPassword));
            tokens.markEmailVerified(holder.accountId());
            sessions.revokeAllForAccount(holder.accountId(), "PASSWORD_RESET");
        });
    }

    @Scheduled(fixedDelayString = "${protik.mail.purge-interval:PT24H}", initialDelayString = "${protik.mail.purge-interval:PT24H}")
    void purge() {
        Integer removed = transaction.execute(s -> tokens.purgeOlderThan(Duration.ofDays(7)));
        if (removed != null && removed > 0) {
            log.info("Purged {} old email token(s)", removed);
        }
    }

    // ---- helpers ----

    private void issue(Holder holder, String purpose, Duration ttl, EmailSender.Kind kind, String path) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.invalidateUnused(holder.accountId(), purpose);
        tokens.insert(Ids.newId(), holder.accountId(), purpose, sha256(raw), ttl);
        var message = new EmailSender.Message(holder.email(), kind, mail.linkBase() + path + "?token=" + raw);
        afterCommit(() -> {
            try {
                sender.send(message);
            } catch (RuntimeException e) {
                // A failed send must not undo an account or reveal itself to the caller; the person can ask again.
                log.warn("Sending a {} mail failed ({}); the person can ask again", kind, e.getClass().getSimpleName());
            }
        });
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void limit(String scope, String key, int perHour) {
        limiter.acquire(List.of(new RateLimiter.Limit(RateLimitKeys.of(scope, key), perHour, Duration.ofHours(1))))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
    }

    private static byte[] hashOrReject(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX) || rawToken.length() > MAX_TOKEN_LENGTH) {
            throw badToken();
        }
        return sha256(rawToken);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static ApiException badToken() {
        return new ApiException(ProblemType.VALIDATION_FAILED, "That link is invalid or has expired. Ask for a new one.");
    }
}
