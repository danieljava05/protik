package com.protik.identity.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sample creators exist only behind the switch, and nobody can log in as them. */
@SpringBootTest(properties = "protik.demo.seed=true")
@Import(TestcontainersConfiguration.class)
class DemoAccountsTests {

    @Autowired
    DemoAccounts demoAccounts;
    @Autowired
    JdbcClient db;

    @Test
    void sampleCreatorsAreActiveAccountsWithoutAnyPasswordAndRepeatingAddsNoMore() {
        demoAccounts.seed();
        demoAccounts.seed();
        assertThat(db.sql("SELECT count(*) FROM identity.accounts WHERE username LIKE 'demo_creator_%'")
                .query(Long.class).single()).isEqualTo(DemoAccounts.CREATORS);
        assertThat(db.sql("SELECT count(*) FROM identity.accounts a JOIN identity.password_credentials p "
                + "ON p.account_id = a.id WHERE a.username LIKE 'demo_creator_%'").query(Long.class).single())
                .as("no password means nobody can log in as a sample creator").isZero();
        assertThat(db.sql("SELECT count(*) FROM identity.accounts a JOIN identity.login_identifiers l "
                + "ON l.account_id = a.id WHERE a.username LIKE 'demo_creator_%'").query(Long.class).single())
                .as("and no email login either").isZero();
        assertThat(db.sql("SELECT count(*) FROM identity.accounts WHERE username LIKE 'demo_creator_%' AND status = 'ACTIVE'")
                .query(Long.class).single()).isEqualTo(DemoAccounts.CREATORS);
    }
}
