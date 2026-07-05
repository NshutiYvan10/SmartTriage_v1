package com.smartTriage.smartTriage_server.module.patient.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Hands out the next per-hospital sequence for a medical record number
 * ({@code <hospitalCode>-<n>}).
 *
 * <p>Replaces the static in-memory {@code AtomicLong(100000)} that reset on every application
 * restart and then re-issued MRNs that already existed — tripping the
 * {@code uq_patient_mrn_per_hospital} partial-unique index (V22) and rolling the whole
 * registration transaction back (the generic 409 "conflicts with existing data" a registrar hit
 * after a restart). The counter now lives in the database ({@code mrn_sequence_counters}, V103)
 * and survives restarts.
 *
 * <p>Concurrency model is identical to {@code VisitSequenceCounterRepository}:
 * {@code INSERT … ON CONFLICT … DO UPDATE SET next_index = next_index + 1 RETURNING next_index}
 * runs as ONE statement and Postgres serialises it with a row lock, so two concurrent
 * registrations — on the same instance OR different instances — always draw distinct sequences.
 *
 * <p>Unlike the visit counter this is keyed on {@code hospital_code} ALONE with no date: an MRN
 * is a lifetime identifier whose sequence must never reset.
 *
 * <p>No JPA entity backs this — the table is a single-column-key counter whose only operation is
 * "upsert + return the new value".
 */
@Repository
public class MrnSequenceCounterRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Atomically reserve and return the next sequence for the given hospital code. V103 seeds each
     * existing hospital at its current MRN high-water mark, so the first claim returns max+1 and
     * continues above every existing MRN. A hospital created after V103 (no seed row) self-seeds at
     * 100001 on first claim — matching the historical {@code AtomicLong(100000).incrementAndGet()}.
     *
     * <p>Must run inside the caller's transaction (both registration paths are {@code @Transactional}).
     */
    public long claimNext(String hospitalCode) {
        Object result = entityManager.createNativeQuery("""
                INSERT INTO mrn_sequence_counters (hospital_code, next_index, updated_at)
                VALUES (?1, 100001, CURRENT_TIMESTAMP)
                ON CONFLICT (hospital_code) DO UPDATE
                    SET next_index = mrn_sequence_counters.next_index + 1,
                        updated_at = CURRENT_TIMESTAMP
                RETURNING next_index
                """)
                .setParameter(1, hospitalCode)
                .getSingleResult();

        return ((Number) result).longValue();
    }
}
