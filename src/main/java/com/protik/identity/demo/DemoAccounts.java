package com.protik.identity.demo;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Sample creators so that following someone can be tried before uploads exist.
 *
 * <p><strong>Off by default.</strong> They exist only when the application is started with
 * {@code protik.demo.seed=true}. They have <em>no password</em>, so nobody can log in as them; they are
 * only people who can be followed. IDs come from fixed names, so repeating the seed adds nothing.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "protik.demo.seed", havingValue = "true")
class DemoAccounts implements ApplicationRunner {

    static final int CREATORS = 8;

    private static final Logger log = LoggerFactory.getLogger(DemoAccounts.class);

    private final JdbcClient db;

    DemoAccounts(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    void seed() {
        for (int i = 0; i < CREATORS; i++) {
            UUID id = UUID.nameUUIDFromBytes(("demo-creator-" + i).getBytes(StandardCharsets.UTF_8));
            db.sql("INSERT INTO identity.accounts (id, username) VALUES (:id, :name) ON CONFLICT (id) DO NOTHING")
                    .param("id", id).param("name", "demo_creator_" + i).update();
            db.sql("INSERT INTO identity.profiles (account_id, display_name) VALUES (:id, :name) ON CONFLICT DO NOTHING")
                    .param("id", id).param("name", "Demo Creator " + i).update();
        }
        log.warn("Demo data is ON (protik.demo.seed=true): {} sample creators exist. They cannot log in. "
                + "Never enable this in production.", CREATORS);
    }
}
