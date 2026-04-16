package com.smartTriage.smartTriage_server.module.patient.repository;

import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByIdAndIsActiveTrue(UUID id);

    Page<Patient> findByHospitalIdAndIsActiveTrue(UUID hospitalId, Pageable pageable);

    Optional<Patient> findByNationalIdAndHospitalIdAndIsActiveTrue(String nationalId, UUID hospitalId);

    Optional<Patient> findByMedicalRecordNumberAndHospitalIdAndIsActiveTrue(String mrn, UUID hospitalId);

    @Query("SELECT p FROM Patient p WHERE p.hospital.id = :hospitalId AND p.isActive = true " +
            "AND (LOWER(p.firstName) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR LOWER(p.lastName) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR p.nationalId LIKE CONCAT('%', :query, '%') " +
            "OR p.medicalRecordNumber LIKE CONCAT('%', :query, '%'))")
    Page<Patient> searchPatients(@Param("hospitalId") UUID hospitalId,
                                 @Param("query") String query,
                                 Pageable pageable);
}
