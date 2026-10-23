package com.protik.video.processing;

import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stand-in used when {@code protik.scan.mode=disabled} (development only, ADR 0022). It checks nothing, says so, and
 * {@link #scans()} is false so the video is recorded as SKIPPED and never as clean.
 */
public class UnscannedScanner implements MalwareScanner {

    private static final Logger log = LoggerFactory.getLogger(UnscannedScanner.class);

    @Override
    public boolean scans() {
        return false;
    }

    @Override
    public Result scan(InputStream content) {
        log.warn("UPLOAD NOT SCANNED FOR MALWARE: protik.scan.mode=disabled. Never use this in production.");
        return Result.ok();
    }
}
