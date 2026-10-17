package com.protik.shared.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates UUIDv7 identifiers (RFC 9562): a 48-bit millisecond timestamp
 * followed by random bits. Time ordering keeps B-tree inserts mostly
 * sequential; the random part keeps identifiers unguessable (ADR 0005).
 *
 * <p>Java's {@link UUID#randomUUID()} produces version 4, which is not
 * time-ordered, hence this class. The timestamp is visible in the ID.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        return newId(System.currentTimeMillis());
    }

    /** Visible for tests: build an ID for a given timestamp (milliseconds since epoch). */
    static UUID newId(long epochMillis) {
        long msb = (epochMillis << 16)                       // 48-bit timestamp
                | 0x7000L                                    // version 7
                | (RANDOM.nextLong() & 0x0FFFL);             // 12 random bits
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) // 62 random bits
                | 0x8000000000000000L;                       // variant 10
        return new UUID(msb, lsb);
    }
}
