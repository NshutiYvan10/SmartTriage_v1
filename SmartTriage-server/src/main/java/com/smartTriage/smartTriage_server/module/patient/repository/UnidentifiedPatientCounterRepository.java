package com.smartTriage.smartTriage_server.module.patient.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Supports phonetic placeholder-name assignment for unidentified patients
 * (Direct Resus + EMS unknown arrivals).
 *
 * <p>Naming is now driven by "which phonetic labels are held by an ACTIVE
 * unidentified patient at this hospital RIGHT NOW", not a per-day counter.
 * The old daily-reset counter produced duplicate LIVE names when an
 * unidentified patient lingered past midnight (yesterday's "Unknown Alpha"
 * still admitted while today's first arrival also became "Alpha") — defeating
 * the whole point of phonetic disambiguation. Reading the live set lets the
 * naming service pick the lowest FREE label and reuse a name only once its
 * previous holder has been identified or discharged.
 */
@Repository
public class UnidentifiedPatientCounterRepository {

    /** Advisory-lock namespace for unidentified-placeholder claims (arbitrary, fixed). */
    private static final int ADVISORY_NAMESPACE = 8274;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Serialise placeholder claims for one hospital, then return the set of
     * phonetic labels currently held by its ACTIVE unidentified patients.
     *
     * <p>The {@code pg_advisory_xact_lock} is held until the caller's
     * transaction commits/rolls back, so two simultaneous unidentified
     * admissions at the same hospital cannot both read an empty set and both
     * claim "Alpha" — the second waits, then sees the first's label. Both
     * callers ({@code EmsRunService.preregister},
     * {@code DirectResusService.admit}) are {@code @Transactional}, so the
     * lock spans the subsequent patient insert.
     *
     * <p>Must run inside the caller's transaction.
     */
    public Set<String> lockActivePlaceholderLabels(UUID hospitalId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1, ?2)")
                .setParameter(1, ADVISORY_NAMESPACE)
                .setParameter(2, hospitalId.hashCode())
                .getSingleResult();

        @SuppressWarnings("unchecked")
        List<String> labels = entityManager.createNativeQuery("""
                SELECT placeholder_label FROM patients
                WHERE hospital_id = ?1
                  AND is_unidentified = true
                  AND is_active = true
                  AND placeholder_label IS NOT NULL
                """)
                .setParameter(1, hospitalId)
                .getResultList();
        return new HashSet<>(labels);
    }
}
