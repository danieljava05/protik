package com.protik.feed.service;

import com.protik.feed.persistence.FeedRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the feed's read models current from other modules' events (ADR 0005 decision 4). Every
 * method is idempotent, because events are delivered at least once (ADR 0002).
 */
@Service
public class FeedIndexer {

    static final int MAX_TAGS = 20;
    private static final Pattern VALID_TAG = Pattern.compile("[^\\s#]{1,60}");

    private final FeedRepository repository;

    FeedIndexer(FeedRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void videoPublished(UUID videoId, UUID creatorId, String language, Instant publishedAt, int durationMs,
            Collection<String> tags) {
        repository.upsertCandidate(videoId, creatorId, language, publishedAt, durationMs);
        repository.replaceTags(videoId, normalizeTags(tags));
    }

    @Transactional
    public void videoRemoved(UUID videoId) {
        repository.deleteCandidate(videoId);
    }

    /** Counters as of now. */
    @Transactional
    public void statsChanged(UUID videoId, long likes, long comments, long views) {
        statsChanged(videoId, likes, comments, views, Instant.now());
    }

    /** Counters as of {@code computedAt}; ignored if the feed already has newer ones. */
    @Transactional
    public void statsChanged(UUID videoId, long likes, long comments, long views, Instant computedAt) {
        repository.setStats(videoId, Math.max(0, likes), Math.max(0, comments), Math.max(0, views), computedAt);
    }

    @Transactional
    public void liked(UUID userId, UUID videoId) {
        repository.addLike(userId, videoId);
    }

    @Transactional
    public void unliked(UUID userId, UUID videoId) {
        repository.removeLike(userId, videoId);
    }

    @Transactional
    public void followed(UUID userId, UUID creatorId) {
        if (!userId.equals(creatorId)) {
            repository.addFollow(userId, creatorId);
        }
    }

    @Transactional
    public void unfollowed(UUID userId, UUID creatorId) {
        repository.removeFollow(userId, creatorId);
    }

    @Transactional
    public void interestsChanged(UUID userId, Collection<String> tags) {
        repository.replaceInterests(userId, normalizeTags(tags));
    }

    /** Lower-case, without a leading '#', no blanks or duplicates, at most 20, silently dropping malformed ones. */
    static Set<String> normalizeTags(Collection<String> tags) {
        Set<String> result = new LinkedHashSet<>();
        for (String raw : tags) {
            if (raw == null) {
                continue;
            }
            String tag = raw.strip().replaceFirst("^#+", "").toLowerCase(Locale.ROOT);
            if (VALID_TAG.matcher(tag).matches()) {
                result.add(tag);
            }
            if (result.size() == MAX_TAGS) {
                break;
            }
        }
        return result;
    }
}
