package com.protik.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.TokenService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADR 0020: deleting an account removes the person's data from every module, and a test fails if a new table that
 * can hold a user ID is added without being accounted for.
 */
@SpringBootTest(properties = {"protik.engagement.refresh-interval=PT1H", "protik.engagement.reconcile-interval=PT1H"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountDeletionIntegrationTests {

    static final String PASSWORD = "correct horse battery staple 42";

    /** Every column that can hold a user's ID, as {@code schema.table.column}. A new one must be added here on purpose. */
    static final Set<String> USER_COLUMNS = new TreeSet<>(Set.of(
            "identity.login_identifiers.account_id", "identity.password_credentials.account_id",
            "identity.profiles.account_id", "identity.session_families.account_id", "identity.account_roles.account_id",
            "identity.deletion_requests.account_id",
            "video.videos.creator_id", "video.upload_sessions.creator_id",
            "feed.user_feed_settings.user_id", "feed.user_ranking_weights.user_id", "feed.usage_events.user_id",
            "feed.seen_videos.user_id", "feed.candidates.creator_id", "feed.following.user_id", "feed.following.creator_id",
            "feed.user_interests.user_id", "feed.user_likes.user_id",
            "social.follows.follower_id", "social.follows.followee_id", "social.blocks.blocker_id",
            "social.blocks.blocked_id", "social.comments.author_id",
            "engagement.video_likes.user_id", "engagement.view_events.user_id",
            "moderation.reports.reporter_id",
            "shared.idempotency_keys.user_id"));

    /** Kept on purpose after deletion: the request that records it was completed (a pseudonymous ID, no personal data). */
    static final Set<String> KEPT = Set.of("identity.deletion_requests.account_id");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;

    private UUID user;
    private UUID other;
    private UUID myVideo;
    private UUID otherVideo;

    @BeforeEach
    void setUp() {
        db.sql("DELETE FROM shared.rate_limits").update();
        user = account("doomed");
        other = account("stays");
        myVideo = video(user);
        otherVideo = video(other);
    }

    // ---- helpers ----

    private UUID account(String label) {
        UUID id = UUID.randomUUID();
        String name = "del_" + label + "_" + id.toString().substring(0, 8);
        db.sql("INSERT INTO identity.accounts (id, username) VALUES (:id, :u)").param("id", id).param("u", name).update();
        db.sql("INSERT INTO identity.login_identifiers (id, account_id, kind, value) VALUES (gen_random_uuid(), :id, 'EMAIL', :e)")
                .param("id", id).param("e", name + "@example.test").update();
        db.sql("INSERT INTO identity.profiles (account_id, display_name) VALUES (:id, :n)").param("id", id).param("n", label).update();
        return id;
    }

    private void givePassword(UUID id, String password) {
        // Registered through the real encoder, so the deletion endpoint verifies a genuine Argon2 hash.
        String hash = new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 1 << 12, 1).encode(password);
        db.sql("INSERT INTO identity.password_credentials (account_id, password_hash) VALUES (:id, :h)")
                .param("id", id).param("h", hash).update();
    }

    private UUID video(UUID owner) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO video.videos (id, creator_id, description, status, published_at, duration_ms) "
                + "VALUES (:id, :c, 'deletion-test', 'PUBLISHED', now(), 9000)").param("id", id).param("c", owner).update();
        db.sql("INSERT INTO feed.candidates (video_id, creator_id, language, published_at, duration_ms) "
                + "VALUES (:id, :c, 'en', now(), 9000)").param("id", id).param("c", owner).update();
        return id;
    }

    /** Gives the user at least one row in every table that can hold their ID. */
    private void seedEverything() {
        givePassword(user, PASSWORD);
        db.sql("INSERT INTO identity.account_roles (account_id, role) VALUES (:u, 'MODERATOR')").param("u", user).update();
        db.sql("INSERT INTO identity.session_families (id, account_id) VALUES (gen_random_uuid(), :u)").param("u", user).update();
        db.sql("INSERT INTO video.upload_sessions (id, creator_id, video_id, storage_key, multipart_upload_id, content_type, "
                + "total_bytes, status, expires_at) VALUES (gen_random_uuid(), :u, :v, :k, 'x', 'video/mp4', 10, 'OPEN', now() + interval '1 day')")
                .param("u", user).param("v", myVideo).param("k", "k-" + UUID.randomUUID()).update();
        db.sql("INSERT INTO feed.user_feed_settings (user_id) VALUES (:u)").param("u", user).update();
        db.sql("INSERT INTO feed.user_ranking_weights (user_id, component_code, weight) VALUES (:u, 'RECENCY', 0.5)")
                .param("u", user).update();
        db.sql("INSERT INTO feed.usage_events (id, user_id, video_id, rendition_code, bytes, occurred_at) "
                + "VALUES (gen_random_uuid(), :u, :v, '144p', 1000, now())").param("u", user).param("v", otherVideo).update();
        db.sql("INSERT INTO feed.seen_videos (user_id, video_id) VALUES (:u, :v)").param("u", user).param("v", otherVideo).update();
        db.sql("INSERT INTO feed.following (user_id, creator_id) VALUES (:u, :o)").param("u", user).param("o", other).update();
        db.sql("INSERT INTO feed.following (user_id, creator_id) VALUES (:o, :u)").param("u", user).param("o", other).update();
        db.sql("INSERT INTO feed.user_interests (user_id, tag) VALUES (:u, 'comedy')").param("u", user).update();
        db.sql("INSERT INTO feed.user_likes (user_id, video_id) VALUES (:u, :v)").param("u", user).param("v", otherVideo).update();
        db.sql("INSERT INTO social.follows (follower_id, followee_id) VALUES (:u, :o)").param("u", user).param("o", other).update();
        db.sql("INSERT INTO social.follows (follower_id, followee_id) VALUES (:o, :u)").param("u", user).param("o", other).update();
        db.sql("INSERT INTO social.blocks (blocker_id, blocked_id) VALUES (:u, :o)").param("u", user).param("o", other).update();
        db.sql("INSERT INTO social.blocks (blocker_id, blocked_id) VALUES (:o, :u)").param("u", user).param("o", other).update();
        UUID mine = UUID.randomUUID();
        db.sql("INSERT INTO social.comments (id, video_id, author_id, body) VALUES (:id, :v, :u, 'hello')")
                .param("id", mine).param("v", otherVideo).param("u", user).update();
        db.sql("INSERT INTO social.comments (id, video_id, author_id, parent_id, body) VALUES (gen_random_uuid(), :v, :o, :p, 'reply')")
                .param("v", otherVideo).param("o", other).param("p", mine).update();
        db.sql("INSERT INTO engagement.video_likes (user_id, video_id) VALUES (:u, :v)").param("u", user).param("v", otherVideo).update();
        db.sql("INSERT INTO engagement.view_events (id, video_id, user_id, watched_ms, occurred_at) "
                + "VALUES (gen_random_uuid(), :v, :u, 1000, now())").param("u", user).param("v", otherVideo).update();
        db.sql("INSERT INTO moderation.reports (id, reporter_id, target_type, target_id, reason_code, details) "
                + "VALUES (gen_random_uuid(), :u, 'VIDEO', :v, 'SPAM', 'personal words')").param("u", user).param("v", otherVideo).update();
        db.sql("INSERT INTO shared.idempotency_keys (user_id, key, endpoint, request_hash, expires_at) "
                + "VALUES (:u, 'k1', 'POST /x', decode('00', 'hex'), now() + interval '1 day')").param("u", user).update();
    }

    private long rowsFor(String column, UUID id) {
        String[] part = column.split("\\.");
        return db.sql("SELECT count(*) FROM " + part[0] + "." + part[1] + " WHERE " + part[2] + " = :id")
                .param("id", id).query(Long.class).single();
    }

    private String bearer(UUID id) {
        return "Bearer " + tokens.issue(id, UUID.randomUUID()).value();
    }

    private ResultActions requestDeletion(UUID id, String password) throws Exception {
        return mvc.perform(post("/v1/me/deletion").header("Authorization", bearer(id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}"));
    }

    private boolean accountExists(UUID id) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM identity.accounts WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    // ---- the guard against forgetting a table ----

    @Test
    void everyColumnThatCanHoldAUserIdIsAccountedFor() {
        List<String> found = db.sql("""
                SELECT c.table_schema || '.' || c.table_name || '.' || c.column_name
                FROM information_schema.columns c
                JOIN pg_class t ON t.relname = c.table_name
                JOIN pg_namespace n ON n.oid = t.relnamespace AND n.nspname = c.table_schema
                WHERE c.table_schema IN ('identity','video','feed','social','engagement','moderation','shared')
                  AND c.column_name IN ('user_id','account_id','creator_id','author_id','follower_id','followee_id',
                                        'blocker_id','blocked_id','reporter_id')
                  AND t.relkind IN ('r','p') AND NOT t.relispartition
                ORDER BY 1
                """).query(String.class).list();
        assertThat(new TreeSet<>(found))
                .as("A table with a user-ID column was added or removed. Teach account deletion about it (ADR 0020), "
                        + "then update USER_COLUMNS.")
                .isEqualTo(USER_COLUMNS);
    }

    @Test
    void theSeedTouchesEveryColumnSoTheCompletenessCheckIsNotVacuous() {
        seedEverything();
        List<String> empty = new ArrayList<>();
        for (String column : USER_COLUMNS) {
            if (rowsFor(column, user) == 0) {
                empty.add(column);
            }
        }
        // deletion_requests is filled only by a real request.
        empty.remove("identity.deletion_requests.account_id");
        assertThat(empty).as("columns the seed does not fill").isEmpty();
    }

    // ---- the real thing ----

    @Test
    void deletingAnAccountRemovesThePersonFromEveryModuleAndLeavesOthersAlone() throws Exception {
        seedEverything();
        UUID reportId = db.sql("SELECT id FROM moderation.reports WHERE reporter_id = :u").param("u", user)
                .query(UUID.class).single();

        requestDeletion(user, PASSWORD).andExpect(status().isAccepted()).andExpect(jsonPath("$.requestId").exists());

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(accountExists(user)).isFalse());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (String column : USER_COLUMNS) {
                if (!KEPT.contains(column)) {
                    assertThat(rowsFor(column, user)).as(column).isZero();
                }
            }
        });
        // The report survives without the person or their words.
        assertThat(db.sql("SELECT details FROM moderation.reports WHERE id = :id").param("id", reportId).query(String.class)
                .single()).isEmpty();
        assertThat(db.sql("SELECT completed_at IS NOT NULL FROM identity.deletion_requests WHERE account_id = :u")
                .param("u", user).query(Boolean.class).single()).isTrue();
        // Other people, their video and their reply are untouched.
        assertThat(accountExists(other)).isTrue();
        assertThat(rowsFor("video.videos.creator_id", other)).isEqualTo(1);
        assertThat(rowsFor("social.comments.author_id", other)).isEqualTo(0); // the reply went with the parent comment
        assertThat(db.sql("SELECT status FROM video.videos WHERE id = :id").param("id", otherVideo).query(String.class).single())
                .isEqualTo("PUBLISHED");
    }

    @Test
    void theAccountStopsWorkingAtOnce() throws Exception {
        seedEverything();
        requestDeletion(user, PASSWORD).andExpect(status().isAccepted());
        // Whether the modules have finished or not, no session of this account may be usable.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(accountExists(user)).isFalse());
        assertThat(rowsFor("identity.session_families.account_id", user)).isZero();
    }

    @Test
    void aWrongPasswordDeletesNothing() throws Exception {
        seedEverything();
        requestDeletion(user, "not the password").andExpect(status().isUnauthorized());
        assertThat(accountExists(user)).isTrue();
        assertThat(db.sql("SELECT status FROM identity.accounts WHERE id = :u").param("u", user).query(String.class).single())
                .isEqualTo("ACTIVE");
        assertThat(rowsFor("identity.deletion_requests.account_id", user)).isZero();
    }

    @Test
    void deletionNeedsASignedInUserAndAPassword() throws Exception {
        mvc.perform(post("/v1/me/deletion").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        givePassword(user, PASSWORD);
        mvc.perform(post("/v1/me/deletion").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        assertThat(accountExists(user)).isTrue();
    }

    @Test
    void guessingThePasswordIsLimitedToFiveTriesAnHour() throws Exception {
        givePassword(user, PASSWORD);
        for (int i = 0; i < 5; i++) {
            requestDeletion(user, "wrong " + i).andExpect(status().isUnauthorized());
        }
        requestDeletion(user, PASSWORD).andExpect(status().isTooManyRequests());
        assertThat(accountExists(user)).isTrue();
    }

    @Test
    void askingTwiceReturnsTheSameRequest() throws Exception {
        givePassword(user, PASSWORD);
        // A pending request that no module has acknowledged yet.
        UUID request = UUID.randomUUID();
        db.sql("UPDATE identity.accounts SET status = 'DELETION_PENDING' WHERE id = :u").param("u", user).update();
        db.sql("INSERT INTO identity.deletion_requests (id, account_id) VALUES (:r, :u)").param("r", request).param("u", user).update();
        String body = requestDeletion(user, PASSWORD).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.requestId")).isEqualTo(request.toString());
        assertThat(rowsFor("identity.deletion_requests.account_id", user)).isEqualTo(1);
    }
}
