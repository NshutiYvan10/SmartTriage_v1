package com.smartTriage.smartTriage_server.module.alert.repository;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
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
public interface ClinicalAlertRepository extends JpaRepository<ClinicalAlert, UUID> {

        Optional<ClinicalAlert> findByIdAndIsActiveTrue(UUID id);

        Page<ClinicalAlert> findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(UUID visitId, Pageable pageable);

        /**
         * Idempotency check used by the identity-overdue scheduler — has
         * an alert of the given type already been raised for this visit
         * and is it still active (acknowledged or not)? Prevents the job
         * from re-firing every tick while the placeholder remains.
         */
        boolean existsByVisitIdAndAlertTypeAndIsActiveTrue(UUID visitId,
                                                          com.smartTriage.smartTriage_server.common.enums.AlertType alertType);

        /**
         * All alerts for a hospital (acknowledged + unacknowledged) — for the full
         * alert history view.
         */
        @Query("SELECT a FROM ClinicalAlert a JOIN a.visit v WHERE v.hospital.id = :hospitalId " +
                        "AND a.isActive = true ORDER BY a.createdAt DESC")
        Page<ClinicalAlert> findAllAlertsByHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

        /**
         * Unacknowledged alerts for a hospital — the critical alert queue.
         */
        @Query("SELECT a FROM ClinicalAlert a JOIN a.visit v WHERE v.hospital.id = :hospitalId " +
                        "AND a.isActive = true AND a.isAcknowledged = false ORDER BY a.createdAt DESC")
        Page<ClinicalAlert> findUnacknowledgedAlerts(@Param("hospitalId") UUID hospitalId, Pageable pageable);

        /**
         * Unacknowledged critical alerts — highest priority for the dashboard.
         */
        @Query("SELECT a FROM ClinicalAlert a JOIN a.visit v WHERE v.hospital.id = :hospitalId " +
                        "AND a.isActive = true AND a.isAcknowledged = false AND a.severity = :severity ORDER BY a.createdAt DESC")
        Page<ClinicalAlert> findUnacknowledgedAlertsBySeverity(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("severity") AlertSeverity severity,
                        Pageable pageable);

        // ====================================================================
        // ZONE-AWARE NOTIFICATION QUERIES
        // ====================================================================

        /**
         * Unacknowledged DOCTOR_NOTIFICATION and DOCTOR_ESCALATION alerts — for
         * escalation scheduler.
         */
        @Query("SELECT a FROM ClinicalAlert a WHERE a.isActive = true AND a.isAcknowledged = false " +
                        "AND (a.alertType = com.smartTriage.smartTriage_server.common.enums.AlertType.DOCTOR_NOTIFICATION "
                        +
                        "OR a.alertType = com.smartTriage.smartTriage_server.common.enums.AlertType.DOCTOR_ESCALATION) "
                        +
                        "ORDER BY a.createdAt ASC")
        List<ClinicalAlert> findUnacknowledgedDoctorNotifications();

