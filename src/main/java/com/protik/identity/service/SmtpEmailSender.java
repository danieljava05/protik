package com.protik.identity.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Delivers mail through any SMTP server configured with {@code spring.mail.*} (ADR 0021 addendum). Plain text only, UTF-8,
 * marked as automatically generated so auto-replies are not sent back. The address list is parsed strictly by Jakarta
 * Mail, so a recipient containing line breaks cannot add headers; such a message fails instead.
 *
 * <p>Tested against an in-process SMTP server. It has not sent a real message through a real provider.
 */
class SmtpEmailSender implements EmailSender {

    /** One plain address: no whitespace, comma, semicolon, angle bracket, quote, parenthesis, bracket, colon or backslash. */
    private static final java.util.regex.Pattern ONE_ADDRESS = java.util.regex.Pattern
            .compile("[^\\s,;<>\"()\\[\\]\\\\:@]+@[^\\s,;<>\"()\\[\\]\\\\:@]+[.][^\\s,;<>\"()\\[\\]\\\\:@]+");

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, String from) {
        this.mail = mail;
        this.from = from;
    }

    @Override
    public void send(Message message) {
        if (message.to() == null || !ONE_ADDRESS.matcher(message.to()).matches()) {
            // Defence in depth: addresses are validated at registration, but a header-injection attempt must never get this far.
            throw new IllegalArgumentException("The recipient is not a single plain email address");
        }
        try {
            MimeMessage mime = mail.createMimeMessage();
            var helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(subject(message.kind()));
            helper.setText(body(message), false);
            mime.setHeader("Auto-Submitted", "auto-generated");
            mail.send(mime);
        } catch (jakarta.mail.MessagingException e) {
            throw new org.springframework.mail.MailPreparationException("Could not prepare the message", e);
        }
    }

    static String subject(Kind kind) {
        return switch (kind) {
            case VERIFY_EMAIL -> "Confirm your email address";
            case RESET_PASSWORD -> "Reset your ProTik password";
        };
    }

    static String body(Message message) {
        return switch (message.kind()) {
            case VERIFY_EMAIL -> "Hello,\n\nOpen this link to confirm your email address for ProTik. It works for 24 hours.\n\n"
                    + message.link() + "\n\nIf you did not create a ProTik account, ignore this message.\n";
            case RESET_PASSWORD -> "Hello,\n\nOpen this link to choose a new ProTik password. It works for 30 minutes and only once.\n\n"
                    + message.link() + "\n\nIf you did not ask for this, ignore this message: your password has not changed.\n";
        };
    }
}
