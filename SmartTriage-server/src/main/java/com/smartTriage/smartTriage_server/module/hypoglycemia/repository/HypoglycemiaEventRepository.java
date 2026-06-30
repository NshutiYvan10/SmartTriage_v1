package com.smartTriage.smartTriage_server.module.hypoglycemia.repository;

import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HypoglycemiaEventRepository extends JpaRepository<HypoglycemiaEvent, UUID> {

    Optional<HypoglycemiaEvent> findByIdAndIsActiveTrue(UUID id);

    /**
     * JOIN FETCH visit/patient and LEFT JOIN FETCH the (nullable) current bed so the response
     * mapper can read patientName/zone/bedLabel after the service transaction closes without
     * a LazyInitializationException or N+1 per row.
     */
    @Query("SELECT h FROM HypoglycemiaEvent h JOIN FETCH h.visit v JOIN FETCH v.patient " +
            "LEFT JOIN FETCH v.currentBed " +
            "WHERE h.visit.id = :visitId AND h.isActive = true ORDER BY h.detectedAt DESC")
    List<HypoglycemiaEvent> findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(@Param("visitId") UUID visitId);

    /**
     * Active (unresolved) hypoglycemia events for a hospital.
     * JOIN FETCH visit/patient (+ LEFT JOIN FETCH bed) for the same reason as above.
     */
    @Query("SELECT h FROM HypoglycemiaEvent h JOIN FETCH h.visit v JOIN FETCH v.patient " +
            "LEFT JOIN FETCH v.currentBed " +
            "WHERE v.hospital.id = :hospitalId AND h.isActive = true AND h.resolved = false " +
            "ORDER BY h.detectedAt DESC")
    List<HypoglycemiaEvent> findActiveEventsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Check for existing unresolved event for a visit — prevents duplicate events.
     */
    boolean existsByVisitIdAndResolvedFalseAndIsActiveTrue(UUID visitId);

    /** Project the owning visit id — used by ClinicalAuthz to scope the mutating endpoints. */
    @Query("SELECT h.visit.id FROM HypoglycemiaEvent h WHERE h.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);

    /** All unresolved active events — the recheck monitor scans these for overdue rechecks. */
    List<HypoglycemiaEvent> findByResolvedFalseAndIsActiveTrue();
}
