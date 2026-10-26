package com.protik.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.protik.identity.service.EmailSender.Kind;
import com.protik.identity.service.EmailSender.Message;
import jakarta.mail.internet.MimeMessage;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** The SMTP sender against a real SMTP server running in this process (GreenMail), not against a real provider. */
class SmtpEmailSenderTests {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    SmtpEmailSender sender;

    @BeforeEach
    void setUp() {
        var mail = new JavaMailSenderImpl();
        mail.setHost("127.0.0.1");
        mail.setPort(smtp.getSmtp().getPort());
        mail.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
        mail.getJavaMailProperties().put("mail.smtp.timeout", "2000");
        sender = new SmtpEmailSender(mail, "no-reply@protik.example");
    }

    private MimeMessage only() {
        MimeMessage[] received = smtp.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    @Test
    void deliversAVerificationMailWithTheLinkTheSubjectAndTheSender() throws Exception {
        sender.send(new Message("ada@example.test", Kind.VERIFY_EMAIL, "https://app.example.test/verify-email?token=ptk_et_abc"));
        MimeMessage mail = only();
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("ada@example.test");
        assertThat(mail.getFrom()[0].toString()).isEqualTo("no-reply@protik.example");
        assertThat(mail.getSubject()).isEqualTo("Confirm your email address");
        assertThat(mail.getHeader("Auto-Submitted")[0]).isEqualTo("auto-generated");
        assertThat(mail.getContentType()).startsWith("text/plain");
        assertThat((String) mail.getContent()).contains("https://app.example.test/verify-email?token=ptk_et_abc")
                .contains("24 hours");
    }

    @Test
    void deliversAResetMailThatSaysItWorksOnceAndForThirtyMinutes() throws Exception {
        sender.send(new Message("ada@example.test", Kind.RESET_PASSWORD, "https://app.example.test/reset-password?token=ptk_et_xyz"));
        MimeMessage mail = only();
        assertThat(mail.getSubject()).isEqualTo("Reset your ProTik password");
        assertThat((String) mail.getContent()).contains("reset-password?token=ptk_et_xyz").contains("30 minutes")
                .contains("your password has not changed");
    }

    @Test
    void keepsNonAsciiAddressesAndTextIntact() throws Exception {
        sender.send(new Message("ada@example.test", Kind.RESET_PASSWORD, "https://app.example.test/x?name=ọmọ"));
        assertThat((String) only().getContent()).contains("name=ọmọ");
    }

    @Test
    void aRecipientCannotSmuggleInHeadersOrExtraRecipients() {
        for (String hostile : new String[] {"a@example.test\r\nBcc: victim@example.test", "a@example.test\nSubject: pwned",
                "a@example.test, b@example.test", "<a@example.test>\r\nX: y", "", "not an address"}) {
            assertThatThrownBy(() -> sender.send(new Message(hostile, Kind.VERIFY_EMAIL, "https://app.example.test/x")))
                    .as(hostile.replace("\r", "\\r").replace("\n", "\\n")).isInstanceOf(RuntimeException.class);
        }
        assertThat(smtp.getReceivedMessages()).isEmpty();
    }

    @Test
    void aServerThatIsNotThereIsAnErrorTheCallerCanHandle() {
        var mail = new JavaMailSenderImpl();
        mail.setHost("127.0.0.1");
        mail.setPort(1); // nothing listens here
        mail.getJavaMailProperties().put("mail.smtp.connectiontimeout", "500");
        var broken = new SmtpEmailSender(mail, "no-reply@protik.example");
        assertThatThrownBy(() -> broken.send(new Message("a@example.test", Kind.VERIFY_EMAIL, "https://x.example.test/")))
                .isInstanceOf(org.springframework.mail.MailException.class);
    }

    @Test
    void everyKindHasASubjectAndABodyWithItsLink() {
        Stream.of(Kind.values()).forEach(kind -> {
            assertThat(SmtpEmailSender.subject(kind)).isNotBlank();
            assertThat(SmtpEmailSender.body(new Message("a@example.test", kind, "https://l.example.test/t"))).contains("https://l.example.test/t");
        });
    }
}
