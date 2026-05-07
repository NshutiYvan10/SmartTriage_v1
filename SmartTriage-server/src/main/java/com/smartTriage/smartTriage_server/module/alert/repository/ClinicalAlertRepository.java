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

        /**
         * Find unacknowledged alerts by type for a hospital — for retriage dashboard queries.
         */
        @Query("SELECT a FROM ClinicalAlert a JOIN a.visit v WHERE v.hospital.id = :hospitalId " +
                        "AND a.isActive = true AND a.isAcknowledged = false AND a.alertType = :alertType " +
                        "ORDER BY a.createdAt DESC")
        List<ClinicalAlert> findUnacknowledgedAlertsByType(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("alertType") AlertType alertType);
}
