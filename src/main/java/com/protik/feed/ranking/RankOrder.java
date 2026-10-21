package com.protik.feed.ranking;

import java.time.Instant;
import java.util.UUID;

/**
 * The one definition of feed order: higher score first, then newer, then larger video ID.
 * The ranker sorts with it and the feed's pagination uses it to find "what comes after this
 * cursor", so the two can never disagree.
 *
 * <p>IDs compare as unsigned bytes, which is how PostgreSQL orders UUIDs (java.util.UUID's own
 * compareTo is signed). Comparing the two longs also avoids allocating strings.
 */
public final class RankOrder {

    private RankOrder() {
    }

    /** @return negative if A is listed before B, positive if after, 0 only for identical keys */
    public static int compare(double scoreA, Instant publishedA, UUID idA,
            double scoreB, Instant publishedB, UUID idB) {
        int byScore = Double.compare(scoreB, scoreA); // higher score first
        if (byScore != 0) {
            return byScore;
        }
        int byAge = publishedB.compareTo(publishedA); // newer first
        if (byAge != 0) {
            return byAge;
        }
        return compareUnsigned(idB, idA); // larger ID first
    }

    public static int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
