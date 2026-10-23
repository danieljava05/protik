package com.protik.video.processing;

import com.protik.video.Rendition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs ffprobe and ffmpeg (ADR 0022 decision 3). Each run is a child process started from a fixed argument list (never
 * through a shell), with a cleared environment, no standard input, a protocol whitelist of {@code file}, a thread limit, an
 * output size limit and a wall-clock timeout after which it is killed.
 *
 * <p><strong>This is not a sandbox.</strong> A vulnerability in FFmpeg would run with this process's privileges. See ADR 0022
 * for what production must add.
 */
public class MediaTools {

    /** The file is not something we can or will process. The message is safe to show to the person who uploaded it. */
    public static class RejectedMediaException extends Exception {
        public RejectedMediaException(String message) {
            super(message);
        }
    }

    /** What the probe found. */
    public record Probe(int durationMs, int width, int height, boolean hasAudio) {
    }

    private static final Logger log = LoggerFactory.getLogger(MediaTools.class);
    private static final int MAX_STREAMS = 8;
    private static final int MAX_DIMENSION = 4096;
    /** ffprobe's JSON for a normal file is a few KB; this is ample and bounded. */
    private static final int CAPTURE_LIMIT = 128 * 1024;

    private final ProcessingProperties.Ffmpeg config;
    private final JsonMapper json;

    public MediaTools(ProcessingProperties.Ffmpeg config, JsonMapper json) {
        this.config = config;
        this.json = json;
    }

    /**
     * @throws RejectedMediaException if the file is not a usable video within the limits
     * @throws IOException            if the tool could not be run at all (a problem to retry, not a bad file)
     */
    public Probe probe(Path file, long maxDurationMs) throws IOException, RejectedMediaException {
        var result = run(config.probePath(), List.of("-v", "error", "-protocol_whitelist", "file", "-print_format", "json",
                "-show_format", "-show_streams", file.toString()), 30_000);
        if (result.exit() != 0) {
            throw new RejectedMediaException("The file could not be read as a video.");
        }
        JsonNode root;
        try {
            root = json.readTree(result.output());
        } catch (RuntimeException e) {
            throw new RejectedMediaException("The file could not be read as a video.");
        }
        JsonNode streams = root.path("streams");
        if (!streams.isArray() || streams.size() == 0 || streams.size() > MAX_STREAMS) {
            throw new RejectedMediaException("The file does not contain a normal video.");
        }
        JsonNode video = null;
        boolean audio = false;
        for (JsonNode stream : streams) {
            String type = stream.path("codec_type").asString("");
            if (type.equals("video") && video == null && stream.path("disposition").path("attached_pic").asInt(0) == 0) {
                video = stream;
            } else if (type.equals("audio")) {
                audio = true;
            }
        }
        if (video == null) {
            throw new RejectedMediaException("The file has no video.");
        }
        int width = video.path("width").asInt(0);
        int height = video.path("height").asInt(0);
        if (width < 16 || height < 16 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new RejectedMediaException("The picture size is not supported (16 to " + MAX_DIMENSION + " pixels).");
        }
        double seconds = root.path("format").path("duration").asString("").isEmpty() ? 0
                : parse(root.path("format").path("duration").asString(""));
        if (seconds <= 0) {
            seconds = video.path("duration").asString("").isEmpty() ? 0 : parse(video.path("duration").asString(""));
        }
        long millis = Math.round(seconds * 1000);
        if (millis < 1000) {
            throw new RejectedMediaException("The video must be at least one second long.");
        }
        if (millis > maxDurationMs) {
            throw new RejectedMediaException("The video is longer than " + (maxDurationMs / 1000) + " seconds.");
        }
        return new Probe((int) millis, width, height, audio);
    }

    /** Encodes one rendition as H.264/AAC MP4 (or AAC-only for the audio rendition) at {@code out}. */
    public void encode(Path in, Path out, Rendition rendition, Probe probe) throws IOException, RejectedMediaException {
        List<String> args = new ArrayList<>(List.of("-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist",
                "file", "-i", in.toString(), "-threads", String.valueOf(config.threads()), "-fs",
                String.valueOf(config.maxOutputBytes()), "-t", String.valueOf(probe.durationMs() / 1000.0 + 1)));
        if (rendition.heightPx() == null) {
            args.addAll(List.of("-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", rendition.maxBitrateKbps() + "k", "-ac", "1"));
        } else {
            int audioKbps = rendition.heightPx() <= 240 ? 32 : 64;
            int videoKbps = Math.max(40, rendition.maxBitrateKbps() - audioKbps);
            args.addAll(List.of("-map", "0:v:0"));
            if (probe.hasAudio()) {
                args.addAll(List.of("-map", "0:a:0", "-c:a", "aac", "-b:a", audioKbps + "k", "-ac", "1"));
            } else {
                args.add("-an");
            }
            args.addAll(List.of("-vf", "scale=-2:" + rendition.heightPx() + ",format=yuv420p", "-c:v", "libx264", "-preset",
                    "veryfast", "-crf", "28", "-maxrate", videoKbps + "k", "-bufsize", (2 * videoKbps) + "k"));
        }
        args.addAll(List.of("-movflags", "+faststart", "-f", "mp4", "-y", out.toString()));
        requireSuccess(run(config.path(), args, config.timeout().toMillis()), "encoding " + rendition.code());
    }

    public void thumbnail(Path in, Path out, Probe probe) throws IOException, RejectedMediaException {
        double at = Math.min(1.0, probe.durationMs() / 2000.0);
        requireSuccess(run(config.path(), List.of("-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist",
                "file", "-ss", String.valueOf(at), "-i", in.toString(), "-frames:v", "1", "-vf", "scale=-2:360", "-q:v", "4",
                "-f", "image2", "-y", out.toString()), 60_000), "making the thumbnail");
        // A thumbnail is a nicety: the caller treats a missing one as "no thumbnail".
    }

    private static void requireSuccess(Result result, String what) throws RejectedMediaException {
        if (result.exit() != 0) {
            log.info("ffmpeg failed while {}: {}", what, result.output().replace('\n', ' '));
            throw new RejectedMediaException("The video could not be processed.");
        }
    }

    private static double parse(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Result(int exit, String output) {
    }

    private Result run(String executable, List<String> args, long timeoutMillis) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        var env = builder.environment();
        String systemRoot = env.get("SystemRoot"); // some Windows builds need it to start
        env.clear();
        if (systemRoot != null) {
            env.put("SystemRoot", systemRoot);
        }
        Process process = builder.start();
        process.getOutputStream().close(); // no standard input
        var captured = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> capture(process.getInputStream(), captured));
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                log.warn("{} was killed after {} ms", executable, timeoutMillis);
                return new Result(124, "timed out");
            }
            reader.join(5_000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + executable, e);
        }
        synchronized (captured) {
            return new Result(process.exitValue(), captured.toString(StandardCharsets.UTF_8));
        }
    }

    /** Keeps the start of the output only, so a chatty or hostile stream cannot fill memory. */
    private static void capture(InputStream in, ByteArrayOutputStream sink) {
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                synchronized (sink) {
                    int room = CAPTURE_LIMIT - sink.size();
                    if (room > 0) {
                        sink.write(buffer, 0, Math.min(read, room));
                    }
                }
            }
        } catch (IOException ignored) {
            // The process ended; whatever was captured is what we have.
        }
    }
}
