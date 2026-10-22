package com.protik.identity.service;

import com.protik.identity.AccountDirectory;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcAccountDirectory implements AccountDirectory {

    private final JdbcClient db;

    JdbcAccountDirectory(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean isActive(UUID accountId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM identity.accounts WHERE id = :id AND status = 'ACTIVE')")
                .param("id", accountId).query(Boolean.class).single();
    }
}
