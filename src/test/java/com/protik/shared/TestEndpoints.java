package com.protik.shared;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.idempotency.IdempotencyService;
import com.protik.shared.idempotency.IdempotentAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that exist only in tests, to exercise cross-cutting behaviour. */
@RestController
class TestEndpoints {

    private static final Logger log = LoggerFactory.getLogger(TestEndpoints.class);

    record Payload(@NotBlank String name) {}

    record Created(int sequence, String name) {}

    /** How many times the idempotent action really ran. */
    static final AtomicInteger CREATED = new AtomicInteger();

    private final IdempotencyService idempotency;

    TestEndpoints(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    @PostMapping("/v1/_test/create")
    ResponseEntity<Created> create(@RequestHeader(value = IdempotencyService.HEADER, required = false) String key,
            @Valid @RequestBody Payload payload) {
        return idempotency.execute(IdempotencyService.ANONYMOUS_SCOPE, key, "POST /v1/_test/create", payload,
                Created.class,
                () -> new IdempotentAction.Outcome<>(201, new Created(CREATED.incrementAndGet(), payload.name())))
                .toResponseEntity();
    }

    @GetMapping("/v1/_test/api-error")
    String apiError() {
        throw new ApiException(ProblemType.CONFLICT, "Username already taken.");
    }

    @GetMapping("/v1/_test/boom")
    String boom() {
        throw new IllegalStateException("secret internal detail: jdbc:postgresql://db/prod");
    }

    @PostMapping("/v1/_test/validate")
    String validate(@Valid @RequestBody Payload payload) {
        return payload.name();
    }

    @GetMapping("/v1/_test/log")
    String logSomething() {
        log.info("handler-log-line");
        return "ok";
    }

    @GetMapping("/internal/_test/outside-v1")
    String outsideV1() {
        return "not part of the public API";
    }
}
