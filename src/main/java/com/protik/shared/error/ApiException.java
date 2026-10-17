package com.protik.shared.error;

/**
 * Thrown by services to signal an expected, client-visible failure. The detail
 * is shown to the client, so it must never contain secrets or internals.
 */
public class ApiException extends RuntimeException {

    private final ProblemType problemType;

    public ApiException(ProblemType problemType, String detail) {
        super(detail);
        this.problemType = problemType;
    }

    public ProblemType problemType() {
        return problemType;
    }
}
