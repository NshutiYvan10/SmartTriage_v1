package com.smartTriage.smartTriage_server;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests (hardening sprint).
 *
 * <p>Boots the FULL application context against a disposable PostgreSQL
 * container: Flyway migrates the schema from scratch (so every
 * migration V1…Vn is validated on every run), Hibernate validates the
 * mappings against the real dialect, and tests exercise real queries,
 * transactions, and constraint behaviour — the failure class unit
 * tests cannot catch (the V67→V68 column-type bug was exactly this).
 *
 * <p>{@code disabledWithoutDocker = true}: on machines without Docker
 * (e.g. a dev laptop) these tests are SKIPPED and {@code mvn test}
 * stays green; CI runs them for real. Crucially, this also means no
 * test ever touches a developer's live database.
 *
 * <p>postgres:14-alpine matches the production major version seen in
 * dev (PostgreSQL 14.x) so dialect behaviour lines up.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:14-alpine");
}
