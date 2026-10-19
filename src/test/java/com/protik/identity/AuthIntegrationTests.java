package com.protik.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.TokenService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Registration, login, access tokens and the security edge cases (ADR 0011). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthIntegrationTests {

    private static final String PASSWORD = "S3cret-Pa55word!";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    TokenService tokenService;
    @MockitoSpyBean
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM identity.accounts").update(); // cascades to credentials, profiles, identifiers
        db.sql("DELETE FROM shared.idempotency_keys").update();
    }

    // ---- helpers ----

    private ResultActions register(String username, String email, String password, String idempotencyKey)
            throws Exception {
        var request = post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(
                "{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(username, email, password));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    private ResultActions register(String username, String email) throws Exception {
        return register(username, email, PASSWORD, null);
    }

    private ResultActions login(String identifier, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"%s\",\"password\":\"%s\"}".formatted(identifier, password)));
    }

    private String tokenFor(String username, String email) throws Exception {
        register(username, email).andExpect(status().isCreated());
        String body = login(username, PASSWORD).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions me(String authorization) throws Exception {
        var request = get("/v1/me");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    private long countAccounts(String username) {
        return db.sql("SELECT count(*) FROM identity.accounts WHERE username = :u").param("u", username)
                .query(Long.class).single();
    }

    // ---- registration ----

    @Test
    void registrationCreatesAccountEmailCredentialAndProfileTogether() throws Exception {
        MvcResult result = register("Ada_Obi", "Ada@Example.com").andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Ada_Obi"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();
        UUID id = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));

        assertThat(id.version()).as("UUIDv7 ids").isEqualTo(7);
        assertThat(db.sql("SELECT value FROM identity.login_identifiers WHERE account_id = :id").param("id", id)
                .query(String.class).single()).as("email stored normalised").isEqualTo("ada@example.com");
        assertThat(db.sql("SELECT display_name FROM identity.profiles WHERE account_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("Ada_Obi");
        assertThat(db.sql("SELECT status FROM identity.accounts WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void thePasswordIsStoredOnlyAsAnArgon2idHashWithTheDocumentedParameters() throws Exception {
        register("hash_check", "hash@example.com").andExpect(status().isCreated());
        String stored = db.sql("SELECT password_hash FROM identity.password_credentials").query(String.class).single();
        assertThat(stored).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(PASSWORD);
        // two accounts with the same password must not share a hash (random salt)
        register("hash_check2", "hash2@example.com").andExpect(status().isCreated());
        assertThat(db.sql("SELECT count(DISTINCT password_hash) FROM identity.password_credentials")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void usernamesAndEmailsAreUniqueIgnoringCase() throws Exception {
        register("taken_name", "one@example.com").andExpect(status().isCreated());
        register("TAKEN_NAME", "two@example.com").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:conflict"))
                .andExpect(jsonPath("$.detail").value("That username is already taken."));
        register("other_name", "ONE@example.com").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("An account with that email already exists."));
    }

    @Test
    void aFailedRegistrationLeavesNothingBehind() throws Exception {
        register("first_user", "dup@example.com").andExpect(status().isCreated());
        // the account row is inserted before the email row fails: the whole thing must roll back
        register("second_user", "dup@example.com").andExpect(status().isConflict());
        assertThat(countAccounts("second_user")).isZero();
        assertThat(db.sql("SELECT count(*) FROM identity.profiles").query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM identity.password_credentials").query(Long.class).single())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            username too short       | ab                                | a@example.com | S3cret-Pa55word!
            username has a space     | bad name                          | a@example.com | S3cret-Pa55word!
            username has a symbol    | bad@name                          | a@example.com | S3cret-Pa55word!
            username too long        | aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | a@example.com | S3cret-Pa55word!
            email malformed          | valid_name                        | not-an-email  | S3cret-Pa55word!
            password too short       | valid_name                        | a@example.com | short
            password is 9 chars      | valid_name                        | a@example.com | 123456789
            password equals username | long_username_1                   | a@example.com | long_username_1
            """)
    void invalidRegistrationsAreRejectedWithAProblemDocument(String label, String username, String email,
            String password) throws Exception {
        register(username.strip(), email.strip(), password.strip(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:validation-failed"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        assertThat(db.sql("SELECT count(*) FROM identity.accounts").query(Long.class).single()).isZero();
    }

    @Test
    void anOverLongPasswordIsRejectedBeforeItCanBurnCpuInTheHash() throws Exception {
        clearInvocations(passwordEncoder);
        register("long_pw_user", "long@example.com", "a".repeat(129), null).andExpect(status().isBadRequest());
        org.mockito.Mockito.verify(passwordEncoder, org.mockito.Mockito.never()).encode(anyString());
    }

    @Test
    void aRetriedRegistrationWithTheSameKeyReplaysTheOriginal() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = register("retry_user", "retry@example.com", PASSWORD, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = register("retry_user", "retry@example.com", PASSWORD, key).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(countAccounts("retry_user")).isEqualTo(1);
        // the same key with a different password is a different request
        register("retry_user", "retry@example.com", "Another-Pa55word!", key)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void tenSimultaneousRegistrationsForOneUsernameCreateExactlyOneAccount() throws Exception {
        int clients = 10;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            String email = "race" + i + "@example.com";
            results.add(pool.submit(() -> {
                go.await();
                return register("race_user", email).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var f : results) {
            statuses.add(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(clients - 1);
        assertThat(countAccounts("race_user")).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM identity.login_identifiers").query(Long.class).single())
                .as("no orphaned email rows from the losers").isEqualTo(1);
    }

    // ---- login ----

    @Test
    void loginByUsernameOrEmailReturnsABearerToken() throws Exception {
        register("login_user", "Login.User@Example.com").andExpect(status().isCreated());
        for (String identifier : List.of("login_user", "LOGIN_USER", "login.user@example.com", "LOGIN.USER@EXAMPLE.COM")) {
            login(identifier, PASSWORD).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
        }
    }

    @Test
    void theTokenCarriesTheDocumentedClaimsAndEveryLoginGetsAFreshTokenId() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(register("claims_user", "claims@example.com")
                .andReturn().getResponse().getContentAsString(), "$.id"));
        String t1 = JsonPath.read(login("claims_user", PASSWORD).andReturn().getResponse().getContentAsString(), "$.accessToken");
        String t2 = JsonPath.read(login("claims_user", PASSWORD).andReturn().getResponse().getContentAsString(), "$.accessToken");
        Jwt a = jwtDecoder.decode(t1);
        Jwt b = jwtDecoder.decode(t2);

        assertThat(a.getSubject()).isEqualTo(id.toString());
        assertThat(a.getClaimAsString("iss")).isEqualTo("protik");
        assertThat(a.getExpiresAt().getEpochSecond() - a.getIssuedAt().getEpochSecond()).isEqualTo(900);
        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getClaims()).doesNotContainKeys("password", "email", "username");
    }

    @Test
    void wrongPasswordAndUnknownAccountAreIndistinguishable() throws Exception {
        register("exists_user", "exists@example.com").andExpect(status().isCreated());
        String wrong = login("exists_user", "Wrong-Pa55word!").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:invalid-credentials"))
                .andReturn().getResponse().getContentAsString();
        String unknown = login("nobody_here", "Wrong-Pa55word!").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutCorrelationId(unknown)).isEqualTo(withoutCorrelationId(wrong));
    }

    @Test
    void anUnknownAccountStillCostsAPasswordVerification() throws Exception {
        clearInvocations(passwordEncoder);
        login("ghost_account", "Whatever-Pa55word!").andExpect(status().isUnauthorized());
        // without the dummy verification, the missing account would answer measurably faster
        verify(passwordEncoder, atLeastOnce()).matches(eq("Whatever-Pa55word!"), anyString());
    }

    @Test
    void suspendedAndDeletionPendingAccountsCannotLogIn() throws Exception {
        register("suspended_user", "susp@example.com").andExpect(status().isCreated());
        for (String status : List.of("SUSPENDED", "DELETION_PENDING")) {
            db.sql("UPDATE identity.accounts SET status = :s").param("s", status).update();
            login("suspended_user", PASSWORD).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("urn:protik:problem:invalid-credentials"));
        }
    }

    @Test
    void loginRequestsAreValidated() throws Exception {
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordsNeverAppearInLogs(CapturedOutput output) throws Exception {
        register("log_user", "log@example.com", "Logged-Pa55word-123", null).andExpect(status().isCreated());
        login("log_user", "Logged-Wrong-Pa55word").andExpect(status().isUnauthorized());
        register("log_user", "log2@example.com", "Logged-Pa55word-123", null).andExpect(status().isConflict());
        register("x", "bad", "Logged-Pa55word-123", null).andExpect(status().isBadRequest());
        assertThat(output.getAll()).doesNotContain("Logged-Pa55word-123").doesNotContain("Logged-Wrong-Pa55word");
    }

    // ---- protected endpoints ----

    @Test
    void aValidTokenOpensTheProfile() throws Exception {
        String token = tokenFor("profile_user", "profile@example.com");
        me("Bearer " + token).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("profile_user"))
                .andExpect(jsonPath("$.displayName").value("profile_user"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void noTokenIsAProblemDocumentWithAChallenge() throws Exception {
        me(null).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.type").value("urn:protik:problem:unauthenticated"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void malformedAndNonBearerCredentialsAreRejected() throws Exception {
        me("Bearer garbage").andExpect(status().isUnauthorized());
        me("Bearer ").andExpect(status().isUnauthorized());
        me("Basic dXNlcjpwYXNz").andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(register("expired_user", "exp@example.com")
                .andReturn().getResponse().getContentAsString(), "$.id"));
        // an otherwise valid token, signed with the app key, that expired an hour ago
        Instant past = Instant.now().minusSeconds(3600);
        String expired = signedWith(appKey(), "protik", id, past.minusSeconds(900), past);
        me("Bearer " + expired).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String forged = signedWith(new SecretKeySpec("0123456789-0123456789-0123456789!".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"), "protik", id, now, now.plusSeconds(900));
        me("Bearer " + forged).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String foreign = signedWith(appKey(), "someone-else", id, now, now.plusSeconds(900));
        me("Bearer " + foreign).andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedPayloadIsRejected() throws Exception {
        String token = tokenFor("tamper_user", "tamper@example.com");
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String evil = payload.replaceFirst("\"sub\":\"[^\"]+\"", "\"sub\":\"" + UUID.randomUUID() + "\"");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(evil.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        me("Bearer " + forged).andExpect(status().isUnauthorized());
    }

    @Test
    void algNoneTokensAreRejected() throws Exception {
        var enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"protik\",\"sub\":\"" + UUID.randomUUID()
                + "\",\"exp\":" + (Instant.now().getEpochSecond() + 900) + "}").getBytes(StandardCharsets.UTF_8));
        me("Bearer " + header + "." + payload + ".").andExpect(status().isUnauthorized());
        me("Bearer " + header + "." + payload).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenForAnAccountThatNoLongerExistsIsNotFound() throws Exception {
        String token = tokenService.issue(UUID.randomUUID()).value();
        me("Bearer " + token).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:not-found"));
    }

    @Test
    void unknownProtectedPathsDoNotRevealTheyAreUnknownToAnonymousCallers() throws Exception {
        mvc.perform(get("/v1/secret-admin-panel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/anything")).andExpect(status().isUnauthorized());
    }

    // ---- CORS ----

    @Test
    void corsAllowsTheConfiguredFrontendOriginOnly() throws Exception {
        mvc.perform(options("/v1/auth/login").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/v1/auth/login").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    // ---- token helpers ----

    private javax.crypto.SecretKey appKey;

    @Autowired
    void setKey(javax.crypto.SecretKey jwtSigningKey) {
        this.appKey = jwtSigningKey;
    }

    private javax.crypto.SecretKey appKey() {
        return appKey;
    }

    private static String signedWith(javax.crypto.SecretKey key, String issuer, UUID subject, Instant issuedAt,
            Instant expiresAt) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(subject.toString())
                .issuedAt(issuedAt).expiresAt(expiresAt).id(UUID.randomUUID().toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static String withoutCorrelationId(String problemJson) {
        return problemJson.replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }
}
