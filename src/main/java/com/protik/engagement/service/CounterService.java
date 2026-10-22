package com.protik.engagement.service;

import com.protik.engagement.VideoStatsChanged;
import com.protik.engagement.persistence.EngagementRepository;
import com.protik.social.CommentCounts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps video counters equal to the truth (ADR 0016 decision 3): recount each dirty video from the like
 * rows, store the counter, announce it. Everything for a batch happens in one transaction, so a crash
 * leaves the videos dirty and the work is simply done again.
 */
@Service
public class CounterService {

    private static final Logger log = LoggerFactory.getLogger(CounterService.class);

    private final EngagementRepository repository;
    private final EngagementProperties props;
    private final ApplicationEventPublisher events;
    private final JdbcClient db;
    private final CommentCounts commentCounts;

    CounterService(EngagementRepository repository, EngagementProperties props, ApplicationEventPublisher events,
            JdbcClient db, CommentCounts commentCounts) {
        this.repository = repository;
        this.props = props;
        this.events = events;
        this.db = db;
        this.commentCounts = commentCounts;
    }

    /** @return how many videos were recounted */
    @Transactional
    public int refresh() {
        List<UUID> videos = repository.claimDirty(props.refreshBatch());
        if (videos.isEmpty()) {
            return 0;
        }
        Instant computedAt = databaseNow();
        // Comments belong to the social module; one grouped question covers the whole batch.
        var comments = commentCounts.countVisible(videos);
        for (UUID videoId : videos) {
            repository.saveCounts(videoId, repository.countLikes(videoId), comments.getOrDefault(videoId, 0L));
            var counters = repository.counters(videoId);
            events.publishEvent(new VideoStatsChanged(videoId, counters.likes(), counters.comments(), counters.views(),
                    computedAt));
        }
        return videos.size();
    }

    /**
     * Recomputes every counter from the truth. Normally a no-op; its job is to repair unexpected drift,
     * and every corrected video is announced so the feed's copy is repaired too.
     */
    @Transactional
    public int reconcileAll() {
        java.util.Set<UUID> changed = new java.util.LinkedHashSet<>(repository.reconcileLikes());
        changed.addAll(reconcileComments());
        if (!changed.isEmpty()) {
            log.warn("Reconciliation corrected {} drifted counter(s)", changed.size());
            Instant computedAt = databaseNow();
            for (UUID videoId : changed) {
                var counters = repository.counters(videoId);
                events.publishEvent(new VideoStatsChanged(videoId, counters.likes(), counters.comments(),
                        counters.views(), computedAt));
            }
        }
        return changed.size();
    }

    /** Compares every stored comment counter with the social module's truth; returns the videos corrected. */
    private List<UUID> reconcileComments() {
        List<UUID> corrected = new java.util.ArrayList<>();
        UUID after = new UUID(0L, 0L);
        while (true) {
            List<UUID> page = repository.counterVideosAfter(after, 500);
            if (page.isEmpty()) {
                return corrected;
            }
            var truth = commentCounts.countVisible(page);
            for (UUID videoId : page) {
                long actual = truth.getOrDefault(videoId, 0L);
                if (repository.counters(videoId).comments() != actual) {
                    repository.setComments(videoId, actual);
                    corrected.add(videoId);
                }
            }
            after = page.getLast();
        }
    }

    private Instant databaseNow() {
        return db.sql("SELECT clock_timestamp()").query(java.time.OffsetDateTime.class).single().toInstant();
    }
}
