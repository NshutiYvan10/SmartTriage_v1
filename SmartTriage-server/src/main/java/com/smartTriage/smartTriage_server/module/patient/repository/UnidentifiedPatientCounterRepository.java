package com.smartTriage.smartTriage_server.module.patient.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Hands out the next per-hospital, per-day phonetic placeholder index for
 * an unidentified patient (Direct Resus Admission, V28).
 *
 * <p>The counter table is a thin row per (hospital, date). The increment
 * uses Postgres's native {@code INSERT ... ON CONFLICT ... DO UPDATE
 * RETURNING} pattern so two simultaneous Direct Resus admissions cannot
 * race into the same placeholder name — the database serialises them.
 *
 * <p>This repo deliberately has no JPA entity behind it; the counter
 * table has a composite primary key (hospital_id, sequence_date) and the
 * only operation we ever perform on it is "upsert + return new value".
 * A native query through {@link EntityManager} is the simplest correct
 * expression of that.
 */
@Repository
public class UnidentifiedPatientCounterRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Atomically reserves the next placeholder index for the given hospital
     * and date. Returns the index just claimed (0-based: 0 → "Alpha",
     * 1 → "Bravo", ... 25 → "Zulu", 26 → "Alpha-2", ...).
     *
     * <p>Concurrency model: {@code INSERT ... ON CONFLICT ... DO UPDATE SET
     * next_index = ... + 1 RETURNING next_index - 1} runs in a single SQL
     * statement; Postgres takes a row lock for the duration. Two concurrent
     * admissions each get a distinct index, never the same one.
     *
     * <p>Must run inside a transaction (the caller's {@code @Transactional}).
     */
    public int claimNextIndex(UUID hospitalId, LocalDate date) {
        Object result = entityManager.createNativeQuery("""
                INSERT INTO unidentified_patient_counters (hospital_id, sequence_date, next_index, updated_at)
                VALUES (?1, ?2, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (hospital_id, sequence_date) DO UPDATE
                    SET next_index = unidentified_patient_counters.next_index + 1,
                        updated_at = CURRENT_TIMESTAMP
                RETURNING next_index - 1
                """)
                .setParameter(1, hospitalId)
                .setParameter(2, Date.valueOf(date))
                .getSingleResult();

        // Postgres returns INTEGER which JPA surfaces as java.lang.Integer.
        return ((Number) result).intValue();
    }
}
