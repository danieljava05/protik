package com.protik.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The identity module's answer to "is there such a person?" */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountDirectoryTests {

    @Autowired
    AccountDirectory directory;
    @Autowired
    JdbcClient db;

    @AfterEach
    void clean() {
        db.sql("DELETE FROM identity.accounts WHERE username LIKE 'dir_%'").update();
    }

    private UUID account(String status) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO identity.accounts (id, username, status) VALUES (:id, :n, :s)")
                .param("id", id).param("n", "dir_" + id.toString().replace("-", "").substring(0, 12)).param("s", status).update();
        return id;
    }

    @Test
    void onlyActiveAccountsCount() {
        assertThat(directory.isActive(account("ACTIVE"))).isTrue();
        assertThat(directory.isActive(account("SUSPENDED"))).isFalse();
        assertThat(directory.isActive(account("DELETION_PENDING"))).isFalse();
        assertThat(directory.isActive(UUID.randomUUID())).isFalse();
    }
}
