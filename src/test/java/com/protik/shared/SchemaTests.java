package com.protik.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.protik.TestcontainersConfiguration;
import com.protik.shared.migration.ModuleMigrations;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Validates the migrated schema against a real PostgreSQL: constraints reject
 * bad data, structural rules hold (ADR 0005). Every test runs in a transaction
 * that is rolled back, so tests are independent.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaTests {

    private static final String VIDEO = "'10000000-0000-0000-0000-000000000001'";
    private static final String CAPTION = "'20000000-0000-0000-0000-000000000001'";
    private static final String USER = "'00000000-0000-0000-0000-000000000001'";

    @Autowired
    JdbcClient db;
    @Autowired
    PlatformTransactionManager txManager;

    /** Runs setup statements, then asserts the final statement fails with the given SQLSTATE. */
    private void assertFails(String sqlState, String statement, String... setup) {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            for (String s : setup) {
                db.sql(s).update();
            }
            try {
                db.sql(statement).update();
            } catch (DataAccessException e) {
                Throwable cause = e.getMostSpecificCause();
                String actual = cause instanceof SQLException sql ? sql.getSQLState() : "n/a";
                assertThat(actual).as("SQLSTATE for: " + statement).isEqualTo(sqlState);
                return;
            }
            fail("Expected failure (" + sqlState + ") but statement succeeded: " + statement);
        });
    }

    private <T> T inRolledBackTx(java.util.function.Function<JdbcClient, T> work) {
        return new TransactionTemplate(txManager).execute(tx -> {
            tx.setRollbackOnly();
            return work.apply(db);
        });
    }

    private static String publishedVideo() {
        return "INSERT INTO video.videos (id, creator_id, status, published_at, duration_ms) VALUES (" + VIDEO
                + ", gen_random_uuid(), 'PUBLISHED', now(), 15000)";
    }

    private static String asset(String kind, String rendition, String key) {
        return "INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes) VALUES "
                + "(gen_random_uuid(), " + VIDEO + ", '" + kind + "', " + (rendition == null ? "NULL" : "'" + rendition + "'")
                + ", '" + key + "', 100)";
    }

    // ---- migrations ----

    @Test
    void everyModuleHasItsOwnSuccessfulMigrationHistory() {
        for (String module : ModuleMigrations.MODULES) {
            long failed = db.sql("SELECT count(*) FROM " + module + ".flyway_schema_history WHERE success = false")
                    .query(Long.class).single();
            long applied = db.sql("SELECT count(*) FROM " + module + ".flyway_schema_history WHERE success")
                    .query(Long.class).single();
            assertThat(failed).as(module + " failed migrations").isZero();
            assertThat(applied).as(module + " applied migrations").isPositive();
        }
    }

    @Test
    void referenceDataIsSeeded() {
        assertThat(db.sql("SELECT code FROM video.languages ORDER BY code").query(String.class).list())
                .containsExactly("en", "ha", "ig", "pcm", "yo");
        assertThat(db.sql("SELECT count(*) FROM video.renditions WHERE code IN ('144p','audio')")
                .query(Long.class).single()).isEqualTo(2);
        // ASR is not claimed to work for any language until WER is measured (ADR 0004)
        assertThat(db.sql("SELECT count(*) FROM video.languages WHERE asr_supported").query(Long.class).single())
                .isZero();
    }

    // ---- structural invariants over the whole catalog ----

    @Test
    void noForeignKeyCrossesAModuleBoundary() {
        List<String> crossing = db.sql("""
                SELECT c.conname FROM pg_constraint c
                JOIN pg_class a ON a.oid = c.conrelid
                JOIN pg_class b ON b.oid = c.confrelid
                WHERE c.contype = 'f' AND a.relnamespace <> b.relnamespace
                """).query(String.class).list();
        assertThat(crossing).isEmpty();
    }

    @Test
    void everyForeignKeyHasASupportingIndex() {
        List<String> unindexed = db.sql("""
                SELECT c.conrelid::regclass || '.' || c.conname FROM pg_constraint c
                WHERE c.contype = 'f' AND NOT EXISTS (
                  SELECT 1 FROM pg_index i
                  WHERE i.indrelid = c.conrelid AND (i.indkey::int2[])[0] = c.conkey[1])
                """).query(String.class).list();
        assertThat(unindexed).isEmpty();
    }

    @Test
    void everyTableHasAPrimaryKey() {
        List<String> without = db.sql("""
                SELECT n.nspname || '.' || t.relname FROM pg_class t
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE t.relkind IN ('r','p') AND t.relispartition = false
                  AND n.nspname IN ('identity','video','feed','social','engagement','moderation','shared')
                  AND t.relname <> 'flyway_schema_history'
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = t.oid AND k.contype = 'p')
                """).query(String.class).list();
        assertThat(without).isEmpty();
    }

    @Test
    void everyRankingModeDefinesEveryComponent() {
        long missing = db.sql("""
                SELECT count(*) FROM feed.ranking_modes m CROSS JOIN feed.ranking_components c
                WHERE NOT EXISTS (SELECT 1 FROM feed.ranking_mode_weights w
                                  WHERE w.mode_code = m.code AND w.component_code = c.code)
                """).query(Long.class).single();
        assertThat(missing).isZero();
    }

    // ---- identity ----

    @Test
    void usernamesAreUniqueIgnoringCaseAndWellFormed() {
        assertFails("23505", "INSERT INTO identity.accounts (id, username) VALUES (gen_random_uuid(), 'ADA_01')",
                "INSERT INTO identity.accounts (id, username) VALUES (gen_random_uuid(), 'ada_01')");
        assertFails("23514", "INSERT INTO identity.accounts (id, username) VALUES (gen_random_uuid(), 'a b')");
    }

    @Test
    void aRevokedSessionFamilyMustSayWhy() {
        assertFails("23514", "INSERT INTO identity.session_families (id, account_id, revoked_at) "
                        + "VALUES (gen_random_uuid(), " + USER + ", now())",
                "INSERT INTO identity.accounts (id, username) VALUES (" + USER + ", 'ada')");
    }

    // ---- video ----

    @Test
    void publishedVideoNeedsPublishTimeAndDuration() {
        assertFails("23514", "INSERT INTO video.videos (id, creator_id, status) "
                + "VALUES (gen_random_uuid(), gen_random_uuid(), 'PUBLISHED')");
    }

    @Test
    void thereIsNoSoftDeletedVideoState() {
        assertFails("23514", "INSERT INTO video.videos (id, creator_id, status) "
                + "VALUES (gen_random_uuid(), gen_random_uuid(), 'DELETED')");
    }

    @Test
    void unknownSpokenLanguageIsRejected() {
        assertFails("23503", "INSERT INTO video.videos (id, creator_id, status, spoken_language) "
                + "VALUES (gen_random_uuid(), gen_random_uuid(), 'UPLOADING', 'zz')");
    }

    @Test
    void aTranscodeRerunCannotDuplicateAssets() {
        // NULL rendition included: UNIQUE NULLS NOT DISTINCT
        assertFails("23505", asset("ORIGINAL", null, "k/orig2"),
                publishedVideo(), asset("ORIGINAL", null, "k/orig"));
        assertFails("23505", asset("HLS_VARIANT", "144p", "k/144b"),
                publishedVideo(), asset("HLS_VARIANT", "144p", "k/144"));
    }

    @Test
    void onlyVariantsCarryARendition() {
        assertFails("23514", asset("HLS_VARIANT", null, "k/x"), publishedVideo());
        assertFails("23514", asset("THUMBNAIL", "144p", "k/t"), publishedVideo());
    }

    @Test
    void deletingAVideoTombstonesEveryStorageKey() {
        long tombstones = inRolledBackTx(c -> {
            c.sql(publishedVideo()).update();
            c.sql(asset("ORIGINAL", null, "k/orig")).update();
            c.sql(asset("HLS_VARIANT", "144p", "k/144")).update();
            c.sql(asset("HLS_VARIANT", "audio", "k/audio")).update();
            c.sql("DELETE FROM video.videos WHERE id = " + VIDEO).update(); // cascades to assets
            return c.sql("SELECT count(*) FROM video.storage_tombstones").query(Long.class).single();
        });
        assertThat(tombstones).as("no object left behind in storage").isEqualTo(3);
    }

    @Test
    void captionRules() {
        String video = "INSERT INTO video.videos (id, creator_id, status) VALUES (" + VIDEO
                + ", gen_random_uuid(), 'PROCESSING')";
        String caption = "INSERT INTO video.captions (id, video_id, language_code, source, status, model) VALUES ("
                + CAPTION + ", " + VIDEO + ", 'yo', 'ASR', 'PENDING', 'm')";
        // ASR captions must record the model
        assertFails("23514", "INSERT INTO video.captions (id, video_id, language_code, source, status) VALUES "
                + "(gen_random_uuid(), " + VIDEO + ", 'ig', 'ASR', 'PENDING')", video);
        // READY needs the rendered file
        assertFails("23514", "UPDATE video.captions SET status = 'READY'", video, caption);
        // one caption per (video, language, source)
        assertFails("23505", "INSERT INTO video.captions (id, video_id, language_code, source, status, model) VALUES "
                + "(gen_random_uuid(), " + VIDEO + ", 'yo', 'ASR', 'PENDING', 'm2')", video, caption);
        // a cue must end after it starts
        assertFails("23514", "INSERT INTO video.caption_segments (caption_id, seq, start_ms, end_ms, text) "
                + "VALUES (" + CAPTION + ", 0, 500, 500, 'x')", video, caption);
    }

    @Test
    void transcriptSearchIgnoresToneMarksAndDots() {
        boolean[] found = inRolledBackTx(c -> {
            c.sql("INSERT INTO video.videos (id, creator_id, status) VALUES (" + VIDEO
                    + ", gen_random_uuid(), 'PROCESSING')").update();
            c.sql("INSERT INTO video.captions (id, video_id, language_code, source, status, model) VALUES ("
                    + CAPTION + ", " + VIDEO + ", 'yo', 'ASR', 'PENDING', 'm')").update();
            // "Ọ̀rọ̀ dára how far na": o-dot-below + grave accents, and an a-acute
            c.sql("INSERT INTO video.caption_segments (caption_id, seq, start_ms, end_ms, text) VALUES ("
                    + CAPTION + ", 0, 0, 2000, U&'\\1ECC\\0300r\\1ECD\\0300 d\\00E1ra how far na')").update();
            boolean oro = c.sql("SELECT count(*) FROM video.caption_segments WHERE text_tsv @@ "
                    + "plainto_tsquery('simple', shared.immutable_unaccent('oro'))").query(Long.class).single() == 1;
            boolean dara = c.sql("SELECT count(*) FROM video.caption_segments WHERE text_tsv @@ "
                    + "plainto_tsquery('simple', 'dara')").query(Long.class).single() == 1;
            boolean fuzzy = c.sql("SELECT count(*) FROM video.caption_segments WHERE "
                    + "lower(shared.immutable_unaccent(text)) % 'oro dara how far'").query(Long.class).single() == 1;
            return new boolean[] {oro, dara, fuzzy};
        });
        assertThat(found[0]).as("plain 'oro' finds the accented word").isTrue();
        assertThat(found[1]).as("plain 'dara' finds 'dara' with an acute").isTrue();
        assertThat(found[2]).as("trigram match tolerates a different spelling").isTrue();
    }

    // ---- feed / social / engagement ----

    @Test
    void feedSettingsAreValidated() {
        assertFails("23503", "INSERT INTO feed.user_feed_settings (user_id, ranking_mode) "
                + "VALUES (gen_random_uuid(), 'MADE_UP')");
        assertFails("23514", "INSERT INTO feed.user_ranking_weights (user_id, component_code, weight) "
                        + "VALUES (" + USER + ", 'RECENCY', 1.5)",
                "INSERT INTO feed.user_feed_settings (user_id) VALUES (" + USER + ")");
        assertFails("23514", "UPDATE feed.user_feed_settings SET monthly_data_cap_mb = 0",
                "INSERT INTO feed.user_feed_settings (user_id) VALUES (" + USER + ")");
    }

    @Test
    void usageEventsLandInAPartition() {
        long rows = inRolledBackTx(c -> {
            c.sql("INSERT INTO feed.usage_events (id, user_id, video_id, rendition_code, bytes, occurred_at) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '144p', 123456, now())").update();
            return c.sql("SELECT count(*) FROM feed.usage_events_default").query(Long.class).single();
        });
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void socialAndEngagementInvariants() {
        assertFails("23514", "INSERT INTO social.follows (follower_id, followee_id) VALUES (" + USER + ", " + USER + ")");
        assertFails("23514", "INSERT INTO social.comments (id, video_id, author_id, body) "
                + "VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '')");
        // PK makes a double like impossible by construction
        assertFails("23505", "INSERT INTO engagement.video_likes (user_id, video_id) VALUES (" + USER + ", " + VIDEO + ")",
                "INSERT INTO engagement.video_likes (user_id, video_id) VALUES (" + USER + ", " + VIDEO + ")");
        assertFails("23514", "INSERT INTO engagement.video_counters (video_id, likes) VALUES (gen_random_uuid(), -1)");
    }

    // ---- moderation ----

    @Test
    void onlyOneOpenReportPerReporterPerTarget() {
        String report = "INSERT INTO moderation.reports (id, reporter_id, target_type, target_id, reason_code) "
                + "VALUES (gen_random_uuid(), " + USER + ", 'VIDEO', " + VIDEO + ", 'SPAM')";
        assertFails("23505", report, report);
    }

    @Test
    void auditLogIsAppendOnlyAndNeedsAReason() {
        String action = "INSERT INTO moderation.actions (id, actor_id, action_type, target_type, target_id, reason) "
                + "VALUES ('40000000-0000-0000-0000-000000000001', gen_random_uuid(), 'TAKEDOWN', 'VIDEO', " + VIDEO
                + ", 'spam')";
        assertFails("23514", "INSERT INTO moderation.actions (id, actor_id, action_type, target_type, target_id, reason) "
                + "VALUES (gen_random_uuid(), gen_random_uuid(), 'TAKEDOWN', 'VIDEO', gen_random_uuid(), '   ')");
        assertFails("P0001", "UPDATE moderation.actions SET reason = 'edited'", action);
        assertFails("P0001", "DELETE FROM moderation.actions", action);
        assertFails("P0001", "TRUNCATE moderation.actions", action);
    }

    // ---- shared ----

    @Test
    void jobsAndIdempotencyKeys() {
        assertFails("23514", "INSERT INTO shared.jobs (id, type, status) VALUES (gen_random_uuid(), 't', 'RUNNING')");
        String key = "INSERT INTO shared.idempotency_keys (user_id, key, endpoint, request_hash, expires_at) VALUES ("
                + USER + ", 'k1', 'POST /v1/videos', '\\xaa', now() + interval '1 day')";
        assertFails("23505", key, key);
    }

    @Test
    void claimQueryMovesExactlyOneJobToRunningWithALease() {
        long running = inRolledBackTx(c -> {
            c.sql("INSERT INTO shared.jobs (id, type) VALUES (gen_random_uuid(), 'TRANSCODE')").update();
            c.sql("""
                    WITH c AS (SELECT id FROM shared.jobs WHERE status = 'QUEUED' AND run_at <= now()
                               ORDER BY run_at FOR UPDATE SKIP LOCKED LIMIT 1)
                    UPDATE shared.jobs j SET status = 'RUNNING', lease_expires_at = now() + interval '5 min',
                           attempts = attempts + 1
                    FROM c WHERE j.id = c.id
                    """).update();
            return c.sql("SELECT count(*) FROM shared.jobs WHERE status = 'RUNNING' AND attempts = 1 "
                    + "AND lease_expires_at IS NOT NULL").query(Long.class).single();
        });
        assertThat(running).isEqualTo(1);
    }
}
