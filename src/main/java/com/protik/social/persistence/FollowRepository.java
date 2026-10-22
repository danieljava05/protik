package com.protik.social.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code social.follows} (ADR 0010). Callers own the transaction. */
@Repository
public class FollowRepository {

    private final JdbcClient db;

    FollowRepository(JdbcClient db) {
        this.db = db;
    }

    /** @return true if the follow is new (false if it already existed) */
    public boolean insert(UUID followerId, UUID followeeId) {
        return db.sql("INSERT INTO social.follows (follower_id, followee_id) VALUES (:f, :t) ON CONFLICT DO NOTHING")
                .param("f", followerId).param("t", followeeId).update() == 1;
    }

    /** @return true if a follow was removed (false if there was none) */
    public boolean delete(UUID followerId, UUID followeeId) {
        return db.sql("DELETE FROM social.follows WHERE follower_id = :f AND followee_id = :t")
                .param("f", followerId).param("t", followeeId).update() == 1;
    }

    public boolean exists(UUID followerId, UUID followeeId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM social.follows WHERE follower_id = :f AND followee_id = :t)")
                .param("f", followerId).param("t", followeeId).query(Boolean.class).single();
    }
}
