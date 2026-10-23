package com.protik.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.mail.*} (ADR 0021). */
@ConfigurationProperties("protik.mail")
public record MailProperties(
        /* Where the front end lives; links in mail point here, never at the request's Host header. */
        @DefaultValue("http://localhost:3000") String linkBase,
        /* Log the full link (a credential). Development only. */
        @DefaultValue("false") boolean logLinks) {

    public MailProperties {
        if (linkBase == null || !linkBase.matches("https?://[^\\s/]+(/[^\\s]*)?")) {
            throw new IllegalArgumentException("protik.mail.link-base must be an http(s) URL");
        }
        linkBase = linkBase.endsWith("/") ? linkBase.substring(0, linkBase.length() - 1) : linkBase;
    }
}
