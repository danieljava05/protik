package com.protik.social.service;

import com.protik.identity.AccountDirectory;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.social.UserFollowed;
import com.protik.social.UserUnfollowed;
import com.protik.social.persistence.FollowRepository;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Following and unfollowing (ADR 0016). Both are idempotent; an event is published only on a real change. */
@Service
public class FollowService {

    private final FollowRepository follows;
    private final AccountDirectory accounts;
    private final ApplicationEventPublisher events;

    FollowService(FollowRepository follows, AccountDirectory accounts, ApplicationEventPublisher events) {
        this.follows = follows;
        this.accounts = accounts;
        this.events = events;
    }

    @Transactional
    public void follow(UUID followerId, UUID targetId) {
        if (followerId.equals(targetId)) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "You cannot follow yourself.");
        }
        if (!accounts.isActive(targetId)) {
            throw new ApiException(ProblemType.NOT_FOUND, "User not found.");
        }
        if (follows.insert(followerId, targetId)) {
            events.publishEvent(new UserFollowed(followerId, targetId));
        }
    }

    /** Unfollowing needs no existence check: removing a follow that is not there is simply a no-op. */
    @Transactional
    public void unfollow(UUID followerId, UUID targetId) {
        if (follows.delete(followerId, targetId)) {
            events.publishEvent(new UserUnfollowed(followerId, targetId));
        }
    }

    @Transactional(readOnly = true)
    public boolean isFollowing(UUID followerId, UUID targetId) {
        return follows.exists(followerId, targetId);
    }
}
