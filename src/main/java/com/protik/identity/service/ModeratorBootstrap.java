package com.protik.identity.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Creates the first moderators (ADR 0019 decision 1). There is deliberately no API that grants roles, so the operator
 * names existing accounts in {@code protik.bootstrap.moderators} (comma separated usernames) and restarts the application.
 * Each grant is logged. Unknown usernames are reported, never created. Granting twice changes nothing. Removing a name from
 * the setting does <em>not</em> revoke the role: revoking is a deliberate database operation.
 */
@Component
class ModeratorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModeratorBootstrap.class);

    private final JdbcClient db;
    private final List<String> usernames;

    ModeratorBootstrap(JdbcClient db, @Value("${protik.bootstrap.moderators:}") List<String> usernames) {
        this.db = db;
        this.usernames = usernames.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        grant();
    }

    /** @return how many roles were newly granted */
    int grant() {
        int granted = 0;
        for (String username : usernames) {
            var id = db.sql("SELECT id FROM identity.accounts WHERE username = CAST(:u AS citext) AND status = 'ACTIVE'")
                    .param("u", username).query(java.util.UUID.class).optional();
            if (id.isEmpty()) {
                log.warn("protik.bootstrap.moderators names '{}', but there is no active account with that username", username);
                continue;
            }
            int added = db.sql("INSERT INTO identity.account_roles (account_id, role) VALUES (:id, 'MODERATOR') ON CONFLICT DO NOTHING")
                    .param("id", id.get()).update();
            if (added == 1) {
                granted++;
                log.warn("MODERATOR role granted to account {} ('{}') by protik.bootstrap.moderators", id.get(), username);
            }
        }
        return granted;
    }
}
