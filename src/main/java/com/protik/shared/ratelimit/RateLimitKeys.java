package com.protik.shared.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Builds rate-limit keys as SHA-256 digests, so the counters table never holds an
 * email address or an IP address (data minimisation under the NDPA). The parts are
 * length-prefixed so that ("a", "bc") and ("ab", "c") cannot collide.
 */
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    public static String of(String scope, String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, scope);
            for (String part : parts) {
                update(digest, part.strip().toLowerCase(Locale.ROOT));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
