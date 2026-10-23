package com.protik.identity.service;

import com.protik.identity.AccountAdministration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcAccountAdministration implements AccountAdministration {

    private final JdbcClient db;

    JdbcAccountAdministration(JdbcClient db) {
        this.db = db;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean suspend(UUID accountId) {
        int changed = db.sql("UPDATE identity.accounts SET status = 'SUSPENDED' WHERE id = :id AND status = 'ACTIVE'")
                .param("id", accountId).update();
        if (changed == 0) {
            return false;
        }
        db.sql("UPDATE identity.session_families SET revoked_at = now(), revoked_reason = 'ADMIN' "
                + "WHERE account_id = :id AND revoked_at IS NULL").param("id", accountId).update();
        return true;
    }

    @Override
    public boolean exists(UUID accountId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM identity.accounts WHERE id = :id)")
                .param("id", accountId).query(Boolean.class).single();
    }
}
