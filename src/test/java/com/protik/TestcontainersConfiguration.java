package com.protik;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database for tests: always a real PostgreSQL, never an in-memory substitute
 * (the schema relies on PostgreSQL-specific features). The version matches
 * docker-compose.yml.
 *
 * <p>Default: a throwaway container per test context (Testcontainers). Escape hatch for
 * machines where Docker is unavailable: set the environment variable
 * {@code PROTIK_TEST_DB_URL} (plus {@code PROTIK_TEST_DB_USER} and
 * {@code PROTIK_TEST_DB_PASSWORD} if needed) to an <em>empty, disposable</em> PostgreSQL 17
 * database. Tests create and delete data in it, so never point this at a database you care
 * about. CI always uses containers.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Skipped when PROTIK_TEST_DB_URL is set (the value never equals havingValue). */
    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "protik.test.db.url", havingValue = "<container-mode>", matchIfMissing = true)
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17");
    }

    @Bean
    @ConditionalOnProperty(name = "protik.test.db.url")
    DynamicPropertyRegistrar externalDatabase(Environment env) {
        return registry -> {
            registry.add("spring.datasource.url", () -> env.getRequiredProperty("protik.test.db.url"));
            registry.add("spring.datasource.username", () -> env.getProperty("protik.test.db.user", "protik_test"));
            registry.add("spring.datasource.password", () -> env.getProperty("protik.test.db.password", ""));
        };
    }
}
