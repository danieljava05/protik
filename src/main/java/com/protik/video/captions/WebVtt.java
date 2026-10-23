package com.protik.video.captions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes WebVTT (ADR 0023). The reader is strict about what matters (the header, the timing line, limits) and
 * ignores what a transcript does not need (cue settings, styles, regions, notes). Text is stored as plain text: inline
 * tags such as {@code <b>} or {@code <v Name>} are removed, and the writer escapes what must be escaped, so a caption can
 * never carry markup into a page that displays it.
 */
public final class WebVtt {

    /** The text is not usable. The message is safe to show to the person who sent it. */
    public static class InvalidCaptionsException extends Exception {
        public InvalidCaptionsException(String message) {
            super(message);
        }
    }

    public static final int MAX_CUES = 5000;
    public static final int MAX_CUE_CHARS = 500;
    public static final int MAX_BYTES = 1_000_000;

    private static final Pattern TIME = Pattern.compile("(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})\\.(\\d{3})");
    private static final Pattern TIMING = Pattern.compile("^(\\S+)\\s+-->\\s+(\\S+)(?:\\s.*)?$");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private WebVtt() {
    }

    /** @param durationMs the video's length if known, or null; a cue may start no later than one second after the end */
    public static List<Cue> parse(String text, Integer durationMs) throws InvalidCaptionsException {
        if (text == null) {
            throw new InvalidCaptionsException("The captions are empty.");
        }
        String normalised = text.startsWith("﻿") ? text.substring(1) : text;
        normalised = normalised.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalised.split("\n", -1);
        if (lines.length == 0 || !(lines[0].equals("WEBVTT") || lines[0].startsWith("WEBVTT ") || lines[0].startsWith("WEBVTT\t"))) {
            throw new InvalidCaptionsException("The file must start with WEBVTT.");
        }
        List<Cue> cues = new ArrayList<>();
        int i = 1;
        while (i < lines.length) {
            if (lines[i].isBlank()) {
                i++;
                continue;
            }
            // A block runs until the next blank line.
            int start = i;
            while (i < lines.length && !lines[i].isBlank()) {
                i++;
            }
            List<String> block = List.of(lines).subList(start, i);
            String first = block.getFirst();
            if (first.equals("NOTE") || first.startsWith("NOTE ") || first.startsWith("NOTE\t") || first.equals("STYLE")
                    || first.equals("REGION")) {
                continue; // comments, styles and regions are not transcript
            }
            int timingAt = -1;
            for (int j = 0; j < Math.min(2, block.size()); j++) {
                if (block.get(j).contains("-->")) {
                    timingAt = j;
                    break;
                }
            }
            if (timingAt < 0) {
                continue; // NOTE, STYLE, REGION or an identifier-only block
            }
            Matcher timing = TIMING.matcher(block.get(timingAt).strip());
            if (!timing.matches()) {
                throw new InvalidCaptionsException("A timing line is not valid: " + shorten(block.get(timingAt)));
            }
            int from = time(timing.group(1), block.get(timingAt));
            int to = time(timing.group(2), block.get(timingAt));
            if (to <= from) {
                throw new InvalidCaptionsException("A cue ends before it starts: " + shorten(block.get(timingAt)));
            }
            if (durationMs != null && from > durationMs + 1000) {
                throw new InvalidCaptionsException("A cue starts after the end of the video: " + shorten(block.get(timingAt)));
            }
            StringBuilder body = new StringBuilder();
            for (int j = timingAt + 1; j < block.size(); j++) {
                if (body.length() > 0) {
                    body.append(' ');
                }
                body.append(block.get(j).strip());
            }
            String plain = decode(TAG.matcher(body).replaceAll("")).strip().replaceAll("\\s+", " ");
            if (plain.isEmpty()) {
                continue;
            }
            if (plain.length() > MAX_CUE_CHARS) {
                throw new InvalidCaptionsException("A cue is longer than " + MAX_CUE_CHARS + " characters.");
            }
            if (plain.indexOf('\u0000') >= 0) {
                throw new InvalidCaptionsException("The captions contain an invalid character.");
            }
            cues.add(new Cue(from, to, plain));
            if (cues.size() > MAX_CUES) {
                throw new InvalidCaptionsException("There are more than " + MAX_CUES + " cues.");
            }
        }
        if (cues.isEmpty()) {
            throw new InvalidCaptionsException("The captions contain no cues.");
        }
        return cues;
    }

    public static String write(List<Cue> cues) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        int n = 1;
        for (Cue cue : cues) {
            out.append(n++).append('\n').append(format(cue.startMs())).append(" --> ").append(format(cue.endMs())).append('\n')
                    .append(cue.text().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("\n\n");
        }
        return out.toString();
    }

    static String format(int millis) {
        return String.format("%02d:%02d:%02d.%03d", millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000);
    }

    private static int time(String text, String line) throws InvalidCaptionsException {
        Matcher m = TIME.matcher(text);
        if (!m.matches()) {
            throw new InvalidCaptionsException("A time is not valid: " + shorten(line));
        }
        long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long minutes = Long.parseLong(m.group(2));
        long seconds = Long.parseLong(m.group(3));
        if (minutes > 59 || seconds > 59) {
            throw new InvalidCaptionsException("A time is not valid: " + shorten(line));
        }
        long total = ((hours * 60 + minutes) * 60 + seconds) * 1000 + Long.parseLong(m.group(4));
        if (total > Integer.MAX_VALUE) {
            throw new InvalidCaptionsException("A time is too large: " + shorten(line));
        }
        return (int) total;
    }

    private static String decode(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static String shorten(String line) {
        String clean = line.strip();
        return clean.length() > 60 ? clean.substring(0, 60) + "..." : clean;
    }
}
