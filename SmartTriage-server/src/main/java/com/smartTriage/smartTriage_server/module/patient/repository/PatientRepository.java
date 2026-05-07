package com.smartTriage.smartTriage_server.module.patient.repository;

import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByIdAndIsActiveTrue(UUID id);

    Page<Patient> findByHospitalIdAndIsActiveTrue(UUID hospitalId, Pageable pageable);

    /**
     * Lightweight projection used by ClinicalAuthz to verify a patient
     * belongs to a given hospital without hydrating the entity. Returns
     * empty when the patient id does not exist.
     */
    @Query("SELECT p.hospital.id FROM Patient p WHERE p.id = :patientId")
    Optional<UUID> findHospitalIdByPatientId(@Param("patientId") UUID patientId);

    // ── Tier 1: deterministic identifiers (partial-unique within hospital) ──

    Optional<Patient> findByNationalIdAndHospitalIdAndIsActiveTrue(String nationalId, UUID hospitalId);

    Optional<Patient> findByPassportNumberAndHospitalIdAndIsActiveTrue(String passportNumber, UUID hospitalId);

    Optional<Patient> findByBirthCertificateNumberAndHospitalIdAndIsActiveTrue(
            String birthCertificateNumber, UUID hospitalId);

    Optional<Patient> findByMedicalRecordNumberAndHospitalIdAndIsActiveTrue(String mrn, UUID hospitalId);

    // ── Tier 3: soft identifiers (multiple matches possible) ──
    //
    // These return List<> rather than Optional<> because the same phone /
    // guardian-NID / guardian-phone can legitimately point to several
    // patients (siblings, family-shared phones, etc.). Ranking is the
    // service's job; the repo just retrieves.

    List<Patient> findAllByPhoneNumberAndHospitalIdAndIsActiveTrue(String phoneNumber, UUID hospitalId);

    List<Patient> findAllByGuardianNationalIdAndHospitalIdAndIsActiveTrue(
            String guardianNationalId, UUID hospitalId);

    List<Patient> findAllByGuardianPhoneAndHospitalIdAndIsActiveTrue(String guardianPhone, UUID hospitalId);

    // ── Tier 4: demographic match (name + DOB) ──

    @Query("SELECT p FROM Patient p WHERE p.hospital.id = :hospitalId AND p.isActive = true " +
            "AND LOWER(p.firstName) = LOWER(:firstName) " +
            "AND LOWER(p.lastName)  = LOWER(:lastName) " +
            "AND p.dateOfBirth = :dob")
    List<Patient> findDemographicMatch(@Param("hospitalId") UUID hospitalId,
                                       @Param("firstName") String firstName,
                                       @Param("lastName") String lastName,
                                       @Param("dob") LocalDate dob);

    // ── Free-text search (kept for hospital/{id}/search compatibility) ──

    @Query("SELECT p FROM Patient p WHERE p.hospital.id = :hospitalId AND p.isActive = true " +
            "AND (LOWER(p.firstName) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR LOWER(p.lastName) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR p.nationalId LIKE CONCAT('%', :query, '%') " +
            "OR p.medicalRecordNumber LIKE CONCAT('%', :query, '%'))")
    Page<Patient> searchPatients(@Param("hospitalId") UUID hospitalId,
                                 @Param("query") String query,
                                 Pageable pageable);

    /**
     * Unidentified patients whose placeholder is older than the given
     * threshold. Used by the identity-overdue scheduler to raise an
     * IDENTITY_UNRESOLVED alert on patients still bearing a phonetic
     * label after the configured wait time. Filtered to active patients.
     */
    @Query("SELECT p FROM Patient p " +
            "WHERE p.isActive = true " +
            "AND p.isUnidentified = true " +
            "AND p.placeholderAssignedAt IS NOT NULL " +
            "AND p.placeholderAssignedAt <= :threshold")
    java.util.List<Patient> findUnidentifiedOlderThan(@Param("threshold") java.time.Instant threshold);
}
