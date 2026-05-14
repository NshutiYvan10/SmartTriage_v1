package com.smartTriage.smartTriage_server.module.patient.repository;

import com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientChronicCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientChronicConditionRepository extends JpaRepository<PatientChronicCondition, UUID> {

    Optional<PatientChronicCondition> findByIdAndIsActiveTrue(UUID id);

    /**
     * Non-RESOLVED active conditions, newest first. This is what
     * the chart panel and the safety engine see by default.
     * RESOLVED rows are excluded from the active feed but kept in
     * the audit history (see {@link #findAllByPatientIdIncludingResolved}).
     */
    @Query("SELECT c FROM PatientChronicCondition c WHERE c.patient.id = :patientId " +
            "AND c.isActive = true " +
            "AND c.status <> com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus.RESOLVED " +
            "ORDER BY c.recordedAt DESC")
    List<PatientChronicCondition> findActiveByPatientId(@Param("patientId") UUID patientId);

    /**
     * Full history including RESOLVED rows — drives the chart's
     * "Show history" expander and the audit view.
     */
    @Query("SELECT c FROM PatientChronicCondition c WHERE c.patient.id = :patientId " +
            "AND c.isActive = true " +
            "ORDER BY c.recordedAt DESC")
    List<PatientChronicCondition> findAllByPatientIdIncludingResolved(@Param("patientId") UUID patientId);

    /**
     * Idempotency helper — same conditionName for the same patient
     * already on file and not RESOLVED.
     */
    @Query("SELECT c FROM PatientChronicCondition c WHERE c.patient.id = :patientId " +
            "AND LOWER(c.conditionName) = LOWER(:conditionName) " +
            "AND c.status <> com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus.RESOLVED " +
            "AND c.isActive = true")
    Optional<PatientChronicCondition> findActiveDuplicate(
            @Param("patientId") UUID patientId,
            @Param("conditionName") String conditionName);

    long countByPatientIdAndIsActiveTrueAndStatus(UUID patientId, ChronicConditionStatus status);
}
