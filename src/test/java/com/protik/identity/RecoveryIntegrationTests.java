package com.protik.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.EmailSender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0021: email verification, password reset and the common-password check, end to end through the API. */
@SpringBootTest(properties = "protik.mail.link-base=https://app.example.test/")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecoveryIntegrationTests.MailConfiguration.class})
class RecoveryIntegrationTests {

    static final String PASSWORD = "a long unusual passphrase 91";

    /** Records mail instead of sending it, and can be told to fail. */
    static class CapturingSender implements EmailSender {
        final List<Message> sent = new CopyOnWriteArrayList<>();
        volatile boolean failing;

        @Override
        public void send(Message message) {
            if (failing) {
                throw new IllegalStateException("provider down");
            }
            sent.add(message);
        }
    }

    @TestConfiguration
    static class MailConfiguration {
        @Bean
        @Primary
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    CapturingSender mail;

    @BeforeEach
    void setUp() {
        mail.sent.clear();
        mail.failing = false;
        db.sql("DELETE FROM shared.rate_limits").update();
    }

    // ---- helpers ----

    private record Person(UUID id, String username, String email) {
    }

    private Person register(String password) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "rec_" + suffix;
        String email = username + "@example.test";
        var result = mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "email", email, "password", password)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Person(UUID.fromString(JsonPath.read(result, "$.id")), username, email);
    }

    private static String json(String... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            sb.append(i > 0 ? "," : "").append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions login(Person p, String password) throws Exception {
        return postJson("/v1/auth/login", json("identifier", p.username(), "password", password));
    }

    private String accessToken(Person p, String password) throws Exception {
        return JsonPath.read(login(p, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.accessToken");
    }

    private ResultActions requestReset(String email) throws Exception {
        return postJson("/v1/auth/password-reset", json("email", email));
    }

    private ResultActions confirm(String token, String password) throws Exception {
        return postJson("/v1/auth/password-reset/confirm", json("token", token, "newPassword", password));
    }

    private ResultActions verify(String token) throws Exception {
        return postJson("/v1/auth/verify-email", json("token", token));
    }

    private static String tokenOf(EmailSender.Message message) {
        String link = message.link();
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private List<EmailSender.Message> mailTo(Person p, EmailSender.Kind kind) {
        return mail.sent.stream().filter(m -> m.to().equals(p.email()) && m.kind() == kind).toList();
    }

    private boolean verified(Person p) {
        return db.sql("SELECT verified_at IS NOT NULL FROM identity.login_identifiers WHERE account_id = :id")
                .param("id", p.id()).query(Boolean.class).single();
    }

    // ---- verification ----

    @Test
    void registeringSendsOneVerificationMailWhoseLinkVerifiesTheAddressOnce() throws Exception {
        Person p = register(PASSWORD);
        var sent = mailTo(p, EmailSender.Kind.VERIFY_EMAIL);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().link()).startsWith("https://app.example.test/verify-email?token=ptk_et_");
        assertThat(verified(p)).isFalse();

        verify(tokenOf(sent.getFirst())).andExpect(status().isNoContent());
        assertThat(verified(p)).isTrue();
        verify(tokenOf(sent.getFirst())).andExpect(status().isBadRequest());
    }

    @Test
    void theMessageNeverPrintsItsLink() throws Exception {
        Person p = register(PASSWORD);
        var message = mailTo(p, EmailSender.Kind.VERIFY_EMAIL).getFirst();
        assertThat(message.toString()).doesNotContain("ptk_et_").doesNotContain("token=");
    }

    @Test
    void junkUnknownAndWrongPurposeTokensAreAllTheSameBadRequest() throws Exception {
        Person p = register(PASSWORD);
        requestReset(p.email()).andExpect(status().isAccepted());
        String resetToken = tokenOf(mailTo(p, EmailSender.Kind.RESET_PASSWORD).getFirst());
        for (String bad : new String[] {"nonsense", "ptk_et_" + "A".repeat(43), "ptk_et_" + "x".repeat(500), resetToken}) {
            verify(bad).andExpect(status().isBadRequest());
        }
        // A verification token cannot reset a password either.
        String verifyToken = tokenOf(mailTo(p, EmailSender.Kind.VERIFY_EMAIL).getFirst());
        confirm(verifyToken, "another long passphrase 12").andExpect(status().isBadRequest());
        assertThat(verified(p)).isFalse();
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        Person p = register(PASSWORD);
        db.sql("UPDATE identity.email_tokens SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day' "
                + "WHERE account_id = :id").param("id", p.id()).update();
        verify(tokenOf(mailTo(p, EmailSender.Kind.VERIFY_EMAIL).getFirst())).andExpect(status().isBadRequest());
        assertThat(verified(p)).isFalse();
    }

    @Test
    void askingForAnotherVerificationMailMakesTheOldLinkUseless() throws Exception {
        Person p = register(PASSWORD);
        String old = tokenOf(mailTo(p, EmailSender.Kind.VERIFY_EMAIL).getFirst());
        mvc.perform(post("/v1/auth/verify-email/request").header("Authorization", "Bearer " + accessToken(p, PASSWORD)))
                .andExpect(status().isAccepted());
        var sent = mailTo(p, EmailSender.Kind.VERIFY_EMAIL);
        assertThat(sent).hasSize(2);
        verify(old).andExpect(status().isBadRequest());
        verify(tokenOf(sent.get(1))).andExpect(status().isNoContent());
    }

    @Test
    void requestingVerificationNeedsSignInIsLimitedAndSendsNothingOnceVerified() throws Exception {
        mvc.perform(post("/v1/auth/verify-email/request")).andExpect(status().isUnauthorized());
        Person p = register(PASSWORD);
        String bearer = "Bearer " + accessToken(p, PASSWORD);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/v1/auth/verify-email/request").header("Authorization", bearer)).andExpect(status().isAccepted());
        }
        mvc.perform(post("/v1/auth/verify-email/request").header("Authorization", bearer))
                .andExpect(status().isTooManyRequests());

        db.sql("DELETE FROM shared.rate_limits").update();
        verify(tokenOf(mailTo(p, EmailSender.Kind.VERIFY_EMAIL).getLast())).andExpect(status().isNoContent());
        int before = mail.sent.size();
        mvc.perform(post("/v1/auth/verify-email/request").header("Authorization", bearer)).andExpect(status().isAccepted());
        assertThat(mail.sent).hasSize(before);
    }

    @Test
    void aFailingMailProviderDoesNotStopRegistration() throws Exception {
        mail.failing = true;
        Person p = register(PASSWORD);
        assertThat(mail.sent).isEmpty();
        assertThat(db.sql("SELECT count(*) FROM identity.accounts WHERE id = :id").param("id", p.id()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void onlyTheHashOfATokenIsStored() throws Exception {
        Person p = register(PASSWORD);
        String raw = tokenOf(mailTo(p, EmailSender.Kind.VERIFY_EMAIL).getFirst());
        byte[] stored = db.sql("SELECT token_hash FROM identity.email_tokens WHERE account_id = :id").param("id", p.id())
                .query(byte[].class).single();
        assertThat(stored).hasSize(32).isNotEqualTo(raw.getBytes());
    }

    // ---- password reset ----

    @Test
    void resettingAPasswordSetsItEndsEverySessionAndVerifiesTheAddress() throws Exception {
        Person p = register(PASSWORD);
        String refresh = JsonPath.read(login(p, PASSWORD).andReturn().getResponse().getContentAsString(), "$.refreshToken");
        requestReset(p.email()).andExpect(status().isAccepted());
        var sent = mailTo(p, EmailSender.Kind.RESET_PASSWORD);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().link()).startsWith("https://app.example.test/reset-password?token=ptk_et_");

        String fresh = "brand new passphrase 77";
        confirm(tokenOf(sent.getFirst()), fresh).andExpect(status().isNoContent());

        login(p, PASSWORD).andExpect(status().isUnauthorized());
        login(p, fresh).andExpect(status().isOk());
        postJson("/v1/auth/refresh", json("refreshToken", refresh)).andExpect(status().isUnauthorized());
        assertThat(verified(p)).isTrue();
        confirm(tokenOf(sent.getFirst()), "yet another passphrase 55").andExpect(status().isBadRequest());
        login(p, fresh).andExpect(status().isOk());
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAndNoMail() throws Exception {
        Person p = register(PASSWORD);
        var known = requestReset(p.email()).andExpect(status().isAccepted()).andReturn().getResponse();
        var unknown = requestReset("nobody-" + UUID.randomUUID() + "@example.test").andExpect(status().isAccepted())
                .andReturn().getResponse();
        assertThat(unknown.getContentAsString()).isEqualTo(known.getContentAsString());
        assertThat(mail.sent.stream().filter(m -> m.kind() == EmailSender.Kind.RESET_PASSWORD)).hasSize(1);
    }

    @Test
    void aPasswordTheCheckRefusesLeavesTheLinkUsable() throws Exception {
        Person p = register(PASSWORD);
        requestReset(p.email()).andExpect(status().isAccepted());
        String token = tokenOf(mailTo(p, EmailSender.Kind.RESET_PASSWORD).getFirst());
        confirm(token, "short").andExpect(status().isBadRequest());
        confirm(token, "1q2w3e4r5t").andExpect(status().isBadRequest());
        confirm(token, p.username().toUpperCase()).andExpect(status().isBadRequest());
        login(p, PASSWORD).andExpect(status().isOk());
        confirm(token, "a perfectly fine passphrase 3").andExpect(status().isNoContent());
    }

    @Test
    void aNewRequestInvalidatesTheOlderLink() throws Exception {
        Person p = register(PASSWORD);
        requestReset(p.email()).andExpect(status().isAccepted());
        requestReset(p.email()).andExpect(status().isAccepted());
        var sent = mailTo(p, EmailSender.Kind.RESET_PASSWORD);
        assertThat(sent).hasSize(2);
        confirm(tokenOf(sent.get(0)), "an old link passphrase 8").andExpect(status().isBadRequest());
        confirm(tokenOf(sent.get(1)), "the new link passphrase 8").andExpect(status().isNoContent());
    }

    @Test
    void suspendedAccountsCannotResetAndGetNoMail() throws Exception {
        Person p = register(PASSWORD);
        db.sql("UPDATE identity.accounts SET status = 'SUSPENDED' WHERE id = :id").param("id", p.id()).update();
        requestReset(p.email()).andExpect(status().isAccepted());
        assertThat(mailTo(p, EmailSender.Kind.RESET_PASSWORD)).isEmpty();
    }

    @Test
    void aLinkIssuedBeforeSuspensionStopsWorking() throws Exception {
        Person p = register(PASSWORD);
        requestReset(p.email()).andExpect(status().isAccepted());
        String token = tokenOf(mailTo(p, EmailSender.Kind.RESET_PASSWORD).getFirst());
        db.sql("UPDATE identity.accounts SET status = 'SUSPENDED' WHERE id = :id").param("id", p.id()).update();
        confirm(token, "a suspended passphrase 4").andExpect(status().isBadRequest());
    }

    @Test
    void resetRequestsAreLimitedPerAddress() throws Exception {
        Person p = register(PASSWORD);
        for (int i = 0; i < 3; i++) {
            requestReset(p.email()).andExpect(status().isAccepted());
        }
        requestReset(p.email()).andExpect(status().isTooManyRequests());
        // Unknown addresses are limited the same way, so the limit itself reveals nothing.
        String unknown = "ghost-" + UUID.randomUUID() + "@example.test";
        for (int i = 0; i < 3; i++) {
            requestReset(unknown).andExpect(status().isAccepted());
        }
        requestReset(unknown).andExpect(status().isTooManyRequests());
    }

    @Test
    void fiveSimultaneousUsesOfOneLinkSucceedExactlyOnce() throws Exception {
        Person p = register(PASSWORD);
        requestReset(p.email()).andExpect(status().isAccepted());
        String token = tokenOf(mailTo(p, EmailSender.Kind.RESET_PASSWORD).getFirst());
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                String password = "simultaneous passphrase " + i + i;
                results.add(pool.submit(() -> {
                    go.await();
                    return confirm(token, password).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) {
                codes.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(codes.stream().filter(c -> c == 204)).hasSize(1);
            assertThat(codes.stream().filter(c -> c == 400)).hasSize(4);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- common passwords ----

    @Test
    void registrationRefusesACommonPasswordInAnyCase() throws Exception {
        for (String common : new String[] {"1q2w3e4r5t", "QWERTYUIOP", "1234567890"}) {
            postJson("/v1/auth/register", json("username", "com_" + UUID.randomUUID().toString().substring(0, 8),
                    "email", "c" + UUID.randomUUID() + "@example.test", "password", common)).andExpect(status().isBadRequest());
        }
    }
}
