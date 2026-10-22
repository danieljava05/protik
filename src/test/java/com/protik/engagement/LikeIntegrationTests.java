package com.protik.engagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import com.protik.engagement.persistence.EngagementRepository;
import com.protik.engagement.service.CounterService;
import com.protik.engagement.service.LikeService;
import com.protik.identity.service.TokenService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR 0016: likes are idempotent and validated; counters always converge on the truth, also under load. */
// The scheduled refresher is slowed to an hour here so that these tests decide when counters are
// recounted; CounterSchedulerTests covers the scheduler itself.
@SpringBootTest(properties = {"protik.engagement.refresh-interval=PT1H", "protik.engagement.reconcile-interval=PT1H"})
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class LikeIntegrationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    LikeService likes;
    @Autowired
    CounterService counters;
    @Autowired
    EngagementRepository repository;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    ApplicationEvents events;

    private final UUID me = UUID.randomUUID();

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM engagement.video_likes").update();
        db.sql("DELETE FROM engagement.video_counters").update();
        db.sql("DELETE FROM engagement.dirty_counters").update();
        db.sql("DELETE FROM video.videos WHERE description = 'like-test'").update();
    }

    // ---- helpers ----

    private UUID video(String status) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO video.videos (id, creator_id, description, status, published_at, duration_ms) "
                + "VALUES (:id, gen_random_uuid(), 'like-test', :status, CASE WHEN :status = 'PUBLISHED' THEN now() END, "
                + "CASE WHEN :status = 'PUBLISHED' THEN 9000 END)")
                .param("id", id).param("status", status).update();
        return id;
    }

    private UUID video() {
        return video("PUBLISHED");
    }

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private ResultActions putLike(UUID video) throws Exception {
        return mvc.perform(put("/v1/videos/" + video + "/like").header("Authorization", bearer(me)));
    }

    private ResultActions deleteLike(UUID video) throws Exception {
        return mvc.perform(delete("/v1/videos/" + video + "/like").header("Authorization", bearer(me)));
    }

    private ResultActions engagement(UUID video) throws Exception {
        return mvc.perform(get("/v1/videos/" + video + "/engagement").header("Authorization", bearer(me)));
    }

    private long likeRows(UUID video) {
        return db.sql("SELECT count(*) FROM engagement.video_likes WHERE video_id = :v").param("v", video)
                .query(Long.class).single();
    }

    private long counter(UUID video) {
        return db.sql("SELECT COALESCE((SELECT likes FROM engagement.video_counters WHERE video_id = :v), 0)")
                .param("v", video).query(Long.class).single();
    }

    // ---- behaviour ----

    @Test
    void likingRecordsItAnnouncesOnceAndShowsAsLikedAtOnce() throws Exception {
        UUID v = video();
        putLike(v).andExpect(status().isNoContent());
        assertThat(likeRows(v)).isEqualTo(1);
        assertThat(events.stream(VideoLiked.class).filter(e -> e.videoId().equals(v)).count()).isEqualTo(1);
        engagement(v).andExpect(status().isOk()).andExpect(jsonPath("$.likedByMe").value(true));
    }

    @Test
    void likingTwiceChangesNothingAndAnnouncesNothingTheSecondTime() throws Exception {
        UUID v = video();
        putLike(v);
        putLike(v).andExpect(status().isNoContent());
        assertThat(likeRows(v)).isEqualTo(1);
        assertThat(events.stream(VideoLiked.class).filter(e -> e.videoId().equals(v)).count()).isEqualTo(1);
    }

    @Test
    void unlikingRemovesItAnnouncesOnceAndIsRepeatable() throws Exception {
        UUID v = video();
        putLike(v);
        deleteLike(v).andExpect(status().isNoContent());
        deleteLike(v).andExpect(status().isNoContent());
        assertThat(likeRows(v)).isZero();
        assertThat(events.stream(VideoUnliked.class).filter(e -> e.videoId().equals(v)).count()).isEqualTo(1);
        engagement(v).andExpect(jsonPath("$.likedByMe").value(false));
    }

    @Test
    void onlyPublishedVideosCanBeLiked() throws Exception {
        putLike(UUID.randomUUID()).andExpect(status().isNotFound());
        putLike(video("UPLOADING")).andExpect(status().isNotFound());
        putLike(video("PROCESSING")).andExpect(status().isNotFound());
        putLike(video("FAILED")).andExpect(status().isNotFound());
        putLike(video("TAKEN_DOWN")).andExpect(status().isNotFound());
        assertThat(db.sql("SELECT count(*) FROM engagement.video_likes").query(Long.class).single()).isZero();
        assertThat(db.sql("SELECT count(*) FROM engagement.dirty_counters").query(Long.class).single()).isZero();
        assertThat(events.stream(VideoLiked.class).count()).isZero();
    }

    @Test
    void aLikeCanBeWithdrawnFromAVideoThatWasTakenDownButNotFromOneThatNeverExisted() throws Exception {
        UUID v = video();
        putLike(v);
        db.sql("UPDATE video.videos SET status = 'TAKEN_DOWN' WHERE id = :id").param("id", v).update();
        deleteLike(v).andExpect(status().isNoContent());
        assertThat(likeRows(v)).isZero();
        deleteLike(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        UUID v = video();
        mvc.perform(put("/v1/videos/" + v + "/like")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/videos/" + v + "/like")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/videos/" + v + "/engagement")).andExpect(status().isUnauthorized());
    }

    @Test
    void engagementOfAnUnknownVideoIsNotFound() throws Exception {
        engagement(UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(get("/v1/videos/nonsense/engagement").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest());
    }

    // ---- counters ----

    @Test
    void aRefreshRecountsAnnouncesAndCleansUpTheDirtyMark() throws Exception {
        UUID v = video();
        UUID other = UUID.randomUUID();
        putLike(v);
        likes.like(other, v);
        counters.refresh();
        assertThat(counter(v)).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) FROM engagement.dirty_counters").query(Long.class).single()).isZero();
        assertThat(events.stream(VideoStatsChanged.class).filter(e -> e.videoId().equals(v)).map(VideoStatsChanged::likes))
                .contains(2L);
        assertThat(counters.refresh()).as("nothing left to do").isZero();
    }

    @Test
    void aCrashDuringRefreshLeavesTheVideoDirtyForTheNextAttempt() throws Exception {
        UUID v = video();
        putLike(v);
        try {
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                assertThat(repository.claimDirty(10)).contains(v);
                throw new IllegalStateException("the process died before the counter was saved");
            });
        } catch (IllegalStateException expected) {
            // the claim was rolled back with everything else
        }
        assertThat(db.sql("SELECT count(*) FROM engagement.dirty_counters WHERE video_id = :v").param("v", v)
                .query(Long.class).single()).isEqualTo(1);
        counters.refresh();
        assertThat(counter(v)).isEqualTo(1);
    }

    @Test
    void twoRefreshersNeverRecountTheSameVideoAtTheSameTime() throws Exception {
        List<UUID> videos = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID v = video();
            videos.add(v);
            likes.like(UUID.randomUUID(), v);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return counters.refresh();
            }));
        }
        go.countDown();
        int total = 0;
        for (var f : results) {
            total += f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(total).as("each dirty video was claimed by exactly one refresher").isEqualTo(20);
        for (UUID v : videos) {
            assertThat(counter(v)).isEqualTo(1);
        }
    }

    @Test
    void counterAccuracyUnderLoad() throws Exception {
        // 40 videos, 300 users hammering likes and unlikes in parallel; the scheduled refresher runs too
        List<UUID> videos = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            videos.add(video());
        }
        int users = 300;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int u = 0; u < users; u++) {
            UUID user = UUID.randomUUID();
            long seed = u;
            futures.add(pool.submit(() -> {
                go.await();
                var random = new Random(seed);
                for (int op = 0; op < 8; op++) {
                    UUID v = videos.get(random.nextInt(8)); // concentrate on 8 hot videos
                    if (random.nextInt(4) == 0) {
                        likes.unlike(user, v);
                    } else {
                        likes.like(user, v);
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (var f : futures) {
            f.get(180, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // settle: let the refresher finish, then every counter must equal the number of like rows
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            counters.refresh();
            return db.sql("SELECT count(*) FROM engagement.dirty_counters").query(Long.class).single() == 0;
        });
        long mismatches = 0;
        long totalLikes = 0;
        for (UUID v : videos) {
            long truth = likeRows(v);
            totalLikes += truth;
            if (truth != counter(v)) {
                mismatches++;
            }
        }
        assertThat(totalLikes).as("the test really produced likes").isGreaterThan(300);
        assertThat(mismatches).as("counters equal the truth for every video").isZero();
    }

    @Test
    void reconciliationRepairsDriftAndTellsTheWorld() throws Exception {
        UUID drifted = video();
        UUID emptied = video();
        UUID missing = video();
        likes.like(UUID.randomUUID(), drifted);
        likes.like(UUID.randomUUID(), drifted);
        likes.like(UUID.randomUUID(), missing);
        counters.refresh();
        events.clear();
        db.sql("UPDATE engagement.video_counters SET likes = 999 WHERE video_id = :v").param("v", drifted).update();
        db.sql("INSERT INTO engagement.video_counters (video_id, likes) VALUES (:v, 7)").param("v", emptied).update();
        db.sql("DELETE FROM engagement.video_counters WHERE video_id = :v").param("v", missing).update();

        assertThat(counters.reconcileAll()).isEqualTo(3);

        assertThat(counter(drifted)).isEqualTo(2);
        assertThat(counter(emptied)).isZero();
        assertThat(counter(missing)).isEqualTo(1);
        assertThat(events.stream(VideoStatsChanged.class).map(VideoStatsChanged::videoId))
                .containsExactlyInAnyOrder(drifted, emptied, missing);
        assertThat(counters.reconcileAll()).as("a second pass finds nothing to fix").isZero();
    }
}
