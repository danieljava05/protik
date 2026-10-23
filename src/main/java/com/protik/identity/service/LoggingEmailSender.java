package com.protik.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers nothing (ADR 0021 decision 2). It records that a message was created and, only when
 * {@code protik.mail.log-links=true}, the link, so a developer can complete a flow locally.
 */
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final boolean logLinks;

    LoggingEmailSender(boolean logLinks) {
        this.logLinks = logLinks;
    }

    @Override
    public void send(Message message) {
        if (logLinks) {
            log.info("[mail NOT DELIVERED, development only] {} for {}: {}", message.kind(), message.to(), message.link());
        } else {
            log.info("[mail NOT DELIVERED, no provider configured] {} created", message.kind());
        }
    }
}
