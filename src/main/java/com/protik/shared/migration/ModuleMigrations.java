package com.protik.shared.migration;

import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * Runs Flyway once per module, each against its own PostgreSQL schema with its
 * own history table (ADR 0005, ADR 0007). A module therefore owns and versions
 * its migrations independently, and no module's migration can silently touch
 * another module's schema.
 *
 * <p>Order matters only for {@code shared}, which installs the extensions and
 * helper functions other modules use, so it always runs first.
 *
 * <p>Spring Boot's single-instance Flyway auto-configuration is switched off
 * ({@code spring.flyway.enabled=false}); it cannot express per-module schemas.
 * Beans that need the schema (for example a future JPA EntityManagerFactory)
 * must declare {@code @DependsOn("moduleMigrations")}.
 */
public class ModuleMigrations implements InitializingBean {

    /** Migration order; must match the modules in the codebase (checked by a test). */
    public static final List<String> MODULES =
            List.of("shared", "identity", "video", "feed", "social", "engagement", "moderation");

    private static final Logger log = LoggerFactory.getLogger(ModuleMigrations.class);

    private final DataSource dataSource;

    public ModuleMigrations(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        for (String module : MODULES) {
            var result = Flyway.configure()
                    .dataSource(dataSource)
                    // First schema is the default (history table, unqualified objects).
                    // public stays on the search_path for citext/unaccent/pg_trgm.
                    .schemas(module, "public")
                    .defaultSchema(module)
                    .createSchemas(true)
                    .locations("classpath:db/migration/" + module)
                    .load()
                    .migrate();
            log.info("Module '{}': {} migration(s) applied, schema at version {}",
                    module, result.migrationsExecuted, result.targetSchemaVersion);
        }
    }
}
