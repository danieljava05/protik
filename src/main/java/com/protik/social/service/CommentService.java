package com.protik.social.service;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.error.RateLimitedException;
import com.protik.shared.id.Ids;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import com.protik.social.CommentCountChanged;
import com.protik.social.persistence.CommentRepository;
import com.protik.social.persistence.CommentRepository.Listed;
import com.protik.social.persistence.CommentRepository.Stored;
import com.protik.video.VideoCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writing, reading and deleting comments (ADR 0017). */
@Service
public class CommentService {

    public record Page(List<Listed> items, String nextCursor) {
    }

    private final CommentRepository comments;
    private final VideoCatalog videos;
    private final RateLimiter limiter;
    private final SocialProperties props;
    private final ApplicationEventPublisher events;

    CommentService(CommentRepository comments, VideoCatalog videos, RateLimiter limiter, SocialProperties props,
            ApplicationEventPublisher events) {
        this.comments = comments;
        this.videos = videos;
        this.limiter = limiter;
        this.props = props;
        this.events = events;
    }

    /**
     * Counts a posting attempt against the user's limit. Call it <em>before</em> opening the transaction that
     * posts: the limiter commits on its own, and calling it from inside another transaction would hold two
     * database connections per request.
     *
     * @throws RateLimitedException if the user is posting too fast
     */
    public void beginPosting(UUID userId) {
        limiter.acquire(List.of(new RateLimiter.Limit(RateLimitKeys.of("comment-user", userId.toString()),
                        props.commentsPerWindow(), props.commentWindow())))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
    }

    @Transactional
    public Stored post(UUID userId, UUID videoId, String rawBody, UUID parentId) {
        String body = CommentText.normalize(rawBody);
        requirePublished(videoId);
        if (parentId != null) {
            Stored parent = comments.find(parentId)
                    .orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "The comment you are replying to was not found."));
            if (!parent.videoId().equals(videoId)) {
                throw new ApiException(ProblemType.VALIDATION_FAILED, "That comment belongs to a different video.");
            }
            if (parent.parentId() != null) {
                throw new ApiException(ProblemType.VALIDATION_FAILED, "You can reply to a comment, but not to a reply.");
            }
        }
        Stored saved = comments.insert(Ids.newId(), videoId, userId, parentId, body);
        events.publishEvent(new CommentCountChanged(videoId));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page topLevel(UUID videoId, Integer limit, String cursor) {
        requirePublished(videoId);
        int size = pageSize(limit);
        CommentCursor after = cursor == null ? null : CommentCursor.decode(cursor);
        List<Listed> rows = comments.topLevel(videoId, size + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());
        return page(rows, size);
    }

    @Transactional(readOnly = true)
    public Page replies(UUID commentId, Integer limit, String cursor) {
        Stored parent = comments.find(commentId)
                .orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Comment not found."));
        requirePublished(parent.videoId());
        int size = pageSize(limit);
        CommentCursor after = cursor == null ? null : CommentCursor.decode(cursor);
        List<Listed> rows = comments.replies(commentId, size + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());
        return page(rows, size);
    }

    /** Only the author can delete (moderators arrive with T5.2). Replies to the comment are deleted with it. */
    @Transactional
    public void delete(UUID userId, UUID commentId) {
        Stored comment = comments.find(commentId)
                .orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Comment not found."));
        if (!comment.authorId().equals(userId)) {
            throw new ApiException(ProblemType.FORBIDDEN, "You can only delete your own comments.");
        }
        comments.delete(commentId);
        events.publishEvent(new CommentCountChanged(comment.videoId()));
    }

    private void requirePublished(UUID videoId) {
        if (!videos.isPublished(videoId)) {
            throw new ApiException(ProblemType.NOT_FOUND, "Video not found.");
        }
    }

    private int pageSize(Integer limit) {
        int size = limit == null ? props.defaultPageSize() : limit;
        if (size < 1 || size > props.maxPageSize()) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "limit must be between 1 and " + props.maxPageSize() + ".");
        }
        return size;
    }

    /** The query asked for one extra row; its presence means there is a next page. */
    private static Page page(List<Listed> rows, int size) {
        if (rows.size() <= size) {
            return new Page(rows, null);
        }
        List<Listed> items = rows.subList(0, size);
        Listed last = items.getLast();
        return new Page(List.copyOf(items), new CommentCursor(last.createdAt(), last.id()).encode());
    }
}
