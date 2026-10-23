package com.protik.identity.service;

/**
 * How identity sends mail (ADR 0021 decision 2). Implementations must not throw for an undeliverable address and
 * must never log the link unless explicitly configured to, because the link is a credential.
 */
public interface EmailSender {

    enum Kind {
        VERIFY_EMAIL, RESET_PASSWORD
    }

    /** @param link the full link the person must open; it contains the token */
    record Message(String to, Kind kind, String link) {

        @Override
        public String toString() {
            return "Message[kind=" + kind + ", to=" + to + ", link=<redacted>]";
        }
    }

    void send(Message message);
}
