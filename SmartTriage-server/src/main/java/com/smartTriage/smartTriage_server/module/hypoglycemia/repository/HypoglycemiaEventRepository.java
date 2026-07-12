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
     * Active (unresolved) hypoglycemia events for a hospital, excluding visits the
     * ED no longer owns (discharged/admitted/... — else their open events sit on the
     * live dashboard forever). JOIN FETCH visit/patient (+ LEFT JOIN FETCH bed) for
     * the same reason as above.
     */
    @Query("SELECT h FROM HypoglycemiaEvent h JOIN FETCH h.visit v JOIN FETCH v.patient " +
            "LEFT JOIN FETCH v.currentBed " +
            "WHERE v.hospital.id = :hospitalId AND h.isActive = true AND h.resolved = false " +
            "AND (v.status IS NULL OR v.status NOT IN :terminalStatuses) " +
            "ORDER BY h.detectedAt DESC")
    List<HypoglycemiaEvent> findActiveEventsByHospital(
            @Param("hospitalId") UUID hospitalId,
            @Param("terminalStatuses") java.util.Collection<com.smartTriage.smartTriage_server.common.enums.VisitStatus> terminalStatuses);

    /**
     * Check for existing unresolved event for a visit — prevents duplicate events.
     */
    boolean existsByVisitIdAndResolvedFalseAndIsActiveTrue(UUID visitId);

    /** Project the owning visit id — used by ClinicalAuthz to scope the mutating endpoints. */
    @Query("SELECT h.visit.id FROM HypoglycemiaEvent h WHERE h.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);

    /** All unresolved active events — the recheck monitor scans these for overdue rechecks. */
    List<HypoglycemiaEvent> findByResolvedFalseAndIsActiveTrue();

    // ── glucose due-clock scan support (GlucoseScheduleService) ──

    /** Visits with an UNRESOLVED event — the due-clock must skip them (the 15-min recheck clock owns them). */
    @Query("SELECT DISTINCT h.visit.id FROM HypoglycemiaEvent h WHERE h.resolved = false AND h.isActive = true")
    List<UUID> findVisitIdsWithUnresolvedEvents();

    /** Latest resolution time per visit for events resolved after {@code cutoff} — arms the q1h post-hypo observation tier. */
    @Query("SELECT h.visit.id, MAX(h.resolvedAt) FROM HypoglycemiaEvent h " +
            "WHERE h.visit.id IN :visitIds AND h.isActive = true AND h.resolved = true AND h.resolvedAt > :cutoff " +
            "GROUP BY h.visit.id")
    List<Object[]> latestResolvedAfterByVisit(@Param("visitIds") java.util.Collection<UUID> visitIds,
                                              @Param("cutoff") java.time.Instant cutoff);

    /** Latest event-borne glucose timestamp per visit (repeat reading when present, else detection reading). */
    @Query("SELECT h.visit.id, MAX(COALESCE(h.repeatGlucoseAt, h.detectedAt)) FROM HypoglycemiaEvent h " +
            "WHERE h.visit.id IN :visitIds AND h.isActive = true " +
            "GROUP BY h.visit.id")
    List<Object[]> latestEventGlucoseAtByVisit(@Param("visitIds") java.util.Collection<UUID> visitIds);
}
