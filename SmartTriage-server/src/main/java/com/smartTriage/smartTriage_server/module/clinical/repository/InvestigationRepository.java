package com.smartTriage.smartTriage_server.module.clinical.repository;

import com.smartTriage.smartTriage_server.common.enums.InvestigationStatus;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestigationRepository extends JpaRepository<Investigation, UUID> {

    Page<Investigation> findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(
            UUID visitId, Pageable pageable);

    List<Investigation> findByVisitIdAndIsActiveTrueOrderByOrderedAtAsc(UUID visitId);

    List<Investigation> findByVisitIdAndInvestigationTypeAndIsActiveTrueOrderByOrderedAtDesc(
            UUID visitId, InvestigationType investigationType);

    List<Investigation> findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(
            UUID visitId, InvestigationStatus status);

    Optional<Investigation> findByIdAndIsActiveTrue(UUID id);

    long countByVisitIdAndStatusAndIsActiveTrue(UUID visitId, InvestigationStatus status);

    /**
     * Batched count of pending (ordered or specimen-collected, not
     * resulted) investigations for a list of visits. One row per visit
     * with at least one match. Drives the patient-card "N pending labs"
     * badge.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT i.visit.id, COUNT(i) FROM Investigation i " +
            "WHERE i.visit.id IN :visitIds AND i.isActive = true " +
            "AND i.status IN (com.smartTriage.smartTriage_server.common.enums.InvestigationStatus.ORDERED, " +
            "                  com.smartTriage.smartTriage_server.common.enums.InvestigationStatus.SPECIMEN_COLLECTED, " +
            "                  com.smartTriage.smartTriage_server.common.enums.InvestigationStatus.IN_PROGRESS) " +
            "GROUP BY i.visit.id")
    List<Object[]> countPendingByVisitIds(
            @org.springframework.data.repository.query.Param("visitIds") java.util.Collection<UUID> visitIds);

    /**
     * Batched count of recently-resulted critical/abnormal lab values
     * for a list of visits. Used to flag "result back, needs review"
     * on the patient card so the inheriting doctor doesn't miss a
     * critical lab that came back during the previous shift.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT i.visit.id, COUNT(i) FROM Investigation i " +
            "WHERE i.visit.id IN :visitIds AND i.isActive = true " +
            "AND i.status = com.smartTriage.smartTriage_server.common.enums.InvestigationStatus.RESULTED " +
            "AND (i.isCritical = true OR i.isAbnormal = true) " +
            "GROUP BY i.visit.id")
    List<Object[]> countCriticalResultedByVisitIds(
            @org.springframework.data.repository.query.Param("visitIds") java.util.Collection<UUID> visitIds);

    /** RBAC fix — projection used by ClinicalAuthz.canAccessInvestigation. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT i.visit.id FROM Investigation i WHERE i.id = :id")
    Optional<UUID> findVisitIdByInvestigationId(
            @org.springframework.data.repository.query.Param("id") UUID id);

    /**
     * Workflow 2 refinement — every investigation a specific doctor
     * has ordered, across every visit, newest first. Matches by
     * {@code ordered_by_id} FK (post-V62) AND by case-insensitive
     * name fallback for legacy rows that lack the FK.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT i FROM Investigation i " +
            "JOIN FETCH i.visit v JOIN FETCH v.patient " +
            "LEFT JOIN FETCH v.currentBed " +
            "WHERE i.isActive = true " +
            "AND ( i.orderedBy.id = :doctorId " +
            "      OR (i.orderedBy IS NULL AND LOWER(i.orderedByName) = LOWER(:doctorName)) ) " +
            "ORDER BY i.orderedAt DESC")
    List<Investigation> findByOrderedByIdOrLegacyName(
            @org.springframework.data.repository.query.Param("doctorId") UUID doctorId,
            @org.springframework.data.repository.query.Param("doctorName") String doctorName);

    /**
     * Imaging &amp; Diagnostics worklist — every ACTIVE imaging/ECG investigation at a
     * hospital that still needs a technician to act (status in {@code statuses},
     * e.g. ORDERED / IN_PROGRESS), across all patients. Hospital-scoped via
     * {@code v.hospital.id}. STAT first, then URGENT, then by age (oldest first)
     * so the queue mirrors the lab inbox ordering. JOIN FETCHes visit + patient +
     * bed so the mapper can hydrate patient context without a lazy-load per row
     * (OSIV is off — a lazy access outside the tx would throw).
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT i FROM Investigation i " +
            "JOIN FETCH i.visit v JOIN FETCH v.patient " +
            "LEFT JOIN FETCH v.currentBed " +
            "LEFT JOIN FETCH i.orderedBy " +
            "WHERE i.isActive = true " +
            "AND v.hospital.id = :hospitalId " +
            "AND i.investigationType IN :types " +
            "AND i.status IN :statuses " +
            "ORDER BY CASE UPPER(i.priority) WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END, " +
            "         i.orderedAt ASC")
    List<Investigation> findDiagnosticsWorklist(
            @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
            @org.springframework.data.repository.query.Param("types") java.util.Collection<InvestigationType> types,
            @org.springframework.data.repository.query.Param("statuses") java.util.Collection<InvestigationStatus> statuses);
}
