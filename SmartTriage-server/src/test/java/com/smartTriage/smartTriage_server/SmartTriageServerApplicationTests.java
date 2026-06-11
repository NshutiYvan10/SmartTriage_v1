package com.smartTriage.smartTriage_server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full context-load smoke test. Previously this ran @SpringBootTest
 * against whatever database application.properties pointed at — i.e.
 * the developer's LIVE dev database (and applied pending Flyway
 * migrations to it as a side effect). It now extends
 * {@link AbstractIntegrationTest}: a disposable Testcontainers
 * PostgreSQL, migrated from scratch, skipped when Docker is absent.
 */
class SmartTriageServerApplicationTests extends AbstractIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	/** Every migration applied cleanly on a virgin database. */
	@Test
	void flywayMigratedFromScratch() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
				Integer.class);
		assertNotNull(applied);
		assertTrue(applied >= 68, "Expected at least 68 successful migrations, got " + applied);
	}
}
