package com.protik.identity.service;

import com.protik.identity.DeletionAcknowledgements;
import com.protik.identity.UserDeletionRequested;
import com.protik.identity.persistence.DeletionRepository;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.error.RateLimitedException;
import com.protik.shared.id.Ids;
import com.protik.shared.idempotency.IdempotencyService;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Account deletion that reaches every module (ADR 0020). */
@Service
public class DeletionService implements DeletionAcknowledgements {

    /** Every module that holds personal data must acknowledge before the account row goes. */
    static final Set<String> REQUIRED_MODULES = Set.of("video", "feed", "social", "engagement", "moderation");
    static final int ATTEMPTS_PER_HOUR = 5;
    static final Duration OVERDUE_AFTER = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(DeletionService.class);

    private final DeletionRepository repository;
    private final PasswordEncoder encoder;
    private final RateLimiter limiter;
    private final IdempotencyService idempotency;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final String dummyHash;

    DeletionService(DeletionRepository repository, PasswordEncoder encoder, RateLimiter limiter,
            IdempotencyService idempotency, ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.encoder = encoder;
        this.limiter = limiter;
        this.idempotency = idempotency;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Starts deleting the account after the person proves it is them with their password. The account stops working at
     * once; the data goes as each module acknowledges. Asking again returns the same request.
     * Deliberately not transactional: the Argon2 check must not hold a database connection.
     *
     * @return the request ID
     */
    public UUID request(UUID accountId, String password) {
        limiter.acquire(List.of(new RateLimiter.Limit(RateLimitKeys.of("delete-account", accountId.toString()),
                        ATTEMPTS_PER_HOUR, Duration.ofHours(1))))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
        String hash = transaction.execute(s -> repository.passwordHash(accountId).orElse(dummyHash));
        if (!encoder.matches(password == null ? "" : password, hash)) {
            throw new ApiException(ProblemType.INVALID_CREDENTIALS, "The password is incorrect.");
        }
        return transaction.execute(s -> begin(accountId));
    }

    private UUID begin(UUID accountId) {
        String status = repository.lockAccountStatus(accountId)
                .orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Account not found."));
        if ("DELETION_PENDING".equals(status)) {
            return repository.findByAccount(accountId).orElseThrow().id();
        }
        UUID requestId = Ids.newId();
        repository.markPending(accountId);
        repository.revokeSessions(accountId);
        repository.insertRequest(requestId, accountId);
        events.publishEvent(new UserDeletionRequested(accountId, requestId));
        return requestId;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void acknowledge(UUID requestId, String module) {
        if (!REQUIRED_MODULES.contains(module)) {
            throw new IllegalArgumentException("Unknown module: " + module);
        }
        var request = repository.lockRequest(requestId).orElse(null);
        if (request == null || request.completed()) {
            return;
        }
        repository.insertAck(requestId, module);
        if (repository.countAcks(requestId) >= REQUIRED_MODULES.size()) {
            idempotency.forgetUser(request.accountId());
            repository.deleteAccount(request.accountId());
            repository.complete(requestId);
            log.info("Deletion request {} complete: every module acknowledged and the account is gone", requestId);
        }
    }

    /** Re-announces requests that some module has not finished; listeners are idempotent. */
    @Scheduled(fixedDelayString = "${protik.deletion.retry-interval:PT10M}",
            initialDelayString = "${protik.deletion.retry-interval:PT10M}")
    void retryOverdue() {
        transaction.executeWithoutResult(s -> {
            for (var overdue : repository.overdue(OVERDUE_AFTER)) {
                log.warn("Deletion request {} is overdue; announcing it again", overdue.id());
                events.publishEvent(new UserDeletionRequested(overdue.accountId(), overdue.id()));
            }
        });
    }
}
