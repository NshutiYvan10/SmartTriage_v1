package com.smartTriage.smartTriage_server.module.visit.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;

/**
 * Hands out the next per-hospital, per-day sequence for a visit number
 * ({@code V-<code>-<yyyyMMdd>-<00000>}).
 *
 * <p>Replaces the old in-memory {@code AtomicLong} that reset to 0 on every
 * application restart and then re-issued visit numbers that already existed for
 * the same day — tripping the {@code visits.visit_number} unique constraint and
 * rolling the whole registration transaction back (the generic 409 a user hit
 * when submitting a new EMS run after a restart). The counter now lives in the
 * database and survives restarts.
 *
 * <p>Concurrency model is identical to {@code UnidentifiedPatientCounterRepository}:
 * {@code INSERT … ON CONFLICT … DO UPDATE SET next_index = next_index + 1
 * RETURNING next_index} runs as one statement and Postgres serialises it with a
 * row lock, so two concurrent registrations always draw distinct sequences.
 *
 * <p>No JPA entity backs this — the table is a composite-key counter whose only
 * operation is "upsert + return the new value".
 */
@Repository
public class VisitSequenceCounterRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Atomically reserve and return the next 1-based sequence for the given
     * hospital code and date. First claim of the day returns 1, then 2, 3, …
     * (the visit number formats it as a zero-padded {@code %05d}). A new day
     * implicitly starts a fresh row at 1.
     *
     * <p>Must run inside the caller's transaction (every registration path is
     * {@code @Transactional}).
     */
    public long claimNext(String hospitalCode, LocalDate date) {
        Object result = entityManager.createNativeQuery("""
                INSERT INTO visit_sequence_counters (hospital_code, sequence_date, next_index, updated_at)
                VALUES (?1, ?2, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (hospital_code, sequence_date) DO UPDATE
                    SET next_index = visit_sequence_counters.next_index + 1,
                        updated_at = CURRENT_TIMESTAMP
                RETURNING next_index
                """)
                .setParameter(1, hospitalCode)
                .setParameter(2, Date.valueOf(date))
                .getSingleResult();

        return ((Number) result).longValue();
    }
}
