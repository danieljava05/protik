package com.protik.moderation.service;

import com.protik.identity.DeletionAcknowledgements;
import com.protik.identity.UserDeletionRequested;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A deleted user's reports stay, because they concern other people's content, but lose their words and their
 * link to the person (ADR 0020). The append-only audit log keeps only pseudonymous IDs and is not touched.
 */
@Component
class ModerationAccountDeletion {

    private final JdbcClient db;
    private final DeletionAcknowledgements acknowledgements;

    ModerationAccountDeletion(JdbcClient db, DeletionAcknowledgements acknowledgements) {
        this.db = db;
        this.acknowledgements = acknowledgements;
    }

    @ApplicationModuleListener
    void on(UserDeletionRequested event) {
        // A fresh random ID per report, so reports cannot be linked to each other either.
        db.sql("UPDATE moderation.reports SET reporter_id = gen_random_uuid(), details = '' WHERE reporter_id = :u")
                .param("u", event.userId()).update();
        acknowledgements.acknowledge(event.requestId(), "moderation");
    }
}
