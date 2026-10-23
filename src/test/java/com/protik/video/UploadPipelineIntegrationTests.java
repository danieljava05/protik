package com.protik.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.TokenService;
import com.protik.video.processing.MalwareScanner;
import com.protik.video.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ADR 0022 end to end with a real FFmpeg and the local file store: upload, scan gate, probe gate, transcode, publish,
 * serve, and file cleanup. The scanner is a controllable fake (no ClamAV here).
 */
@SpringBootTest(properties = {
        "protik.jobs.enabled=true", "protik.jobs.worker-threads=2", "protik.jobs.poll-interval=50ms",
        "protik.jobs.retry-base=200ms", "protik.jobs.retry-max=500ms", "protik.jobs.maintenance-interval=500ms",
        "protik.upload.max-bytes=3000000", "protik.upload.max-duration=5s", "protik.storage.sweep-interval=PT1S",
        "protik.engagement.refresh-interval=PT1H", "protik.engagement.reconcile-interval=PT1H"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, UploadPipelineIntegrationTests.ScannerConfiguration.class})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class UploadPipelineIntegrationTests {

    enum Verdict {
        CLEAN, INFECTED, DOWN
    }

    static class ControllableScanner implements MalwareScanner {
        volatile Verdict verdict = Verdict.CLEAN;
        volatile int calls;

        @Override
        public boolean scans() {
            return true;
        }

        @Override
        public Result scan(InputStream content) throws IOException {
            calls++;
            content.readAllBytes();
            return switch (verdict) {
                case CLEAN -> Result.ok();
                case INFECTED -> Result.infected("Test.Signature");
                case DOWN -> throw new ScannerUnavailableException("scanner is down");
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScannerConfiguration {
        @Bean
        @Primary
        ControllableScanner controllableScanner() {
            return new ControllableScanner();
        }
    }

    static Path root;
    static Path fixtures;
    static Path sample;
    static Path small;
    static Path silent;
    static Path tooLong;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        root = Files.createTempDirectory("protik-test-objects-");
        registry.add("protik.storage.root", () -> root.toString());
    }

    @BeforeAll
    static void makeFixtures() throws Exception {
        boolean available = run("ffmpeg", "-version") == 0;
        if (System.getenv("PROTIK_REQUIRE_FFMPEG") != null) {
            assertThat(available).as("ffmpeg must be installed when PROTIK_REQUIRE_FFMPEG is set").isTrue();
        }
        assumeThat(available).as("ffmpeg is not installed").isTrue();
        fixtures = Files.createTempDirectory("protik-fixtures-");
        sample = movie("sample", 3, "640x360", true);
        small = movie("small", 2, "128x72", true);
        silent = movie("silent", 2, "426x240", false);
        tooLong = movie("toolong", 7, "160x90", true);
    }

    private static Path movie(String name, int seconds, String size, boolean audio) throws Exception {
        Path out = fixtures.resolve(name + ".mp4");
        var args = new java.util.ArrayList<>(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc=duration=" + seconds + ":size=" + size + ":rate=15"));
        if (audio) {
            args.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds));
        }
        args.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-crf", "35", "-pix_fmt", "yuv420p"));
        if (audio) {
            args.addAll(List.of("-c:a", "aac", "-b:a", "32k"));
        }
        args.add(out.toString());
        assertThat(run(args.toArray(new String[0]))).as("making fixture " + name).isZero();
        return out;
    }

    private static int run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    ControllableScanner scanner;
    @Autowired
    ObjectStorage storage;

    private final UUID me = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scanner.verdict = Verdict.CLEAN;
        scanner.calls = 0;
        db.sql("DELETE FROM shared.rate_limits").update();
        db.sql("DELETE FROM shared.jobs WHERE type = 'video.process'").update();
    }

    // ---- helpers ----

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private UUID create(UUID user) throws Exception {
        String body = mvc.perform(post("/v1/videos").header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"upload-test\",\"language\":\"yo\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("UPLOADING")).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions send(UUID user, UUID video, String contentType, byte[] bytes) throws Exception {
        return mvc.perform(put("/v1/videos/" + video + "/content").header("Authorization", bearer(user))
                .contentType(contentType).content(bytes));
    }

    private UUID uploaded(Path file) throws Exception {
        UUID video = create(me);
        send(me, video, "video/mp4", Files.readAllBytes(file)).andExpect(status().isAccepted());
        return video;
    }

    private String statusOf(UUID video) {
        return db.sql("SELECT status FROM video.videos WHERE id = :id").param("id", video).query(String.class).single();
    }

    private String scanStatus(UUID video) {
        return db.sql("SELECT scan_status FROM video.videos WHERE id = :id").param("id", video).query(String.class).single();
    }

    private String reason(UUID video) {
        return db.sql("SELECT failure_reason FROM video.videos WHERE id = :id").param("id", video).query(String.class).single();
    }

    private void awaitStatus(UUID video, String expected) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(statusOf(video)).isEqualTo(expected));
    }

    private List<String> assetKeys(UUID video) {
        return db.sql("SELECT storage_key FROM video.video_assets WHERE video_id = :id").param("id", video)
                .query(String.class).list();
    }

    // ---- the happy path ----

    @Test
    void anUploadIsScannedProbedTranscodedPublishedAndServed() throws Exception {
        UUID video = uploaded(sample);
        assertThat(statusOf(video)).isIn("PROCESSING", "PUBLISHED");
        awaitStatus(video, "PUBLISHED");

        assertThat(scanStatus(video)).isEqualTo("CLEAN");
        var variants = db.sql("SELECT rendition_code, size_bytes, avg_bitrate_kbps FROM video.video_assets "
                + "WHERE video_id = :id AND kind = 'MP4_VARIANT' ORDER BY rendition_code").param("id", video)
                .query((rs, n) -> rs.getString(1) + ":" + rs.getLong(2) + ":" + rs.getInt(3)).list();
        List<String> codes = variants.stream().map(v -> v.split(":")[0]).toList();
        // A 640x360 source gets 144p and 240p and audio, but is never enlarged to 480p or 720p.
        assertThat(codes).containsExactlyInAnyOrder("144p", "240p", "audio");
        long size144 = sizeOf(variants, "144p");
        long size240 = sizeOf(variants, "240p");
        long sizeAudio = sizeOf(variants, "audio");
        assertThat(sizeAudio).as("audio only is the smallest").isLessThan(size144).isPositive();
        assertThat(size144).isLessThanOrEqualTo(size240);
        assertThat(db.sql("SELECT duration_ms FROM video.videos WHERE id = :id").param("id", video).query(Integer.class).single())
                .isBetween(2500, 3600);

        // The API tells the owner what exists.
        mvc.perform(get("/v1/videos/" + video).header("Authorization", bearer(me)))
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.renditions.length()").value(3))
                .andExpect(jsonPath("$.language").value("yo"));

        // A stranger can fetch a rendition with Range support, and the bytes are really an MP4.
        UUID stranger = UUID.randomUUID();
        byte[] head = mvc.perform(get("/v1/videos/" + video + "/renditions/144p").header("Authorization", bearer(stranger))
                        .header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Range", "bytes 0-99/" + size144)).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(head).hasSize(100);
        assertThat(new String(Arrays.copyOfRange(head, 4, 8), StandardCharsets.US_ASCII)).isEqualTo("ftyp");
        mvc.perform(get("/v1/videos/" + video + "/renditions/audio").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "audio/mp4"));
        mvc.perform(get("/v1/videos/" + video + "/thumbnail").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"));
        mvc.perform(get("/v1/videos/" + video + "/renditions/720p").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());

        // The feed heard about it, so the video is discoverable.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                db.sql("SELECT count(*) FROM feed.candidates WHERE video_id = :id").param("id", video).query(Long.class).single())
                .isEqualTo(1));

        // The quarantined original is gone, both from the database and from disk.
        assertThat(assetKeys(video)).noneMatch(k -> k.startsWith("quarantine/"));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                Files.exists(root.resolve("quarantine"))
                        ? Files.list(root.resolve("quarantine")).filter(p -> p.getFileName().toString().startsWith(video.toString()))
                                .count()
                        : 0L).isZero());
    }

    private static long sizeOf(List<String> variants, String code) {
        return variants.stream().filter(v -> v.startsWith(code + ":")).mapToLong(v -> Long.parseLong(v.split(":")[1])).findFirst()
                .orElseThrow();
    }

    @Test
    void aTinySourceStillGetsTheLowestVideoRenditionAndAudio() throws Exception {
        UUID video = uploaded(small);
        awaitStatus(video, "PUBLISHED");
        var codes = db.sql("SELECT rendition_code FROM video.video_assets WHERE video_id = :id AND kind = 'MP4_VARIANT'")
                .param("id", video).query(String.class).list();
        assertThat(codes).containsExactlyInAnyOrder("144p", "audio");
    }

    @Test
    void aSilentVideoGetsNoAudioRendition() throws Exception {
        UUID video = uploaded(silent);
        awaitStatus(video, "PUBLISHED");
        var codes = db.sql("SELECT rendition_code FROM video.video_assets WHERE video_id = :id AND kind = 'MP4_VARIANT'")
                .param("id", video).query(String.class).list();
        assertThat(codes).containsExactlyInAnyOrder("144p", "240p");
    }

    // ---- the gates ----

    @Test
    void anInfectedFileIsRejectedDeletedAndNeverPublished() throws Exception {
        scanner.verdict = Verdict.INFECTED;
        UUID video = uploaded(sample);
        awaitStatus(video, "FAILED");
        assertThat(scanStatus(video)).isEqualTo("INFECTED");
        assertThat(reason(video)).contains("virus scanner").doesNotContain("Test.Signature");
        assertThat(assetKeys(video)).isEmpty();
        assertThat(db.sql("SELECT count(*) FROM feed.candidates WHERE video_id = :id").param("id", video).query(Long.class)
                .single()).isZero();
        mvc.perform(get("/v1/videos/" + video + "/renditions/144p").header("Authorization", bearer(me)))
                .andExpect(status().isNotFound());
    }

    @Test
    void whenTheScannerIsDownNothingIsPublishedAndThePublishHappensOnceItIsBack() throws Exception {
        scanner.verdict = Verdict.DOWN;
        UUID video = uploaded(sample);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(scanner.calls).isGreaterThanOrEqualTo(2));
        assertThat(statusOf(video)).isEqualTo("PROCESSING");
        assertThat(scanStatus(video)).isEqualTo("PENDING");

        scanner.verdict = Verdict.CLEAN;
        awaitStatus(video, "PUBLISHED");
        assertThat(scanStatus(video)).isEqualTo("CLEAN");
    }

    @Test
    void aFileThatLooksLikeAnMp4ButIsNotOneFailsTheProbe() throws Exception {
        byte[] fake = new byte[5000];
        System.arraycopy("\0\0\0 ftypisom".getBytes(StandardCharsets.ISO_8859_1), 0, fake, 0, 12);
        UUID video = create(me);
        send(me, video, "video/mp4", fake).andExpect(status().isAccepted());
        awaitStatus(video, "FAILED");
        assertThat(reason(video)).isNotBlank();
        assertThat(assetKeys(video)).isEmpty();
    }

    @Test
    void aVideoLongerThanTheLimitIsRejected() throws Exception {
        UUID video = uploaded(tooLong);
        awaitStatus(video, "FAILED");
        assertThat(reason(video)).contains("longer than 5 seconds");
        assertThat(assetKeys(video)).isEmpty();
    }

    // ---- the front door ----

    @Test
    void wrongTypesAndMismatchedSignaturesAreRefusedBeforeAnythingIsKept() throws Exception {
        byte[] mp4 = Files.readAllBytes(small);
        UUID video = create(me);
        send(me, video, "application/octet-stream", mp4).andExpect(status().isUnsupportedMediaType());
        send(me, video, "text/html", mp4).andExpect(status().isUnsupportedMediaType());
        send(me, video, "video/webm", mp4).andExpect(status().isUnsupportedMediaType());
        send(me, video, "video/mp4", "MZ this is an executable pretending to be a video".getBytes(StandardCharsets.ISO_8859_1))
                .andExpect(status().isUnsupportedMediaType());
        send(me, video, "video/mp4", new byte[0]).andExpect(status().isUnsupportedMediaType());
        assertThat(statusOf(video)).isEqualTo("UPLOADING");
        assertThat(assetKeys(video)).isEmpty();
        assertThat(quarantineFilesOf(video)).isZero();
        send(me, video, "video/mp4", mp4).andExpect(status().isAccepted()); // the video is still open for a good file
    }

    @Test
    void aFileOverTheLimitIsStoppedAndNothingRemains() throws Exception {
        byte[] big = new byte[3_000_001];
        System.arraycopy("\0\0\0 ftypisom".getBytes(StandardCharsets.ISO_8859_1), 0, big, 0, 12);
        UUID video = create(me);
        send(me, video, "video/mp4", big).andExpect(status().isContentTooLarge());
        assertThat(statusOf(video)).isEqualTo("UPLOADING");
        assertThat(quarantineFilesOf(video)).isZero();
    }

    private long quarantineFilesOf(UUID video) throws IOException {
        Path dir = root.resolve("quarantine");
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(video.toString())).count();
        }
    }

    @Test
    void onlyTheCreatorCanSendTheFileAndOnlyOnce() throws Exception {
        byte[] mp4 = Files.readAllBytes(small);
        UUID video = create(me);
        send(UUID.randomUUID(), video, "video/mp4", mp4).andExpect(status().isNotFound());
        send(me, UUID.randomUUID(), "video/mp4", mp4).andExpect(status().isNotFound());
        mvc.perform(put("/v1/videos/" + video + "/content").contentType("video/mp4").content(mp4)).andExpect(status().isUnauthorized());
        send(me, video, "video/mp4", mp4).andExpect(status().isAccepted());
        send(me, video, "video/mp4", mp4).andExpect(status().isConflict());
    }

    @Test
    void anUnpublishedVideoIsInvisibleToEveryoneButItsOwner() throws Exception {
        scanner.verdict = Verdict.DOWN;
        UUID video = uploaded(small);
        UUID stranger = UUID.randomUUID();
        mvc.perform(get("/v1/videos/" + video).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/v1/videos/" + video).header("Authorization", bearer(me))).andExpect(status().isOk());
        mvc.perform(get("/v1/videos/" + video + "/renditions/144p").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/videos/" + video + "/thumbnail").header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
        scanner.verdict = Verdict.CLEAN;
        awaitStatus(video, "PUBLISHED");
    }

    @Test
    void creatingAVideoIsIdempotentLimitedAndValidated() throws Exception {
        String body = "{\"description\":\"upload-test\"}";
        String first = mvc.perform(post("/v1/videos").header("Authorization", bearer(me)).header("Idempotency-Key", "k-123456")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        mvc.perform(post("/v1/videos").header("Authorization", bearer(me)).header("Idempotency-Key", "k-123456")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
        mvc.perform(post("/v1/videos").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"language\":\"xx\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/videos").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        UUID busy = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/v1/videos").header("Authorization", bearer(busy)).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isCreated());
        }
        mvc.perform(post("/v1/videos").header("Authorization", bearer(busy)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isTooManyRequests());
    }

    // ---- cleanup ----

    @Test
    void deletingTheAccountOfAPublishedVideoRemovesItsFilesFromDisk() throws Exception {
        UUID creator = UUID.randomUUID();
        db.sql("INSERT INTO identity.accounts (id, username) VALUES (:id, :u)").param("id", creator)
                .param("u", "up_" + creator.toString().substring(0, 8)).update();
        UUID video = create(creator);
        send(creator, video, "video/mp4", Files.readAllBytes(small)).andExpect(status().isAccepted());
        awaitStatus(video, "PUBLISHED");
        List<String> keys = assetKeys(video);
        assertThat(keys).isNotEmpty().allMatch(storage::exists);

        db.sql("DELETE FROM video.videos WHERE id = :id").param("id", video).update(); // what account deletion does
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(keys).noneMatch(storage::exists));
        assertThat(db.sql("SELECT count(*) FROM video.storage_tombstones WHERE storage_key = ANY(:k)")
                .param("k", keys.toArray(new String[0])).query(Long.class).single()).isZero();
    }
}
