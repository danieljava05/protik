package com.protik.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MailPropertiesTests {

    @Test
    void theDefaultsDeliverNothingAndPointAtTheLocalFrontEnd() {
        var p = new MailProperties("http://localhost:3000/", false, "log", "");
        assertThat(p.linkBase()).isEqualTo("http://localhost:3000");
        assertThat(p.sender()).isEqualTo("log");
    }

    @Test
    void smtpNeedsAFromAddressAndAnHttpsLinkExceptOnTheDevelopersOwnMachine() {
        assertThat(new MailProperties("https://app.example.test", false, "smtp", "no-reply@example.test").sender()).isEqualTo("smtp");
        assertThat(new MailProperties("http://localhost:3000", false, "smtp", "no-reply@example.test").sender()).isEqualTo("smtp");
        assertThat(new MailProperties("http://127.0.0.1:3000", false, "smtp", "no-reply@example.test").sender()).isEqualTo("smtp");
        for (String from : new String[] {"", "nobody", "a b@example.test", "<a@example.test>", "a@b"}) {
            assertThatThrownBy(() -> new MailProperties("https://app.example.test", false, "smtp", from)).as(from)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String base : new String[] {"http://app.example.test", "http://localhost.evil.test", "http://evil.test/localhost"}) {
            assertThatThrownBy(() -> new MailProperties(base, false, "smtp", "no-reply@example.test")).as(base)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void unknownSendersAndBadLinkBasesAreRefused() {
        assertThatThrownBy(() -> new MailProperties("https://a.example.test", false, "sendgrid", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MailProperties("javascript:alert(1)", false, "log", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MailProperties("ftp://a.example.test", false, "log", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
