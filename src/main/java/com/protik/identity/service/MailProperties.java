package com.protik.identity.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.mail.*} (ADR 0021). */
@ConfigurationProperties("protik.mail")
public record MailProperties(
        /* Where the front end lives; links in mail point here, never at the request's Host header. */
        @DefaultValue("http://localhost:3000") String linkBase,
        /* Log the full link (a credential). Development only. */
        @DefaultValue("false") boolean logLinks,
        /* {@code log} delivers nothing (default); {@code smtp} sends through spring.mail.* (ADR 0021 addendum). */
        @DefaultValue("log") String sender,
        /* The From address; required for smtp. */
        @DefaultValue("") String from) {

    public MailProperties {
        if (linkBase == null || !linkBase.matches("https?://[^\\s/]+(/[^\\s]*)?")) {
            throw new IllegalArgumentException("protik.mail.link-base must be an http(s) URL");
        }
        if (!sender.equals("log") && !sender.equals("smtp")) {
            throw new IllegalArgumentException("protik.mail.sender must be log or smtp");
        }
        if (sender.equals("smtp")) {
            if (!from.matches("[^\\s<>@]+@[^\\s<>@]+[.][^\\s<>@]+")) {
                throw new IllegalArgumentException("protik.mail.from must be an email address when protik.mail.sender=smtp");
            }
            // A link in a real email must not travel over plain HTTP, except to a developer's own machine.
            if (!linkBase.startsWith("https://") && !linkBase.matches("http://(localhost|127[.]0[.]0[.]1)(:[0-9]+)?(/.*)?")) {
                throw new IllegalArgumentException("protik.mail.link-base must be https when protik.mail.sender=smtp");
            }
        }
        linkBase = linkBase.endsWith("/") ? linkBase.substring(0, linkBase.length() - 1) : linkBase;
    }
}
