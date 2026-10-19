package com.protik.shared.idempotency;

/** Namespace for the value an idempotent action returns. */
public final class IdempotentAction {

    private IdempotentAction() {
    }

    /** The HTTP status and body to send (and, if a key was given, to remember). */
    public record Outcome<T>(int status, T body) {
    }
}
