package com.protik.identity.service;

import com.protik.identity.persistence.SessionRepository;
import com.protik.identity.persistence.SessionRepository.TokenState;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.id.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sessions and rotating refresh tokens (ADR 0012). */
@Service
public class SessionService {

    /** A freshly issued session state. {@code refreshToken} is shown to the client once and never stored. */
    public record Session(UUID sessionId, UUID accountId, String refreshToken, Duration refreshTtl) {
    }

    static final String TOKEN_PREFIX = "ptk_rt_";
    private static final int MAX_TOKEN_LENGTH = 100;
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessions;
    private final SessionProperties props;
    private final SecureRandom random = new SecureRandom();

    SessionService(SessionRepository sessions, SessionProperties props) {
        this.sessions = sessions;
        this.props = props;
    }

    /** Starts a new session after a successful login. */
    @Transactional
    public Session start(UUID accountId) {
        UUID familyId = Ids.newId();
        sessions.insertFamily(familyId, accountId);
        String token = issueToken(familyId);
        return new Session(familyId, accountId, token, props.refreshTokenTtl());
    }

    /**
     * Exchanges a refresh token for a new one. Failure throws {@link ApiException}, and the
     * transaction must still commit in the reuse and suspended-account cases so the
     * revocation sticks, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawToken) {
        byte[] hash = hashOrReject(rawToken);
        var owner = sessions.findOwner(hash).orElseThrow(() -> invalid("unknown token"));

        // Serialise everything that touches this session, then read the state under the lock.
        sessions.lockFamily(owner.familyId());
        TokenState state = sessions.loadState(owner.familyId(), hash, props.maxAge())
                .orElseThrow(() -> invalid("token vanished"));

        if (state.familyRevoked()) {
            throw invalid("session revoked");
        }
        if (state.rotated()) {
            if (state.secondsSinceRotation() <= props.reuseGrace().toMillis() / 1000.0) {
                // Duplicate or racing request from the same holder: refuse, keep the session.
                throw invalid("rotated token presented again within the grace period");
            }
            sessions.revokeFamily(owner.familyId(), "TOKEN_REUSE");
            log.warn("Refresh token reuse detected: session {} of account {} revoked",
                    owner.familyId(), state.accountId());
            throw invalid("rotated token reused: session revoked");
        }
        if (state.tokenExpired() || state.familyTooOld()) {
            throw invalid("expired");
        }
        if (!"ACTIVE".equals(state.accountStatus())) {
            sessions.revokeFamily(owner.familyId(), "ADMIN");
            throw invalid("account not active: session revoked");
        }

        if (!sessions.markRotated(state.tokenId())) {
            // Second line of defence behind the family lock: someone else rotated it first.
            throw invalid("lost the rotation race");
        }
        String next = issueToken(owner.familyId());
        return new Session(owner.familyId(), state.accountId(), next, props.refreshTokenTtl());
    }

    /** Ends the session this token belongs to. Never reveals whether the token was valid. */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || !wellFormed(rawToken)) {
            return;
        }
        sessions.revokeByTokenHash(sha256(rawToken), "LOGOUT");
    }

    /** Ends every session of the account. */
    @Transactional
    public int logoutAll(UUID accountId) {
        return sessions.revokeAllForAccount(accountId, "LOGOUT");
    }

    @Scheduled(fixedDelayString = "${protik.session.purge-interval:PT1H}",
            initialDelayString = "${protik.session.purge-interval:PT1H}")
    void purge() {
        int removed = sessions.purge(props.purgeRetention(), props.maxAge());
        if (removed > 0) {
            log.info("Purged {} finished session(s)", removed);
        }
    }

    // ---- helpers ----

    private String issueToken(UUID familyId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.insertToken(Ids.newId(), familyId, sha256(token), props.refreshTokenTtl());
        return token;
    }

    private byte[] hashOrReject(String rawToken) {
        if (rawToken == null || !wellFormed(rawToken)) {
            throw invalid("malformed token");
        }
        return sha256(rawToken);
    }

    private static boolean wellFormed(String token) {
        return token.startsWith(TOKEN_PREFIX) && token.length() <= MAX_TOKEN_LENGTH;
    }

    static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** One public message for every failure; the real reason goes to the log only. */
    private static ApiException invalid(String reason) {
        log.info("Refresh rejected: {}", reason);
        return new ApiException(ProblemType.INVALID_REFRESH_TOKEN, "The refresh token is invalid or has expired.");
    }
}
