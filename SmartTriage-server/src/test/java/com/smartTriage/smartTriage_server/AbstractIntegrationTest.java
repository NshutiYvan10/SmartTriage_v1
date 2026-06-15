package com.smartTriage.smartTriage_server;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
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
 * <p><b>Singleton container, NOT {@code @Container}.</b> Spring caches one
 * application context across every test class sharing this configuration.
 * A per-class {@code @Container} field — which Testcontainers starts and
 * stops around each class — would tear the database down while that
 * cached context (and its running {@code @Scheduled} monitors) still
 * pointed at it, so the SECOND integration class hit "connection refused"
 * (the CI failure on localhost:&lt;mapped-port&gt;). Instead the container is
 * started ONCE in a static initializer and lives for the whole test JVM;
 * Testcontainers' Ryuk reaper removes it when the JVM exits. The shared
 * context's connection therefore stays valid across all classes.
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} still disables
 * these classes on a machine without Docker (a dev laptop), so
 * {@code mvn test} stays green locally and no test ever touches a
 * developer's live database; CI runs them for real. The static-initializer
 * start is guarded by a Docker-availability check so it no-ops (rather
 * than throwing) on those machines before the disabled-condition skips
 * the class.
 *
 * <p>postgres:14-alpine matches the production major version seen in
 * dev (PostgreSQL 14.x) so dialect behaviour lines up.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:14-alpine");

    static {
        // Guard: on a Docker-less machine isDockerAvailable() returns false
        // (it does not throw), so we skip start() and let
        // disabledWithoutDocker skip the class. With Docker, start once for
        // the whole JVM — never stopped per-class.
        if (DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
        }
    }

    /**
     * Point the application datasource (and therefore Flyway) at the
     * singleton container. {@code @DynamicPropertySource} has the highest
     * precedence in the Environment, so it overrides the committed
     * {@code application*.properties} datasource even under the active
     * "dev" profile.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