        /**
         * Unacknowledged time-critical clinical alerts that need their own
         * follow-up escalation when nobody acks them. Distinct from the
         * DOCTOR_NOTIFICATION pipeline because the action implied is
         * different (a sepsis alert isn't acknowledged by routing to "all
         * doctors" — it's by starting the bundle), but the principle is
         * the same: a CRITICAL alert sitting unack'd for too long must be
         * escalated to all-staff before it gets lost.
         *
         * <p>Covers: SEPSIS_SCREENING, ICU_ESCALATION_REQUESTED,
         * CRITICAL_VALUE_UNACKNOWLEDGED, DETERIORATION_DETECTED.
         */
        @Query("SELECT a FROM ClinicalAlert a WHERE a.isActive = true AND a.isAcknowledged = false " +
                        "AND a.alertType IN (" +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.SEPSIS_SCREENING, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.ICU_ESCALATION_REQUESTED, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.CRITICAL_VALUE_UNACKNOWLEDGED, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.DETERIORATION_DETECTED, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.FAST_TRACK_ACTIVATED, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.FAST_TRACK_SLA_BREACH, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.HYPOGLYCEMIA_CRITICAL, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.HYPOGLYCEMIA_RECHECK_OVERDUE, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.ISOLATION_REQUIRED, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.ISOLATION_PLACEMENT_OVERDUE, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.NOTIFIABLE_DISEASE, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.PATHWAY_ACTIVATED, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.PATHWAY_STEP_OVERDUE) " +
                        "ORDER BY a.createdAt ASC")
        List<ClinicalAlert> findUnacknowledgedTimeCriticalAlerts();

        /**
         * Unacknowledged, not-yet-re-escalated CRITICAL ambulance pre-arrivals
         * (RED / lights). Fed to the escalation scheduler so a crashing inbound
         * that nobody acknowledged gets re-alarmed before the patient arrives.
         * escalatedAt IS NULL ensures we re-page only once.
         */
        @Query("SELECT a FROM ClinicalAlert a WHERE a.isActive = true AND a.isAcknowledged = false " +
                        "AND a.escalatedAt IS NULL " +
                        "AND a.alertType = com.smartTriage.smartTriage_server.common.enums.AlertType.EMS_PRE_ARRIVAL " +
                        "AND a.severity = com.smartTriage.smartTriage_server.common.enums.AlertSeverity.CRITICAL " +
                        "ORDER BY a.createdAt ASC")
        List<ClinicalAlert> findUnescalatedCriticalEmsPreArrivals();

        /**
         * Unacknowledged alerts for a specific zone — for zone doctor dashboard.
         */
        @Query("SELECT a FROM ClinicalAlert a JOIN a.visit v WHERE v.hospital.id = :hospitalId " +
                        "AND a.isActive = true AND a.isAcknowledged = false AND a.targetZone = :zone " +
                        "ORDER BY a.severity ASC, a.createdAt ASC")
        List<ClinicalAlert> findUnacknowledgedAlertsByZone(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("zone") EdZone zone);

        /**
         * Alerts targeted at a specific doctor.
         */
        @Query("SELECT a FROM ClinicalAlert a WHERE a.targetDoctor.id = :doctorId " +
                        "AND a.isActive = true AND a.isAcknowledged = false ORDER BY a.createdAt DESC")
        List<ClinicalAlert> findUnacknowledgedAlertsForDoctor(@Param("doctorId") UUID doctorId);

        /**
         * Count unacknowledged alerts per zone — for surge detection.
         */
        @Query("SELECT a.targetZone, COUNT(a) FROM ClinicalAlert a JOIN a.visit v " +
                        "WHERE v.hospital.id = :hospitalId AND a.isActive = true AND a.isAcknowledged = false " +
                        "AND a.targetZone IS NOT NULL GROUP BY a.targetZone")
        List<Object[]> countUnacknowledgedByZone(@Param("hospitalId") UUID hospitalId);

        /**
         * Check for existing unacknowledged alert of a given type for a visit — prevents duplicate alerts.
         */
        boolean existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(UUID visitId, AlertType alertType);

        /** The open (unacknowledged, active) alert of a type for a visit — used to
         *  acknowledge the FAST_TRACK_ACTIVATED alert when the pathway is accepted. */
        java.util.Optional<ClinicalAlert> findFirstByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                UUID visitId, AlertType alertType);

        /**
         * Find unacknowledged alerts by type for a hospital — for retriage dashboard queries.
         */
        @Query("SELECT a FROM ClinicalAlert a JOIN a.visit v WHERE v.hospital.id = :hospitalId " +
                        "AND a.isActive = true AND a.isAcknowledged = false AND a.alertType = :alertType " +
                        "ORDER BY a.createdAt DESC")
        List<ClinicalAlert> findUnacknowledgedAlertsByType(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("alertType") AlertType alertType);

        /**
         * Server-side filter for the Override Audit dashboard. The Phase 14
         * frontend currently pulls every alert and filters in-memory; once
         * volume crosses a few hundred per hospital that doesn't scale.
         * This query lets the dashboard ask the database for just the
         * MEDICATION_SAFETY_WARNING rows in a date window.
         *
         * `from` / `to` are both inclusive endpoints — pass null on either
         * side to leave that bound open. The frontend's "24h / 7d / 30d /
         * all" ranges all map cleanly onto this contract.
         *
         * Returns acknowledged + unacknowledged together because the
         * Override Audit is a forensic surface, not a queue — safety
         * officers need to see what overrides happened regardless of
         * whether someone has clicked acknowledge.
         */
        @Query("SELECT a FROM ClinicalAlert a JOIN a.visit v WHERE v.hospital.id = :hospitalId " +
                        "AND a.isActive = true " +
                        // Both prescribe-time overrides (MEDICATION_SAFETY_WARNING) AND the
                        // administration-time / high-alert-approval-gate bypasses
                        // (MEDICATION_EMERGENCY_OVERRIDE) are overrides the forensic audit must
                        // see — administration time is precisely where patient harm occurs.
                        "AND a.alertType IN (" +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.MEDICATION_SAFETY_WARNING, " +
                        "    com.smartTriage.smartTriage_server.common.enums.AlertType.MEDICATION_EMERGENCY_OVERRIDE) " +
                        "AND (:from IS NULL OR a.createdAt >= :from) " +
                        "AND (:to IS NULL OR a.createdAt <= :to) " +
                        "ORDER BY a.createdAt DESC")
        Page<ClinicalAlert> findSafetyOverrides(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("from") Instant from,
                        @Param("to") Instant to,
                        Pageable pageable);
}
