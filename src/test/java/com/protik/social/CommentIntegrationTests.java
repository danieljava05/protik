package com.protik.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.engagement.service.CounterService;
import com.protik.identity.service.TokenService;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0017: plain-text comments, one level of replies, stable paging, real deletion, bounded posting. */
@SpringBootTest(properties = {"protik.social.comments-per-window=5", "protik.engagement.refresh-interval=PT1H",
        "protik.engagement.reconcile-interval=PT1H"})
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class CommentIntegrationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    CounterService counters;
    @Autowired
    ApplicationEvents events;

    private UUID video;
    private final UUID me = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        db.sql("DELETE FROM social.comments").update();
        db.sql("DELETE FROM engagement.video_counters").update();
        db.sql("DELETE FROM engagement.dirty_counters").update();
        db.sql("DELETE FROM shared.rate_limits").update();
        db.sql("DELETE FROM shared.idempotency_keys").update();
        db.sql("DELETE FROM video.videos WHERE description = 'comment-test'").update();
        video = video("PUBLISHED");
    }

    // ---- helpers ----

    private UUID video(String status) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO video.videos (id, creator_id, description, status, published_at, duration_ms) "
                + "VALUES (:id, gen_random_uuid(), 'comment-test', :s, CASE WHEN :s = 'PUBLISHED' THEN now() END, "
                + "CASE WHEN :s = 'PUBLISHED' THEN 9000 END)").param("id", id).param("s", status).update();
        return id;
    }

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private static String bodyJson(String text, UUID parent) {
        StringBuilder sb = new StringBuilder("{\"body\":\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        if (parent != null) {
            sb.append(",\"parentId\":\"").append(parent).append('"');
        }
        return sb.append('}').toString();
    }

    private ResultActions postComment(UUID user, UUID videoId, String text, UUID parent, String key) throws Exception {
        var request = post("/v1/videos/" + videoId + "/comments").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(bodyJson(text, parent));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private ResultActions postComment(String text) throws Exception {
        return postComment(me, video, text, null, null);
    }

    private UUID postedId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.id"));
    }

    private ResultActions list(UUID videoId, String query) throws Exception {
        return mvc.perform(get("/v1/videos/" + videoId + "/comments" + query).header("Authorization", bearer(me)));
    }

    private ResultActions replies(UUID commentId, String query) throws Exception {
        return mvc.perform(get("/v1/comments/" + commentId + "/replies" + query).header("Authorization", bearer(me)));
    }

    private ResultActions deleteComment(UUID user, UUID commentId) throws Exception {
        return mvc.perform(delete("/v1/comments/" + commentId).header("Authorization", bearer(user)));
    }

    private long rows() {
        return db.sql("SELECT count(*) FROM social.comments").query(Long.class).single();
    }

    private void bulkInsert(int n, boolean sameTimestamp) {
        db.sql("""
                INSERT INTO social.comments (id, video_id, author_id, body, created_at)
                SELECT gen_random_uuid(), :v, gen_random_uuid(), 'bulk ' || g,
                       CASE WHEN :same THEN timestamptz '2026-10-01 12:00:00+00'
                            ELSE timestamptz '2026-10-01 12:00:00+00' + g * interval '1 second' END
                FROM generate_series(1, :n) g
                """).param("v", video).param("n", n).param("same", sameTimestamp).update();
    }

    // ---- writing ----

    @Test
    void aPostedCommentComesBackWithItsFactsAndIsAnnouncedOnce() throws Exception {
        postComment("  Great video!  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Great video!"))          // trimmed
                .andExpect(jsonPath("$.videoId").value(video.toString()))
                .andExpect(jsonPath("$.authorId").value(me.toString()))
                .andExpect(jsonPath("$.parentId").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.id").isNotEmpty());
        assertThat(rows()).isEqualTo(1);
        assertThat(events.stream(CommentCountChanged.class).filter(e -> e.videoId().equals(video)).count()).isEqualTo(1);
    }

    @Test
    void textIsStoredExactlyAsGivenIncludingAfricanLanguagesAndMarkup() throws Exception {
        String yoruba = "Ọ̀rọ̀ yìí dára gan-an! 😀 Ṣé o rí i?";
        String markup = "<script>alert('x')</script> & \"quotes\" \\ backslash";
        for (String text : List.of(yoruba, markup, "line one\nline two\twith tab")) {
            UUID id = postedId(postComment(text));
            assertThat(db.sql("SELECT body FROM social.comments WHERE id = :id").param("id", id).query(String.class).single())
                    .isEqualTo(text);
        }
        // returned byte-for-byte; escaping for display is the client's job
        String listed = list(video, "").andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(listed, "$.items[*].body")).contains(yoruba, markup);
    }

    @Test
    void theContentRulesAreEnforced() throws Exception {
        // a fresh author per attempt: this test is about the content rules, not the posting limit
        for (String bad : List.of("", "   ", "\n\t ", "bell\u0007char", "nul\u0000char", "x".repeat(2201))) {
            postComment(UUID.randomUUID(), video, bad, null, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:protik:problem:validation-failed"));
        }
        postComment(UUID.randomUUID(), video, "x".repeat(2200), null, null).andExpect(status().isCreated());
        // 2,200 emoji are 4,400 UTF-16 units but 2,200 characters people see: allowed; 2,201 are not
        postComment(UUID.randomUUID(), video, "😀".repeat(2200), null, null).andExpect(status().isCreated());
        postComment(UUID.randomUUID(), video, "😀".repeat(2201), null, null).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/videos/" + video + "/comments").header("Authorization", bearer(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void rejectedAttemptsStillCountTowardThePostingLimit() throws Exception {
        UUID spammer = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            postComment(spammer, video, "", null, null).andExpect(status().isBadRequest());
        }
        // five rejected attempts used the budget: a valid comment is now refused too, so garbage cannot be spammed free
        postComment(spammer, video, "valid", null, null).andExpect(status().isTooManyRequests());
        assertThat(rows()).isZero();
    }

    @Test
    void onlyPublishedVideosCanBeCommentedOnOrRead() throws Exception {
        for (UUID v : List.of(UUID.randomUUID(), video("UPLOADING"), video("TAKEN_DOWN"))) {
            postComment(me, v, "hello", null, null).andExpect(status().isNotFound());
            list(v, "").andExpect(status().isNotFound());
        }
        assertThat(rows()).isZero();
        assertThat(events.stream(CommentCountChanged.class).count()).isZero();
    }

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        UUID c = postedId(postComment("hi"));
        mvc.perform(post("/v1/videos/" + video + "/comments").contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson("hi", null))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/videos/" + video + "/comments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/comments/" + c + "/replies")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/comments/" + c)).andExpect(status().isUnauthorized());
    }

    // ---- replies ----

    @Test
    void repliesAreOneLevelDeepAndBelongToTheSameVideo() throws Exception {
        UUID top = postedId(postComment("top"));
        UUID reply = postedId(postComment(me, video, "reply", top, null));
        postComment(me, video, "reply to a reply", reply, null).andExpect(status().isBadRequest());
        postComment(me, video, "ghost parent", UUID.randomUUID(), null).andExpect(status().isNotFound());
        postComment(me, video("PUBLISHED"), "wrong video", top, null).andExpect(status().isBadRequest());
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void topLevelCommentsShowTheirReplyCountAndRepliesReadOldestFirst() throws Exception {
        UUID top = postedId(postComment("top"));
        postedId(postComment(UUID.randomUUID(), video, "first reply", top, null));
        postedId(postComment(UUID.randomUUID(), video, "second reply", top, null));
        UUID other = postedId(postComment(UUID.randomUUID(), video, "no replies", null, null));

        String page = list(video, "").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Number>>read(page, "$.items[?(@.id=='" + top + "')].replyCount").getFirst().longValue()).isEqualTo(2);
        assertThat(JsonPath.<List<Number>>read(page, "$.items[?(@.id=='" + other + "')].replyCount").getFirst().longValue()).isZero();
        assertThat(JsonPath.<List<String>>read(page, "$.items[*].body")).doesNotContain("first reply", "second reply");

        assertThat(JsonPath.<List<String>>read(replies(top, "").andReturn().getResponse().getContentAsString(), "$.items[*].body"))
                .containsExactly("first reply", "second reply");
        replies(UUID.randomUUID(), "").andExpect(status().isNotFound());
    }

    // ---- paging ----

    @Test
    void commentsPageNewestFirstWithoutGapsOrRepeats() throws Exception {
        bulkInsert(25, false);
        List<String> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String json = list(video, "?limit=10" + (cursor == null ? "" : "&cursor=" + cursor))
                    .andReturn().getResponse().getContentAsString();
            all.addAll(JsonPath.read(json, "$.items[*].body"));
            cursor = JsonPath.read(json, "$.nextCursor");
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(all).hasSize(25).doesNotHaveDuplicates();
        assertThat(all.getFirst()).isEqualTo("bulk 25");   // newest first
        assertThat(all.getLast()).isEqualTo("bulk 1");
    }

    @Test
    void commentsWithTheSameTimestampStillPageStably() throws Exception {
        bulkInsert(10, true); // identical created_at: the ID breaks the tie
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            String json = list(video, "?limit=3" + (cursor == null ? "" : "&cursor=" + cursor))
                    .andReturn().getResponse().getContentAsString();
            ids.addAll(JsonPath.read(json, "$.items[*].id"));
            cursor = JsonPath.read(json, "$.nextCursor");
        } while (cursor != null);
        assertThat(ids).hasSize(10).doesNotHaveDuplicates();
        List<String> expected = db.sql("SELECT id::text FROM social.comments ORDER BY created_at DESC, id DESC")
                .query(String.class).list();
        assertThat(ids).isEqualTo(expected);
    }

    @Test
    void aPageThatEndsExactlyAtTheEndHasNoCursor() throws Exception {
        bulkInsert(10, false);
        list(video, "?limit=10").andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.nextCursor").doesNotExist());
        list(video, "?limit=9").andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void badLimitsAndCursorsAreRefused() throws Exception {
        for (String q : List.of("?limit=0", "?limit=-3", "?limit=51", "?limit=abc", "?cursor=garbage", "?cursor=", "?cursor=" + "a".repeat(200))) {
            list(video, q).andExpect(status().isBadRequest());
        }
    }

    @Test
    void hiddenCommentsNeverAppearAndAreNotCounted() throws Exception {
        UUID visible = postedId(postComment("visible"));
        UUID hidden = postedId(postComment("hidden"));
        db.sql("UPDATE social.comments SET status = 'HIDDEN' WHERE id = :id").param("id", hidden).update();
        assertThat(JsonPath.<List<String>>read(list(video, "").andReturn().getResponse().getContentAsString(), "$.items[*].id"))
                .containsExactly(visible.toString());
        deleteComment(me, hidden).andExpect(status().isNotFound());
    }

    // ---- deleting ----

    @Test
    void onlyTheAuthorCanDeleteAndDeletingReallyDeletesWithItsReplies() throws Exception {
        UUID author = UUID.randomUUID();
        UUID top = postedId(postComment(author, video, "mine", null, null));
        UUID reply = postedId(postComment(UUID.randomUUID(), video, "reply", top, null));

        deleteComment(UUID.randomUUID(), top).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:forbidden"));
        assertThat(rows()).isEqualTo(2);

        deleteComment(author, top).andExpect(status().isNoContent());
        assertThat(rows()).as("the row and its reply are really gone").isZero();
        deleteComment(author, top).andExpect(status().isNotFound());
        replies(reply, "").andExpect(status().isNotFound());
    }

    @Test
    void aMalformedCommentIdIsABadRequest() throws Exception {
        mvc.perform(delete("/v1/comments/nonsense").header("Authorization", bearer(me))).andExpect(status().isBadRequest());
    }

    // ---- retries and abuse ----

    @Test
    void aRetriedPostWithTheSameKeyIsPostedOnceAndTheReplayIsMarked() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = postComment(me, video, "once", null, key).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = postComment(me, video, "once", null, key).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(rows()).isEqualTo(1);
        assertThat(events.stream(CommentCountChanged.class).count()).isEqualTo(1);

        // the same key with other text, or for another video, is a different request
        postComment(me, video, "something else", null, key).andExpect(status().isUnprocessableContent());
        postComment(me, video("PUBLISHED"), "once", null, key).andExpect(status().isUnprocessableContent());
        // and someone else's identical key is theirs alone
        postComment(UUID.randomUUID(), video, "once", null, key).andExpect(status().isCreated());
    }

    @Test
    void postingTooFastIsStopped() throws Exception {
        UUID spammer = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            postComment(spammer, video, "spam " + i, null, null).andExpect(status().isCreated());
        }
        postComment(spammer, video, "one too many", null, null).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertThat(rows()).isEqualTo(5);
        postComment(UUID.randomUUID(), video, "someone else is fine", null, null).andExpect(status().isCreated());
    }

    @Test
    void twentySimultaneousPostsFromTwentyPeopleAllLand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                go.await();
                return postComment(UUID.randomUUID(), video, "parallel " + n, null, null).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (var f : results) {
            assertThat(f.get(120, TimeUnit.SECONDS)).isEqualTo(201);
        }
        pool.shutdownNow();
        assertThat(rows()).isEqualTo(20);
        assertThat(db.sql("SELECT count(DISTINCT id) FROM social.comments").query(Long.class).single()).isEqualTo(20);
    }

    // ---- the counter ----

    @Test
    void theVideosCommentCounterFollowsAddsDeletesAndHiddenComments() throws Exception {
        UUID top1 = postedId(postComment(UUID.randomUUID(), video, "top1", null, null));
        postedId(postComment(UUID.randomUUID(), video, "reply", top1, null));
        UUID top2 = postedId(postComment(UUID.randomUUID(), video, "top2", null, null));
        counters.refresh();
        assertThat(commentCounter()).as("replies count too").isEqualTo(3);

        UUID author = db.sql("SELECT author_id FROM social.comments WHERE id = :id").param("id", top1)
                .query(UUID.class).single();
        deleteComment(author, top1).andExpect(status().isNoContent()); // removes top1 and its reply
        counters.refresh();
        assertThat(commentCounter()).isEqualTo(1);

        db.sql("UPDATE social.comments SET status = 'HIDDEN' WHERE id = :id").param("id", top2).update();
        counters.reconcileAll();
        assertThat(commentCounter()).as("reconciliation catches changes made outside the API").isZero();
    }

    private long commentCounter() {
        return db.sql("SELECT COALESCE((SELECT comments FROM engagement.video_counters WHERE video_id = :v), 0)")
                .param("v", video).query(Long.class).single();
    }
}
