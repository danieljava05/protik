package com.protik.video.service;

import java.util.Locale;

/**
 * Recognises a video container from its first bytes (ADR 0022 decision 2). Only the signature is checked; the real
 * validation is ffprobe and ffmpeg later. A name or a declared type is never trusted.
 */
final class ContainerSniffer {

    enum Container {
        /** MP4, MOV and friends (ISO base media file format). */
        ISO_BMFF,
        /** WebM and Matroska. */
        MATROSKA
    }

    static final int NEEDED = 12;

    private ContainerSniffer() {
    }

    /** @return the container, or null if the bytes are not a supported one */
    static Container sniff(byte[] head, int length) {
        if (length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return Container.ISO_BMFF;
        }
        if (length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF
                && (head[3] & 0xFF) == 0xA3) {
            return Container.MATROSKA;
        }
        return null;
    }

    /** @return the container a declared media type promises, or null if the type is not accepted at all */
    static Container declared(String contentType) {
        if (contentType == null) {
            return null;
        }
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "video/mp4", "video/quicktime" -> Container.ISO_BMFF;
            case "video/webm" -> Container.MATROSKA;
            default -> null;
        };
    }
}
