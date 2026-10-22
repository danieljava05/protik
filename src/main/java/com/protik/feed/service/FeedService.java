package com.protik.feed.service;

import com.protik.feed.RankedItem;
import com.protik.feed.Ranker;
import com.protik.feed.RankingContext;
import com.protik.feed.persistence.FeedRepository;
import com.protik.feed.persistence.FeedRepository.PoolEntry;
import com.protik.feed.ranking.RankOrder;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.protik.video.Rendition;
import com.protik.video.RenditionCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a page of the viewer's feed (ADR 0015): take a bounded pool of recent candidates, rank
 * it with the viewer's own mode and weights, and return the next slice after their cursor.
 */
@Service
public class FeedService {

    /** One feed item: the ranker's decision plus the facts the client needs to show the video. */
    public record Entry(RankedItem ranked, PoolEntry video, boolean liked, boolean followingCreator) {
    }

    public record Page(Instant asOf, String mode, List<Entry> items, String nextCursor, DataPolicy dataPolicy,
            List<Rendition> renditions) {
    }

    private final FeedRepository repository;
    private final FeedPreferencesService preferences;
    private final Ranker ranker;
    private final FeedProperties props;
    private final DataService data;
    private final RenditionCatalog renditionCatalog;
    private final Clock clock;
    /** The ladder changes about never, so it is read at most once a minute instead of on every request. */
    private volatile CachedRenditions renditionCache = new CachedRenditions(Instant.EPOCH, List.of());

    private record CachedRenditions(Instant loadedAt, List<Rendition> renditions) {
    }

    FeedService(FeedRepository repository, FeedPreferencesService preferences, Ranker ranker, FeedProperties props,
            DataService data, RenditionCatalog renditionCatalog, Clock clock) {
        this.repository = repository;
        this.preferences = preferences;
        this.ranker = ranker;
        this.props = props;
        this.data = data;
        this.renditionCatalog = renditionCatalog;
        this.clock = clock;
    }

    private List<Rendition> renditions() {
        CachedRenditions cached = renditionCache;
        Instant now = clock.instant();
        if (cached.renditions().isEmpty() || cached.loadedAt().plusSeconds(60).isBefore(now)) {
            cached = new CachedRenditions(now, List.copyOf(renditionCatalog.all()));
            renditionCache = cached;
        }
        return cached.renditions();
    }

    /**
     * @param limit  items wanted (null = the default); must be within the configured maximum
     * @param cursor from the previous page's {@code nextCursor}, or null to start a new session
     */
    @Transactional(readOnly = true)
    public Page page(UUID userId, Integer limit, String cursor) {
        int size = limit == null ? props.defaultLimit() : limit;
        if (size < 1 || size > props.maxLimit()) {
            throw new ApiException(ProblemType.VALIDATION_FAILED,
                    "limit must be between 1 and " + props.maxLimit() + ".");
        }

        var prefs = preferences.preferences(userId);
        String settingsHash = fingerprint(prefs);
        FeedCursor position = cursor == null ? null : FeedCursor.decode(cursor);
        if (position != null && !position.settingsHash().equals(settingsHash)) {
            // The order is a function of the settings; resuming under different ones would repeat or skip videos.
            throw new ApiException(ProblemType.CONFLICT, "Your ranking settings changed. Reload the feed.");
        }
        Instant asOf = position == null ? FeedCursor.now() : position.asOf();

        List<PoolEntry> pool = repository.pool(userId, asOf, props.window(), props.poolSize(), prefs.followedOnly());
        Map<UUID, PoolEntry> byId = new HashMap<>();
        pool.forEach(p -> byId.put(p.candidate().videoId(), p));

        var context = new RankingContext(userId, asOf, prefs.effectiveWeights(), prefs.followedOnly(),
                repository.followedCreators(userId), repository.interests(userId));
        List<RankedItem> ranked = ranker.rank(context, pool.stream().map(PoolEntry::candidate).toList());

        List<RankedItem> chosen = new java.util.ArrayList<>(size);
        boolean more = false;
        for (RankedItem item : ranked) {
            PoolEntry video = byId.get(item.videoId());
            if (position != null && !sortsAfter(item, video, position)) {
                continue;
            }
            if (chosen.size() == size) {
                more = true;
                break;
            }
            chosen.add(item);
        }

        Set<UUID> liked = repository.likedAmong(userId, chosen.stream().map(RankedItem::videoId).toList());
        List<Entry> page = chosen.stream().map(item -> {
            PoolEntry video = byId.get(item.videoId());
            return new Entry(item, video, liked.contains(item.videoId()),
                    context.followedCreators().contains(video.candidate().creatorId()));
        }).toList();

        String next = null;
        if (more) {
            Entry last = page.getLast();
            next = new FeedCursor(asOf, settingsHash, last.ranked().score(), last.video().candidate().publishedAt(),
                    last.ranked().videoId()).encode();
        }
        return new Page(asOf, prefs.mode(), List.copyOf(page), next, data.policy(userId), renditions());
    }

    private static boolean sortsAfter(RankedItem item, PoolEntry video, FeedCursor cursor) {
        return RankOrder.compare(item.score(), video.candidate().publishedAt(), item.videoId(),
                cursor.score(), cursor.publishedAt(), cursor.videoId()) > 0;
    }

    /** A short fingerprint of everything the order depends on, so a cursor can tell the settings changed. */
    private static String fingerprint(FeedPreferencesService.Preferences prefs) {
        StringBuilder text = new StringBuilder(prefs.mode()).append('|').append(prefs.followedOnly());
        new TreeMap<>(prefs.effectiveWeights()).forEach((code, weight) -> text.append('|').append(code).append('=')
                .append(Double.doubleToLongBits(weight)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
