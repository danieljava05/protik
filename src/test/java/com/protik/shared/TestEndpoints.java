package com.protik.shared;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that exist only in tests, to exercise cross-cutting behaviour. */
@RestController
class TestEndpoints {

    private static final Logger log = LoggerFactory.getLogger(TestEndpoints.class);

    record Payload(@NotBlank String name) {}

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
