package com.protik.video.captions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.TokenService;
import com.protik.shared.job.JobContext;
import com.protik.video.storage.ObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0023: creator captions, accent-insensitive transcript search, and the guarded machine-caption job. */
@SpringBootTest(properties = {"protik.storage.sweep-interval=PT1S", "protik.engagement.refresh-interval=PT1H",
        "protik.engagement.reconcile-interval=PT1H"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CaptionIntegrationTests.FakeModel.class})
class CaptionIntegrationTests {

    static final String YORUBA = """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            Ẹ káàbọ̀, ọmọ mi

            00:00:04.000 --> 00:00:06.000
            Mo fẹ́ràn oúnjẹ Nàìjíríà
            """;

    /** A transcriber that "hears" fixed speech, so the wiring can be tested without any model. */
    static class FakeTranscriber implements Transcriber {
        volatile boolean available = true;
        volatile int calls;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public String modelVersion() {
            return "0.1";
        }

        @Override
        public Transcript transcribe(Path audio, String language) throws IOException {
            calls++;
            if (!Files.exists(audio)) {
                throw new IOException("audio missing");
            }
            return new Transcript(List.of(new Cue(0, 2000, "machine heard this"), new Cue(2000, 4000, "and this")), 0.75);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModel {
        @Bean
        @Primary
        FakeTranscriber fakeTranscriber() {
            return new FakeTranscriber();
        }
    }

    static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        root = Files.createTempDirectory("protik-caption-objects-");
        registry.add("protik.storage.root", () -> root.toString());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    ObjectStorage storage;
    @Autowired
    FakeTranscriber transcriber;
    @Autowired
    TranscribeVideoJob job;

    private final UUID creator = UUID.randomUUID();
    private UUID video;

    @BeforeEach
    void setUp() {
        db.sql("DELETE FROM shared.rate_limits").update();
        db.sql("DELETE FROM video.videos WHERE description = 'caption-test'").update();
        db.sql("UPDATE video.languages SET asr_supported = false").update();
        transcriber.available = true;
        transcriber.calls = 0;
        video = video(creator, "PUBLISHED", "yo");
    }

    /** The flag is shared database state that SchemaTests asserts is off; never leave it on. */
    @org.junit.jupiter.api.AfterEach
    void switchMachineCaptionsOff() {
        db.sql("UPDATE video.languages SET asr_supported = false").update();
    }

    // ---- helpers ----

    private UUID video(UUID owner, String status, String language) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO video.videos (id, creator_id, description, spoken_language, status, published_at, duration_ms) "
                        + "VALUES (:id, :c, 'caption-test', :lang, :s, CASE WHEN :s = 'PUBLISHED' THEN now() END, "
                        + "CASE WHEN :s = 'PUBLISHED' THEN 10000 END)")
                .param("id", id).param("c", owner).param("lang", language).param("s", status).update();
        return id;
    }

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private ResultActions putCaptions(UUID user, UUID videoId, String language, String vtt) throws Exception {
        return mvc.perform(put("/v1/videos/" + videoId + "/captions/" + language).header("Authorization", bearer(user))
                .contentType("text/vtt").content(vtt.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions search(String q, String language) throws Exception {
        return mvc.perform(get("/v1/search/transcripts").param("q", q).param(language == null ? "x" : "language",
                language == null ? "" : language).header("Authorization", bearer(UUID.randomUUID())));
    }

    // ---- creator captions ----

    @Test
    void theCreatorUploadsCaptionsAndAnyoneCanReadThemBack() throws Exception {
        putCaptions(creator, video, "yo", YORUBA).andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("CREATOR"));
        String vtt = mvc.perform(get("/v1/videos/" + video + "/captions/yo").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/vtt;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(vtt).startsWith("WEBVTT").contains("Ẹ káàbọ̀, ọmọ mi").contains("00:00:01.000 --> 00:00:03.000");
        mvc.perform(get("/v1/videos/" + video + "/captions").header("Authorization", bearer(creator)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].language").value("yo"))
                .andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    void uploadingAgainReplacesTheCaptionsAndRemovesTheOldFile() throws Exception {
        putCaptions(creator, video, "yo", YORUBA).andExpect(status().isCreated());
        String oldKey = db.sql("SELECT vtt_storage_key FROM video.captions WHERE video_id = :v").param("v", video)
                .query(String.class).single();
        putCaptions(creator, video, "yo", "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nnew text\n").andExpect(status().isCreated());
        assertThat(db.sql("SELECT count(*) FROM video.captions WHERE video_id = :v").param("v", video).query(Long.class).single())
                .isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM video.caption_segments s JOIN video.captions c ON c.id = s.caption_id "
                + "WHERE c.video_id = :v").param("v", video).query(Long.class).single()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(storage.exists(oldKey)).isFalse());
    }

    @Test
    void onlyTheCreatorMayAddCaptionsAndBadInputIsRefused() throws Exception {
        putCaptions(UUID.randomUUID(), video, "yo", YORUBA).andExpect(status().isNotFound());
        putCaptions(creator, UUID.randomUUID(), "yo", YORUBA).andExpect(status().isNotFound());
        putCaptions(creator, video, "xx", YORUBA).andExpect(status().isBadRequest());
        putCaptions(creator, video, "yo", "not webvtt at all").andExpect(status().isBadRequest());
        putCaptions(creator, video, "yo", "WEBVTT\n\n00:00:50.000 --> 00:00:51.000\nafter the end\n").andExpect(status().isBadRequest());
        putCaptions(creator, video, "yo", "").andExpect(status().isBadRequest());
        putCaptions(creator, video, "yo", "WEBVTT\n\n" + "x".repeat(WebVtt.MAX_BYTES)).andExpect(status().isContentTooLarge());
        mvc.perform(put("/v1/videos/" + video + "/captions/yo").contentType("text/vtt").content(YORUBA))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/v1/videos/" + video + "/captions/yo").header("Authorization", bearer(creator)).contentType("text/vtt")
                .content(new byte[] {(byte) 0xC3, 0x28, 'W'})).andExpect(status().isBadRequest());
        assertThat(db.sql("SELECT count(*) FROM video.captions WHERE video_id = :v").param("v", video).query(Long.class).single())
                .isZero();
    }

    @Test
    void failedAndTakenDownVideosTakeNoCaptions() throws Exception {
        putCaptions(creator, video(creator, "FAILED", "yo"), "yo", YORUBA).andExpect(status().isConflict());
        putCaptions(creator, video(creator, "TAKEN_DOWN", "yo"), "yo", YORUBA).andExpect(status().isConflict());
    }

    @Test
    void unpublishedVideosShowTheirCaptionsOnlyToTheOwner() throws Exception {
        UUID processing = video(creator, "PROCESSING", "yo");
        putCaptions(creator, processing, "yo", "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nready early\n").andExpect(status().isCreated());
        mvc.perform(get("/v1/videos/" + processing + "/captions/yo").header("Authorization", bearer(creator)))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/videos/" + processing + "/captions/yo").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/videos/" + processing + "/captions").header("Authorization", bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/videos/" + video + "/captions/ig").header("Authorization", bearer(creator)))
                .andExpect(status().isNotFound());
    }

    // ---- search ----

    @Test
    void searchFindsWhatWasSaidWithoutCaringAboutAccents() throws Exception {
        putCaptions(creator, video, "yo", YORUBA).andExpect(status().isCreated());
        for (String query : new String[] {"ọmọ", "omo", "OMO", "kaabo", "Ẹ káàbọ̀", "naijiria", "fẹ́ràn", "feran"}) {
            search(query, null).andExpect(status().isOk()).andExpect(jsonPath("$.matches[?(@.videoId == '" + video + "')]").exists());
        }
        search("omo", null).andExpect(jsonPath("$.matches[0].startMs").value(1000))
                .andExpect(jsonPath("$.matches[0].language").value("yo")).andExpect(jsonPath("$.matches[0].source").value("CREATOR"))
                .andExpect(jsonPath("$.matches[0].text").value("Ẹ káàbọ̀, ọmọ mi"));
        search("something nobody said", null).andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void searchOnlyReturnsPublishedVideosAndHonoursTheLanguageFilter() throws Exception {
        putCaptions(creator, video, "yo", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nunique-word-alpha\n").andExpect(status().isCreated());
        UUID hidden = video(creator, "PROCESSING", "yo");
        putCaptions(creator, hidden, "yo", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nunique-word-alpha secret\n")
                .andExpect(status().isCreated());
        search("alpha", null).andExpect(jsonPath("$.matches.length()").value(1))
                .andExpect(jsonPath("$.matches[0].videoId").value(video.toString()));
        search("alpha", "ig").andExpect(jsonPath("$.matches.length()").value(0));
        search("alpha", "yo").andExpect(jsonPath("$.matches.length()").value(1));
        // Taking the video down removes it from search at once.
        db.sql("UPDATE video.videos SET status = 'TAKEN_DOWN' WHERE id = :id").param("id", video).update();
        search("alpha", null).andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void searchValidatesItsInputAndIsRateLimited() throws Exception {
        search("a", null).andExpect(status().isBadRequest());
        search("x".repeat(101), null).andExpect(status().isBadRequest());
        search("fine", "zz").andExpect(status().isBadRequest());
        mvc.perform(get("/v1/search/transcripts").param("q", "fine").param("limit", "0").header("Authorization", bearer(creator)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/search/transcripts").param("q", "fine")).andExpect(status().isUnauthorized());
        UUID busy = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            mvc.perform(get("/v1/search/transcripts").param("q", "fine").header("Authorization", bearer(busy)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/v1/search/transcripts").param("q", "fine").header("Authorization", bearer(busy)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void searchTextIsTreatedAsTextNotAsQuerySyntax() throws Exception {
        putCaptions(creator, video, "yo", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nplain words here\n").andExpect(status().isCreated());
        for (String hostile : new String[] {"' OR 1=1 --", "plain & !words | (here", "plain:* <-> words", "\\\\", "%%"}) {
            mvc.perform(get("/v1/search/transcripts").param("q", hostile).header("Authorization", bearer(UUID.randomUUID())))
                    .andExpect(status().isOk());
        }
        // A NUL or other control character is refused rather than passed to the database (it used to be a 500).
        for (String control : new String[] {"plain\u0000", "plain\u0001words", "tab\there"}) {
            mvc.perform(get("/v1/search/transcripts").param("q", control).header("Authorization", bearer(UUID.randomUUID())))
                    .andExpect(status().isBadRequest());
        }
        search("plain words", null).andExpect(jsonPath("$.matches.length()").value(1));
    }

    // ---- machine captions ----

    private JobContext context(UUID videoId) {
        return new JobContext() {
            @Override
            public UUID id() {
                return UUID.randomUUID();
            }

            @Override
            public String type() {
                return "video.caption";
            }

            @Override
            public String payloadJson() {
                return "{\"videoId\":\"" + videoId + "\"}";
            }

            @Override
            public int attempt() {
                return 1;
            }

            @Override
            public boolean extendLease(Duration duration) {
                return true;
            }
        };
    }

    private void withAudio(UUID videoId) throws IOException {
        String key = "renditions/" + videoId + "/audio.mp4";
        storage.put(key, new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}), 100);
        db.sql("INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes) "
                + "VALUES (gen_random_uuid(), :v, 'MP4_VARIANT', 'audio', :k, 3)").param("v", videoId).param("k", key).update();
    }

    @Test
    void aLanguageWithoutAMeasuredErrorRateGetsNoMachineCaptions() throws Exception {
        withAudio(video);
        job.handle(context(video));
        assertThat(transcriber.calls).isZero();
        assertThat(db.sql("SELECT count(*) FROM video.captions WHERE video_id = :v").param("v", video).query(Long.class).single())
                .isZero();
    }

    @Test
    void aSupportedLanguageGetsMachineCaptionsRecordedWithTheirModel() throws Exception {
        db.sql("UPDATE video.languages SET asr_supported = true WHERE code = 'yo'").update();
        withAudio(video);
        job.handle(context(video));
        job.handle(context(video)); // delivered twice: still one caption
        assertThat(transcriber.calls).isEqualTo(2);
        var row = db.sql("SELECT source, status, model, model_version, avg_confidence FROM video.captions WHERE video_id = :v")
                .param("v", video).query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getString(4) + "|" + rs.getDouble(5)).single();
        assertThat(row).isEqualTo("ASR|READY|fake-model|0.1|0.75");
        search("machine heard", null).andExpect(jsonPath("$.matches[0].source").value("ASR"));
    }

    @Test
    void theCreatorsOwnCaptionsWinOverTheMachinesButBothAreKept() throws Exception {
        db.sql("UPDATE video.languages SET asr_supported = true WHERE code = 'yo'").update();
        withAudio(video);
        job.handle(context(video));
        putCaptions(creator, video, "yo", YORUBA).andExpect(status().isCreated());
        mvc.perform(get("/v1/videos/" + video + "/captions/yo").header("Authorization", bearer(creator)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ọmọ mi")));
        mvc.perform(get("/v1/videos/" + video + "/captions").header("Authorization", bearer(creator)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void withoutAConfiguredModelNothingIsMadeEvenForASupportedLanguage() throws Exception {
        db.sql("UPDATE video.languages SET asr_supported = true WHERE code = 'yo'").update();
        transcriber.available = false;
        withAudio(video);
        job.handle(context(video));
        assertThat(transcriber.calls).isZero();
        assertThat(db.sql("SELECT count(*) FROM video.captions WHERE video_id = :v").param("v", video).query(Long.class).single())
                .isZero();
    }

    // ---- cleanup ----

    @Test
    void deletingAVideoRemovesItsCaptionsFilesToo() throws Exception {
        putCaptions(creator, video, "yo", YORUBA).andExpect(status().isCreated());
        String key = db.sql("SELECT vtt_storage_key FROM video.captions WHERE video_id = :v").param("v", video)
                .query(String.class).single();
        assertThat(storage.exists(key)).isTrue();
        db.sql("DELETE FROM video.videos WHERE id = :id").param("id", video).update();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(storage.exists(key)).isFalse());
    }
}
