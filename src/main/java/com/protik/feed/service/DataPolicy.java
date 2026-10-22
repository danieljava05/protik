package com.protik.feed.service;

/**
 * What the app should do about data right now (ADR 0018 decision 2). A pure function of the user's settings and
 * what they have used this period. It is advice: the server never blocks playback.
 *
 * @param remainingBytes         bytes left in the budget, or null if there is no cap
 * @param recommendedRendition   the highest quality the app should use, or null for "no restriction"
 * @param prefetchBudgetBytes    how much it may download ahead of time
 */
public record DataPolicy(State state, Long remainingBytes, String recommendedRendition, long prefetchBudgetBytes,
        boolean dataSaver) {

    public enum State {
        /** No cap set. */
        UNLIMITED,
        /** Under 90% of the cap. */
        OK,
        /** 90% or more of the cap used. */
        LOW,
        /** The cap is reached. */
        EXHAUSTED
    }

    static final String AUDIO = "audio";
    static final String LOWEST_VIDEO = "144p";

    /**
     * @param monthlyCapBytes the cap, or null for none
     * @param usedBytes       used so far this period (never negative)
     */
    public static DataPolicy compute(boolean dataSaver, Long monthlyCapBytes, long usedBytes, long prefetchCapBytes) {
        if (usedBytes < 0 || prefetchCapBytes < 0 || (monthlyCapBytes != null && monthlyCapBytes <= 0)) {
            throw new IllegalArgumentException("bytes must not be negative and a cap must be positive");
        }
        if (monthlyCapBytes == null) {
            return new DataPolicy(State.UNLIMITED, null, dataSaver ? LOWEST_VIDEO : null,
                    dataSaver ? 0 : prefetchCapBytes, dataSaver);
        }
        long remaining = Math.max(0, monthlyCapBytes - usedBytes);
        State state = usedBytes >= monthlyCapBytes ? State.EXHAUSTED
                : usedBytes >= monthlyCapBytes - monthlyCapBytes / 10 ? State.LOW : State.OK;
        String rendition = switch (state) {
            case EXHAUSTED -> AUDIO;
            case LOW -> LOWEST_VIDEO;
            default -> dataSaver ? LOWEST_VIDEO : null;
        };
        long prefetch = state == State.EXHAUSTED || dataSaver ? 0 : Math.min(prefetchCapBytes, remaining);
        return new DataPolicy(state, remaining, rendition, prefetch, dataSaver);
    }
}
