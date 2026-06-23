package com.smartTriage.smartTriage_server.module.lab.repository;

import com.smartTriage.smartTriage_server.common.enums.LabOrderStatus;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabOrderRepository extends JpaRepository<LabOrder, UUID> {

    Optional<LabOrder> findByIdAndIsActiveTrue(UUID id);

    /** Projection for hospital-scope authz — the order's visit id. */
    @Query("SELECT o.visit.id FROM LabOrder o WHERE o.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);

    Optional<LabOrder> findByOrderNumberAndIsActiveTrue(String orderNumber);

    Page<LabOrder> findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(
            UUID visitId, Pageable pageable);

    /**
     * Standalone lab orders for a visit — those NOT linked to an
     * {@code Investigation} row (investigation_id IS NULL). Used by the
     * handover report so an outstanding/critical standalone order is surfaced
     * without double-counting tests already rendered in the investigations
     * section (which come from the Investigation entity).
     */
    List<LabOrder> findByVisitIdAndInvestigationIsNullAndIsActiveTrueOrderByOrderedAtDesc(UUID visitId);

    /**
     * Pending (not yet resulted and not cancelled) lab orders for a hospital.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.resultedAt IS NULL AND o.cancelledAt IS NULL " +
            "ORDER BY CASE o.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END, o.orderedAt ASC")
    Page<LabOrder> findPendingOrders(
            @Param("hospitalId") UUID hospitalId, Pageable pageable);

    /** All orders for a hospital ordered within a window — for the lab reporting pack (newest first). */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.orderedAt BETWEEN :from AND :to " +
            "ORDER BY o.orderedAt DESC")
    List<LabOrder> findForReport(
            @Param("hospitalId") UUID hospitalId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Unacknowledged critical results for a hospital.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.isCritical = true " +
            "AND o.criticalValueAcknowledgedAt IS NULL " +
            // RESULTED only — a critical value still AWAITING_VERIFICATION is a junior's
            // unverified draft and must NOT surface on the doctor's critical banner.
            "AND o.resultedAt IS NOT NULL " +
            "ORDER BY o.resultedAt ASC")
    List<LabOrder> findUnacknowledgedCriticalResults(
            @Param("hospitalId") UUID hospitalId);

    /** Active LabOrder exists for this investigation — the lab "owns" its lifecycle. */
    boolean existsByInvestigation_IdAndIsActiveTrue(UUID investigationId);

    /** Investigation ids on a visit that have an active LabOrder (lab-owned), so the
     *  chart can show its own lifecycle actions only for investigations the lab is NOT
     *  driving (e.g. legacy lab-routable rows with no LabOrder). */
    @Query("SELECT o.investigation.id FROM LabOrder o WHERE o.visit.id = :visitId " +
            "AND o.isActive = true AND o.investigation IS NOT NULL")
    List<UUID> findInvestigationIdsWithActiveLabOrderForVisit(@Param("visitId") UUID visitId);

    /**
     * Does this visit still have ANY resulted critical lab order whose critical
     * value is unacknowledged? Used so a doctor acknowledging one order's critical
     * value only closes the (visit-scoped) escalation alerts when no OTHER critical
     * result on the same visit is still pending — preventing false suppression of a
     * second critical that nobody has seen.
     */
    @Query("SELECT COUNT(o) > 0 FROM LabOrder o WHERE o.visit.id = :visitId " +
            "AND o.isActive = true AND o.isCritical = true " +
            "AND o.resultedAt IS NOT NULL AND o.criticalValueAcknowledgedAt IS NULL")
    boolean hasUnacknowledgedCriticalForVisit(@Param("visitId") UUID visitId);

    /**
     * Active STAT orders (not yet resulted and not cancelled) for a hospital.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.priority = 'STAT' " +
            "AND o.resultedAt IS NULL AND o.cancelledAt IS NULL " +
            "ORDER BY o.orderedAt ASC")
    List<LabOrder> findActiveStatOrders(
            @Param("hospitalId") UUID hospitalId);

    /**
     * Overdue STAT orders — ordered more than 30 minutes ago without result.
     */
    @Query("SELECT o FROM LabOrder o WHERE o.isActive = true AND o.priority = :priority " +
            "AND o.resultedAt IS NULL AND o.cancelledAt IS NULL " +
            // Exclude terminal REJECTED (which has neither resultedAt nor cancelledAt set)
            // so a rejected specimen isn't counted as an overdue order forever.
            "AND o.status <> com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.REJECTED " +
            "AND o.orderedAt < :cutoff")
    List<LabOrder> findOverdueOrdersByPriority(
            @Param("priority") LabPriority priority,
            @Param("cutoff") Instant cutoff);

    /**
     * Orders still in ORDERED status (specimen not yet received by
     * the lab) past the cutoff — used by the stuck-in-ORDERED
     * early-warning check so an alert fires before the total
     * turnaround SLA is breached.
     */
    @Query("SELECT o FROM LabOrder o WHERE o.isActive = true AND o.priority = :priority " +
            "AND o.status = com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.ORDERED " +
            "AND o.orderedAt < :cutoff")
    List<LabOrder> findStuckInOrderedByPriority(
            @Param("priority") LabPriority priority,
            @Param("cutoff") Instant cutoff);

    /**
     * Critical results not acknowledged within the cutoff time.
     */
    @Query("SELECT o FROM LabOrder o WHERE o.isActive = true AND o.isCritical = true " +
            "AND o.criticalValueAcknowledgedAt IS NULL " +
            "AND o.resultedAt IS NOT NULL AND o.resultedAt < :cutoff")
    List<LabOrder> findUnacknowledgedCriticalResultsBefore(
            @Param("cutoff") Instant cutoff);

    /**
     * Count for order number generation — orders placed today.
     */
    @Query("SELECT COUNT(o) FROM LabOrder o WHERE o.orderNumber LIKE :prefix%")
    long countByOrderNumberPrefix(@Param("prefix") String prefix);

    // ── Lab-tech dashboard queries (Phase 1) ──

    /**
     * Lab-tech inbox: orders waiting for lab action — specimen is in
     * the lab (or about to be) but not yet processing/resulted.
     * Sorted STAT first, then by oldest first within priority.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true " +
            "AND o.status IN (com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.ORDERED, " +
            "                 com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.SPECIMEN_COLLECTED, " +
            "                 com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.RECEIVED_BY_LAB) " +
            "ORDER BY CASE o.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END, o.orderedAt ASC")
    List<LabOrder> findInboxForLab(@Param("hospitalId") UUID hospitalId);

    /**
     * Orders the tech has accessioned and is actively processing —
     * waiting for a result.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true " +
            "AND o.status = com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.PROCESSING " +
            "ORDER BY CASE o.priority WHEN 'STAT' THEN 0 WHEN 'URGENT' THEN 1 ELSE 2 END, o.processingStartedAt ASC")
    List<LabOrder> findInProgressForLab(@Param("hospitalId") UUID hospitalId);

    /**
     * Orders by status for a hospital — generic helper for Phase 2
     * verification queues, etc.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true AND o.status = :status " +
            "ORDER BY o.orderedAt DESC")
    List<LabOrder> findByHospitalAndStatus(
            @Param("hospitalId") UUID hospitalId,
            @Param("status") LabOrderStatus status);

    /**
     * Lab-tech history view (Workflow 2 refinement) — paginated
     * search across orders matching:
     *   • the hospital,
     *   • optional status filter (when null, no status filter is applied),
     *   • optional free-text query matched against orderNumber,
     *     testName, accessionNumber (case-insensitive substring).
     *
     * Used by the lab dashboard's "History" tab so the tech can
     * audit / re-look-up previously processed orders without the
     * page being limited to live work. Sorted newest first.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true " +
            "AND (:status IS NULL OR o.status = :status) " +
            "AND ( :q IS NULL OR :q = '' " +
            "      OR LOWER(o.orderNumber)     LIKE LOWER(CONCAT('%', :q, '%')) " +
            "      OR LOWER(o.testName)        LIKE LOWER(CONCAT('%', :q, '%')) " +
            "      OR LOWER(o.accessionNumber) LIKE LOWER(CONCAT('%', :q, '%')) ) " +
            "ORDER BY o.orderedAt DESC")
    org.springframework.data.domain.Page<LabOrder> searchHistory(
            @Param("hospitalId") UUID hospitalId,
            @Param("status") LabOrderStatus status,
            @Param("q") String query,
            org.springframework.data.domain.Pageable pageable);

    // ── Phase 2 — verification queue ──

    /**
     * Senior-tech verification queue: results entered but not yet
     * released to the doctor. Sorted by timeout-soonest first so
     * STAT items appear at the top.
     */
    @Query("SELECT o FROM LabOrder o JOIN o.visit v WHERE v.hospital.id = :hospitalId " +
            "AND o.isActive = true " +
            "AND o.status = com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.AWAITING_VERIFICATION " +
            "ORDER BY o.verificationTimeoutAt ASC NULLS LAST, o.orderedAt ASC")
    List<LabOrder> findAwaitingVerification(@Param("hospitalId") UUID hospitalId);

    /**
     * Background scheduler — find AWAITING_VERIFICATION rows whose
     * timeout has passed. They auto-release to keep patient care
     * unblocked when no senior is online.
     */
    @Query("SELECT o FROM LabOrder o WHERE o.isActive = true " +
            "AND o.status = com.smartTriage.smartTriage_server.common.enums.LabOrderStatus.AWAITING_VERIFICATION " +
            "AND o.verificationTimeoutAt IS NOT NULL " +
            "AND o.verificationTimeoutAt < :now")
    List<LabOrder> findVerificationTimeoutsBefore(@Param("now") java.time.Instant now);

    /** Count active HEAD_LAB_TECHNICIAN at the hospital — used to decide whether to enforce verification. */
    @Query("SELECT COUNT(u) FROM User u WHERE u.hospital.id = :hospitalId " +
            "AND u.isActive = true " +
            "AND u.designation = com.smartTriage.smartTriage_server.common.enums.Designation.HEAD_LAB_TECHNICIAN")
    long countActiveHeadLabTechs(@Param("hospitalId") UUID hospitalId);
}
