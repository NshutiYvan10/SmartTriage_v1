package com.smartTriage.smartTriage_server.module.medication.repository;

import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MedicationAdministrationRepository extends JpaRepository<MedicationAdministration, UUID> {

    Page<MedicationAdministration> findByVisitIdAndIsActiveTrueOrderByPrescribedAtDesc(
            UUID visitId, Pageable pageable);

    List<MedicationAdministration> findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(UUID visitId);

    Optional<MedicationAdministration> findByIdAndIsActiveTrue(UUID id);

    long countByVisitIdAndIsActiveTrue(UUID visitId);

    /**
     * Returns every active medication this patient has been prescribed across
     * ALL their visits, newest first. Drives the doctor's "Reorder" affordance:
     * one tap to copy a previous prescription's drugName/dose/route/frequency
     * into the new order. Cancelled / refused / soft-deleted records are
     * excluded by `is_active = true`.
     *
     * Joins through the visit because MedicationAdministration is visit-scoped.
     */
    @Query("SELECT m FROM MedicationAdministration m " +
           "WHERE m.visit.patient.id = :patientId AND m.isActive = true " +
           "ORDER BY m.prescribedAt DESC")
    List<MedicationAdministration> findByPatientIdAcrossVisits(@Param("patientId") UUID patientId);

    /**
     * Batched count of "prescribed but not yet administered" medications
     * for a list of visits. Returns one row per visit that has at least
     * one such medication. Drives the patient-card "N pending meds"
     * badge in the active-visits list.
     *
     * <p>Visits with zero matching rows are absent from the result —
     * the caller treats them as count = 0.
     */
    @Query("SELECT m.visit.id, COUNT(m) FROM MedicationAdministration m " +
           "WHERE m.visit.id IN :visitIds AND m.isActive = true " +
           "AND m.status = com.smartTriage.smartTriage_server.common.enums.MedicationStatus.PRESCRIBED " +
           "AND m.administeredAt IS NULL " +
           "GROUP BY m.visit.id")
    List<Object[]> countPendingByVisitIds(@Param("visitIds") java.util.Collection<UUID> visitIds);

    /**
     * Nurse medication queue (Workflow 3) — every active PRESCRIBED
     * medication for a hospital that has not yet been administered.
     * Sorted by priority tier (STAT first, then URGENT, then
     * ROUTINE) and within each tier oldest first so the most
     * overdue STAT bubbles to the top of the screen.
     *
     * <p>V67: typed SCHEDULED / PRN / CONTINUOUS orders are excluded —
     * they stay PRESCRIBED for their whole life and belong on the
     * dose-level zone board, not this single-shot queue. Legacy
     * (NULL-typed) and typed ONE_TIME orders keep appearing here.
     */
    @Query("SELECT m FROM MedicationAdministration m " +
           "JOIN FETCH m.visit v JOIN FETCH v.patient LEFT JOIN FETCH v.currentBed " +
           "WHERE v.hospital.id = :hospitalId AND m.isActive = true " +
           "AND m.status = com.smartTriage.smartTriage_server.common.enums.MedicationStatus.PRESCRIBED " +
           "AND m.administeredAt IS NULL " +
           "AND (m.prescriptionType IS NULL " +
           "     OR m.prescriptionType = com.smartTriage.smartTriage_server.common.enums.PrescriptionType.ONE_TIME) " +
           "ORDER BY CASE m.priority " +
           "    WHEN com.smartTriage.smartTriage_server.common.enums.MedicationPriority.STAT THEN 0 " +
           "    WHEN com.smartTriage.smartTriage_server.common.enums.MedicationPriority.URGENT THEN 1 " +
           "    ELSE 2 END, " +
           "m.prescribedAt ASC")
    List<MedicationAdministration> findPendingForHospital(@Param("hospitalId") UUID hospitalId);

    // ====================================================================
    // Medication Management (V67)
    // ====================================================================

    /**
     * Live typed orders of one type across a hospital, with visit +
     * patient fetched — drives the zone board's PRN / infusion /
     * pending-approval lanes (zone filter applied in-service from
     * {@code visit.currentEdZone} so mid-prescription transfers are
     * honoured live).
     */
    @Query("SELECT m FROM MedicationAdministration m " +
           "JOIN FETCH m.visit v JOIN FETCH v.patient LEFT JOIN FETCH v.currentBed " +
           "WHERE v.hospital.id = :hospitalId AND m.isActive = true " +
           "AND m.status = :status " +
           "AND m.prescriptionType = :type " +
           "ORDER BY m.prescribedAt ASC")
    List<MedicationAdministration> findByHospitalAndStatusAndType(
            @Param("hospitalId") UUID hospitalId,
            @Param("status") com.smartTriage.smartTriage_server.common.enums.MedicationStatus status,
            @Param("type") com.smartTriage.smartTriage_server.common.enums.PrescriptionType type);

    /**
     * Typed orders in one status across a hospital regardless of type
     * — drives the board's pending-approval lane.
     */
    @Query("SELECT m FROM MedicationAdministration m " +
           "JOIN FETCH m.visit v JOIN FETCH v.patient LEFT JOIN FETCH v.currentBed " +
           "WHERE v.hospital.id = :hospitalId AND m.isActive = true " +
           "AND m.status = :status AND m.prescriptionType IS NOT NULL " +
           "ORDER BY m.prescribedAt ASC")
    List<MedicationAdministration> findTypedByHospitalAndStatus(
            @Param("hospitalId") UUID hospitalId,
            @Param("status") com.smartTriage.smartTriage_server.common.enums.MedicationStatus status);

    /**
     * Completion sweep — live recurring/continuous orders whose endAt
     * has passed. Visit + hospital fetched for alert/broadcast use.
     */
    @Query("SELECT m FROM MedicationAdministration m " +
           "JOIN FETCH m.visit v JOIN FETCH v.hospital " +
           "WHERE m.isActive = true " +
           "AND m.status = com.smartTriage.smartTriage_server.common.enums.MedicationStatus.PRESCRIBED " +
           "AND m.prescriptionType IS NOT NULL " +
           "AND m.endAt IS NOT NULL AND m.endAt < :now")
    List<MedicationAdministration> findLiveTypedOrdersPastEnd(@Param("now") java.time.Instant now);

    /**
     * STAT-monitor query — PRESCRIBED meds older than the given
     * cutoff, filtered by priority. Used by
     * {@code MedicationStatMonitorService} to fire STAT / URGENT
     * SLA-breach alerts.
     */
    @Query("SELECT m FROM MedicationAdministration m " +
           "WHERE m.isActive = true " +
           "AND m.priority = :priority " +
           "AND m.status = com.smartTriage.smartTriage_server.common.enums.MedicationStatus.PRESCRIBED " +
           "AND m.administeredAt IS NULL " +
           "AND m.prescribedAt < :cutoff")
    List<MedicationAdministration> findOverduePrescribedByPriority(
            @Param("priority") com.smartTriage.smartTriage_server.common.enums.MedicationPriority priority,
            @Param("cutoff") java.time.Instant cutoff);

    /**
     * Live insulin exposure per visit — drives the glucose due-clock's insulin
     * tiers (q1h for a CONTINUOUS infusion, q4h otherwise). "Live" = PRESCRIBED
     * or ADMINISTERED (an SC dose keeps acting for hours after it's given), not
     * past its endAt, and prescribed/administered within the last 24 h so a
     * long-boarding patient's ancient one-time dose doesn't demand serial
     * fingersticks forever. Drug matching is by name — the same primary
     * mechanism the teratogen/allergy gates use.
     */
    @Query("SELECT m.visit.id, m.prescriptionType, COALESCE(m.administeredAt, m.prescribedAt) " +
           "FROM MedicationAdministration m " +
           "WHERE m.visit.id IN :visitIds AND m.isActive = true " +
           "AND m.status IN (com.smartTriage.smartTriage_server.common.enums.MedicationStatus.PRESCRIBED, " +
           "                 com.smartTriage.smartTriage_server.common.enums.MedicationStatus.ADMINISTERED) " +
           "AND LOWER(m.drugName) LIKE '%insulin%' " +
           "AND (m.endAt IS NULL OR m.endAt > :now) " +
           "AND COALESCE(m.administeredAt, m.prescribedAt) > :recencyCutoff")
    List<Object[]> findInsulinExposureByVisit(
            @Param("visitIds") java.util.Collection<UUID> visitIds,
            @Param("now") java.time.Instant now,
            @Param("recencyCutoff") java.time.Instant recencyCutoff);

    /** Override register (V109): prescriptions carrying an emergency / allergy / interaction override. */
    @org.springframework.data.jpa.repository.Query("SELECT m FROM MedicationAdministration m JOIN m.visit v "
            + "WHERE v.hospital.id = :hospitalId AND m.isActive = true AND m.prescribedAt BETWEEN :from AND :to "
            + "AND (m.emergencyOverride = true OR m.prescribedDespiteAllergy = true OR m.prescribedDespiteInteraction = true) "
            + "ORDER BY m.prescribedAt DESC")
    java.util.List<MedicationAdministration> findOverridesForHospital(
            @org.springframework.data.repository.query.Param("hospitalId") java.util.UUID hospitalId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** RBAC — projection used by ClinicalAuthz.canAccessMedication to scope the
     *  id-keyed order endpoints (administer / hold / countersign / approve /
     *  resume / discontinue / prn-dose / infusion) to the order's own hospital. */
    @Query("SELECT m.visit.id FROM MedicationAdministration m WHERE m.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);
}
