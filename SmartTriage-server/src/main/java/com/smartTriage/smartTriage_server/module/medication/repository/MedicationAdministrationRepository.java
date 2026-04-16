package com.smartTriage.smartTriage_server.module.medication.repository;

import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
