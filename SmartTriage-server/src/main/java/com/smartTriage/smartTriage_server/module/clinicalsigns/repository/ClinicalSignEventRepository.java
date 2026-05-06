package com.smartTriage.smartTriage_server.module.clinicalsigns.repository;

import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ClinicalSignEventRepository extends JpaRepository<ClinicalSignEvent, UUID> {

    /** Full event history for a visit, oldest-first. */
    List<ClinicalSignEvent> findByVisitIdAndIsActiveTrueOrderByRecordedAtAsc(UUID visitId);

    /**
     * Latest event per sign_code for a visit — drives the "current state"
     * view. Implemented via a correlated subquery: pick the row whose
     * recorded_at is the max for its (visit_id, sign_code) bucket.
     *
     * Tied timestamps (rare; same recorded_at by accident) fall back to
     * the row with the highest id ordering — deterministic but arbitrary.
     */
    @Query("SELECT e FROM ClinicalSignEvent e " +
           "WHERE e.visit.id = :visitId AND e.isActive = true " +
           "AND e.recordedAt = (" +
           "    SELECT MAX(e2.recordedAt) FROM ClinicalSignEvent e2 " +
           "    WHERE e2.visit.id = e.visit.id AND e2.signCode = e.signCode AND e2.isActive = true" +
           ") " +
           "ORDER BY e.signCategory ASC, e.signCode ASC")
    List<ClinicalSignEvent> findCurrentStateForVisit(@Param("visitId") UUID visitId);

    /**
     * History of a single sign across this visit — for per-sign mini-timeline.
     */
    List<ClinicalSignEvent> findByVisitIdAndSignCodeAndIsActiveTrueOrderByRecordedAtAsc(
            UUID visitId, String signCode);

    /**
     * Round 4c — find non-baseline events recorded within the last
     * {@code since} window that have NOT yet been processed by the
     * re-triage engine. "Processed" is defined as: there's no
     * triage_records row pointing back to this event, AND there's no
     * clinical_alerts row pointing back to this event. The two NOT
     * EXISTS subqueries cover both the AutoBump and Suggest output
     * paths.
     *
     * <p>The query is intentionally conservative: it returns events
     * that may not have warranted any action either (the inline
     * evaluator would have decided NoAction) — re-running them is
     * cheap and idempotent because the evaluator returns the same
     * decision either way.
     *
     * <p>Capped to 200 rows to keep the scheduled job's per-tick work
     * bounded; if the backlog grows beyond that the next tick will
     * pick up the remainder.
     */
    @Query(value = "SELECT e.* FROM clinical_sign_events e " +
            "WHERE e.is_active = true " +
            "AND e.is_baseline = false " +
            "AND e.recorded_at >= :since " +
            "AND NOT EXISTS (" +
            "    SELECT 1 FROM triage_records t " +
            "    WHERE t.triggering_sign_event_id = e.id" +
            ") " +
            "AND NOT EXISTS (" +
            "    SELECT 1 FROM clinical_alerts a " +
            "    WHERE a.triggering_sign_event_id = e.id" +
            ") " +
            "ORDER BY e.recorded_at ASC " +
            "LIMIT 200",
            nativeQuery = true)
    List<ClinicalSignEvent> findUnprocessedRecentEvents(@Param("since") Instant since);
}
