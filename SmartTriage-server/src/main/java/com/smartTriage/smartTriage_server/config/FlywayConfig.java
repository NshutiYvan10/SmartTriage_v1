package com.smartTriage.smartTriage_server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Auto-repair Flyway's schema_history before every migration run.
 *
 * <p>Why: in development, migration files occasionally drift in their
 * raw bytes after they have been applied to the database — a trailing
 * whitespace cleanup, a comment edit, an IDE-side line-ending
 * normalisation, etc. Flyway computes checksums on file <em>bytes</em>,
 * not on SQL semantics, so any byte-level difference between the
 * applied file and the current file makes Flyway refuse to start with:
 *
 * <pre>
 *   Migration checksum mismatch for migration version N
 *   -> Applied to database : ...
 *   -> Resolved locally    : ...
 * </pre>
 *
 * <p>The recommended remedy is {@code flyway repair}, which only
 * realigns the recorded checksums to the current file content — it
 * does NOT re-run any successful migration and does NOT touch the
 * actual schema. Spring Boot's
 * {@code spring.flyway.repair-on-migrate} property exposes this in
 * later versions, but the property is unreliable across the
 * Spring Boot 4 / Flyway 11 combination this project pins to. Wiring
 * a custom {@link FlywayMigrationStrategy} bean is the version-stable
 * way to do the same thing.
 *
 * <p>This is gated to the {@code dev} and {@code default} profiles —
 * production deployments should NOT silently realign checksums; they
 * should fail loud and require an intentional repair commit. If
 * production is ever set up to run with no profile, it will pick up
 * the default profile and this will activate. To disable for a
 * production deployment set {@code spring.profiles.active=prod}.
 */
@Slf4j
@Configuration
@Profile({"dev", "default"})
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy repairBeforeMigrate() {
        return flyway -> {
            log.info("[flyway] Running repair() before migrate() to absorb any "
                    + "checksum drift from edits to applied migration files (dev profile only)");
            flyway.repair();
            flyway.migrate();
        };
    }
}
