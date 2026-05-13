package com.smartTriage.smartTriage_server.module.patient.repository;

import com.smartTriage.smartTriage_server.common.enums.AllergyVerificationStatus;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientAllergy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientAllergyRepository extends JpaRepository<PatientAllergy, UUID> {

    Optional<PatientAllergy> findByIdAndIsActiveTrue(UUID id);

    /**
     * All non-refuted active allergies for a patient, newest first.
     * This is the list the safety engine consults at prescribe time.
     * Refuted allergies are deliberately excluded so they no longer
     * fire safety dialogs, but they remain on the audit trail
     * (queryable via {@link #findAllByPatientIdIncludingRefuted}).
     */
    @Query("SELECT a FROM PatientAllergy a WHERE a.patient.id = :patientId " +
            "AND a.isActive = true " +
            "AND a.verificationStatus <> com.smartTriage.smartTriage_server.common.enums.AllergyVerificationStatus.REFUTED " +
            "ORDER BY a.recordedAt DESC")
    List<PatientAllergy> findActiveByPatientId(@Param("patientId") UUID patientId);

    /**
     * Full history including refuted rows — used by the audit/history
     * view. Returns everything the soft-delete flag has kept.
     */
    @Query("SELECT a FROM PatientAllergy a WHERE a.patient.id = :patientId " +
            "AND a.isActive = true " +
            "ORDER BY a.recordedAt DESC")
    List<PatientAllergy> findAllByPatientIdIncludingRefuted(@Param("patientId") UUID patientId);

    /**
     * Idempotency helper — when a clinician records an allergy that
     * already exists (same allergen, same verification status, not
     * refuted), we surface the existing row instead of creating a
     * duplicate.
     */
    @Query("SELECT a FROM PatientAllergy a WHERE a.patient.id = :patientId " +
            "AND LOWER(a.allergenName) = LOWER(:allergenName) " +
            "AND a.verificationStatus <> com.smartTriage.smartTriage_server.common.enums.AllergyVerificationStatus.REFUTED " +
            "AND a.isActive = true")
    Optional<PatientAllergy> findActiveDuplicate(
            @Param("patientId") UUID patientId,
            @Param("allergenName") String allergenName);

    long countByPatientIdAndIsActiveTrueAndVerificationStatusNot(
            UUID patientId, AllergyVerificationStatus excluded);
}
