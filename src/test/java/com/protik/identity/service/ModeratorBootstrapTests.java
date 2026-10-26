package com.protik.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ADR 0019: the first moderator is named in configuration, since no API grants roles. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ModeratorBootstrapTests {

    @Autowired
    JdbcClient db;

    private UUID account(String username, String status) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO identity.accounts (id, username, status) VALUES (:id, :u, :s)").param("id", id)
                .param("u", username).param("s", status).update();
        return id;
    }

    private long roles(UUID id) {
        return db.sql("SELECT count(*) FROM identity.account_roles WHERE account_id = :id AND role = 'MODERATOR'")
                .param("id", id).query(Long.class).single();
    }

    @Test
    void namedActiveAccountsBecomeModeratorsOnceAndUnknownOrInactiveOnesDoNot() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID active = account("boot_a_" + suffix, "ACTIVE");
        UUID suspended = account("boot_s_" + suffix, "SUSPENDED");
        var bootstrap = new ModeratorBootstrap(db, List.of(" BOOT_A_" + suffix + " ", "boot_s_" + suffix, "nobody_" + suffix, ""));

        assertThat(bootstrap.grant()).isEqualTo(1); // the name is case-insensitive and trimmed
        assertThat(roles(active)).isEqualTo(1);
        assertThat(roles(suspended)).isZero();
        assertThat(db.sql("SELECT count(*) FROM identity.accounts WHERE username = :u").param("u", "nobody_" + suffix)
                .query(Long.class).single()).as("unknown names are never created").isZero();

        assertThat(bootstrap.grant()).as("granting again changes nothing").isZero();
        assertThat(roles(active)).isEqualTo(1);
    }

    @Test
    void anEmptySettingGrantsNothing() {
        assertThat(new ModeratorBootstrap(db, List.of()).grant()).isZero();
    }
}
