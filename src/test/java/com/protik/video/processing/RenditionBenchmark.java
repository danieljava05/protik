package com.protik.video.processing;

import com.protik.video.Rendition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reproducible measurement of what each rendition costs (ADR 0022, roadmap T3.2). It is not a pass/fail test: it encodes
 * one input file at every rendition, with the same code and settings the upload pipeline uses, and prints a table.
 *
 * <pre>
 * PROTIK_BENCH_INPUT=/path/to/clip.mp4 [PROTIK_BENCH_OUT=table.md] [PROTIK_BENCH_RUNS=3] ./mvnw -o test -Dtest=RenditionBenchmark
 * </pre>
 *
 * The numbers describe <em>that clip on that machine</em> only. Animation, a talking head and a street scene differ
 * several-fold, so a result is never a general claim about the platform.
 */
@EnabledIfEnvironmentVariable(named = "PROTIK_BENCH_INPUT", matches = ".+")
class RenditionBenchmark {

    /** The ladder in video/V2__reference_data.sql. The audio row is skipped when the clip has no audio. */
    static final List<Rendition> LADDER = List.of(new Rendition("audio", null, 48), new Rendition("144p", 144, 150),
            new Rendition("240p", 240, 300), new Rendition("480p", 480, 800), new Rendition("720p", 720, 1800));

    @Test
    void measure() throws Exception {
        Path input = Path.of(System.getenv("PROTIK_BENCH_INPUT"));
        int runs = Integer.parseInt(System.getenv().getOrDefault("PROTIK_BENCH_RUNS", "3"));
        var tools = new MediaTools(new ProcessingProperties.Ffmpeg("ffmpeg", "ffprobe", Duration.ofMinutes(10), 2, 1_000_000_000L),
                JsonMapper.builder().build());
        var probe = tools.probe(input, Duration.ofMinutes(10).toMillis());
        Path work = Files.createTempDirectory("protik-bench-");

        List<String> rows = new ArrayList<>();
        long top = 0;
        List<long[]> measured = new ArrayList<>();
        List<Rendition> used = new ArrayList<>();
        for (Rendition r : LADDER) {
            if (r.heightPx() == null ? !probe.hasAudio() : r.heightPx() > probe.height()) {
                continue;
            }
            Path out = work.resolve(r.code() + ".mp4");
            long best = Long.MAX_VALUE;
            for (int i = 0; i < runs; i++) {
                long start = System.nanoTime();
                tools.encode(input, out, r, probe);
                best = Math.min(best, System.nanoTime() - start);
            }
            long bytes = Files.size(out);
            measured.add(new long[] {bytes, best});
            used.add(r);
            if (r.code().equals("720p") || (top == 0 && r.heightPx() != null)) {
                top = bytes;
            }
        }
        double seconds = probe.durationMs() / 1000.0;
        rows.add("| Rendition | Size | Average kbps | MB per 10 min | vs largest | Encode time (best of " + runs + ") | Speed |");
        rows.add("|---|---:|---:|---:|---:|---:|---:|");
        for (int i = 0; i < used.size(); i++) {
            long bytes = measured.get(i)[0];
            double encodeSeconds = measured.get(i)[1] / 1e9;
            rows.add(String.format(Locale.ROOT, "| %s | %.2f MB | %.0f | %.1f | %.0f%% | %.2f s | %.1fx real time |",
                    used.get(i).code(), bytes / 1e6, bytes * 8 / seconds / 1000, bytes / seconds * 600 / 1e6,
                    100.0 * bytes / top, encodeSeconds, seconds / encodeSeconds));
        }
        String header = String.format(Locale.ROOT,
                "Input: %s (%dx%d, %.1f s, audio: %s)%nMachine: %s, %d logical processors, %s %s, Java %s%nffmpeg threads per encode: 2, CRF 28, preset veryfast, +faststart%n%n",
                input.getFileName(), probe.width(), probe.height(), seconds, probe.hasAudio() ? "yes" : "no",
                System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown CPU"), Runtime.getRuntime().availableProcessors(),
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("java.version"));
        String report = header + String.join(System.lineSeparator(), rows) + System.lineSeparator();
        System.out.println(report);
        String out = System.getenv("PROTIK_BENCH_OUT");
        if (out != null && !out.isBlank()) {
            writeQuietly(Path.of(out), report);
        }
    }

    private static void writeQuietly(Path path, String text) throws IOException {
        Files.writeString(path, text);
    }
}
