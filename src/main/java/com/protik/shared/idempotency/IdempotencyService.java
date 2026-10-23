package com.protik.shared.idempotency;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes retried POSTs safe (ADR 0009). The key is claimed, the business action
 * runs and its response is stored in <em>one</em> transaction, so a crash can
 * never leave "work done, key missing".
 *
 * <p>Usage from a controller (the action runs inside this service's transaction,
 * so the business service it calls joins it):
 * <pre>{@code
 * return idempotency.execute(scope, headerValue, "POST /v1/things", request, Thing.class,
 *         () -> new IdempotentAction.Outcome<>(201, thingService.create(request))).toResponseEntity();
 * }</pre>
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";
    /** Scope for endpoints with no authenticated user (for example registration). */
    public static final UUID ANONYMOUS_SCOPE = new UUID(0L, 0L);

    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final JsonMapper json;
    private final SecretKeySpec hmacKey;

    IdempotencyService(IdempotencyStore store, IdempotencyProperties props, JsonMapper json) {
        this.store = store;
        this.props = props;
        this.json = json;
        this.hmacKey = new SecretKeySpec(props.hashKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        if (IdempotencyProperties.DEVELOPMENT_KEY.equals(props.hashKey())) {
            log.warn("protik.idempotency.hash-key is the development default; set IDEMPOTENCY_HASH_KEY in production");
        }
    }

    /** Forgets everything stored for one user's keys; part of account deletion (ADR 0020). */
    public int forgetUser(UUID userId) {
        return store.deleteForScope(userId);
    }

    /**
     * @param scope      who owns the key (the user ID, or {@link #ANONYMOUS_SCOPE})
     * @param keyHeader  the raw {@code Idempotency-Key} header, or null if the client sent none
     * @param endpoint   a stable label such as {@code "POST /v1/auth/register"}
     * @param request    the request body (any object Jackson can serialise); bound into the fingerprint
     * @param bodyType   the response body type, needed to rebuild it on replay
     * @param action     the business work; runs at most once per key
     */
    @Transactional
    public <T> IdempotentResponse<T> execute(UUID scope, String keyHeader, String endpoint, Object request,
            Class<T> bodyType, Supplier<IdempotentAction.Outcome<T>> action) {
        if (keyHeader == null) {
            var outcome = action.get();
            return new IdempotentResponse<>(outcome.status(), outcome.body(), false);
        }
        String key = validate(keyHeader);
        byte[] fingerprint = fingerprint(endpoint, request);

        if (store.claim(scope, key, endpoint, fingerprint, props.ttl())) {
            var outcome = action.get();
            store.saveResponse(scope, key, outcome.status(),
                    outcome.body() == null ? null : json.writeValueAsString(outcome.body()));
            return new IdempotentResponse<>(outcome.status(), outcome.body(), false);
        }

        var stored = store.find(scope, key).orElseThrow(() ->
                // Claimed-and-vanished means the other transaction rolled back or was purged between
                // our two statements; the client may simply retry.
                new ApiException(ProblemType.CONFLICT, "The request is being processed; retry shortly."));
        if (!stored.endpoint().equals(endpoint) || !MessageDigest.isEqual(stored.fingerprint(), fingerprint)) {
            throw new ApiException(ProblemType.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request.");
        }
        if (stored.responseStatus() == null) {
            throw new ApiException(ProblemType.CONFLICT, "The request is being processed; retry shortly.");
        }
        T body = stored.responseBody() == null ? null : json.readValue(stored.responseBody(), bodyType);
        return new IdempotentResponse<>(stored.responseStatus(), body, true);
    }

    static String validate(String keyHeader) {
        if (!VALID_KEY.matcher(keyHeader).matches()) {
            throw new ApiException(ProblemType.VALIDATION_FAILED,
                    "Idempotency-Key must be 1 to 255 printable ASCII characters.");
        }
        return keyHeader;
    }

    /** HMAC-SHA-256 over the endpoint and the canonical JSON of the request. */
    private byte[] fingerprint(String endpoint, Object request) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            mac.update(endpoint.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(json.writeValueAsBytes(request));
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    @Scheduled(fixedDelayString = "${protik.idempotency.purge-interval:PT1H}",
            initialDelayString = "${protik.idempotency.purge-interval:PT1H}")
    void purgeExpired() {
        int removed = store.purgeExpired();
        if (removed > 0) {
            log.info("Purged {} expired idempotency key(s)", removed);
        }
    }
}
