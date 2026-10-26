package com.protik.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.protik.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The whole path with the real sender: register, a real SMTP server receives the mail, and its link works. */
@SpringBootTest(properties = {"protik.mail.sender=smtp", "protik.mail.from=no-reply@protik.example",
        "protik.mail.link-base=https://app.example.test"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SmtpWiringIntegrationTests {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort()).withPerMethodLifecycle(false);

    @DynamicPropertySource
    static void mail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> smtp.getSmtp().getPort());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void registeringSendsARealMailWhoseLinkVerifiesTheAddress() throws Exception {
        String name = "smtp_" + UUID.randomUUID().toString().substring(0, 8);
        String email = name + "@example.test";
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"a long unusual passphrase 91\"}"))
                .andExpect(status().isCreated());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(smtp.getReceivedMessages()).hasSize(1));

        var mail = smtp.getReceivedMessages()[0];
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(email);
        String body = (String) mail.getContent();
        String token = body.substring(body.indexOf("token=") + 6).lines().findFirst().orElseThrow().strip();
        assertThat(body).contains("https://app.example.test/verify-email?token=ptk_et_");

        mvc.perform(post("/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isNoContent());
    }
}
