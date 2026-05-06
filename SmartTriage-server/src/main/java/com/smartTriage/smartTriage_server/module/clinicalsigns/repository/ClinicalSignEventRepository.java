package com.smartTriage.smartTriage_server.module.clinicalsigns.repository;

import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
