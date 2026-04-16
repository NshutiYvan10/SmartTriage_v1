package com.smartTriage.smartTriage_server.module.medsafety.repository;

import com.smartTriage.smartTriage_server.module.medsafety.entity.MedicationSafetyCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MedicationSafetyCheckRepository extends JpaRepository<MedicationSafetyCheck, UUID> {

    Optional<MedicationSafetyCheck> findByIdAndIsActiveTrue(UUID id);

    Page<MedicationSafetyCheck> findByVisitIdAndIsActiveTrueOrderByCheckedAtDesc(
            UUID visitId, Pageable pageable);

    List<MedicationSafetyCheck> findByVisitIdAndIsActiveTrueOrderByCheckedAtDesc(UUID visitId);

    Optional<MedicationSafetyCheck> findByMedicationIdAndIsActiveTrueOrderByCheckedAtDesc(
            UUID medicationId);

    List<MedicationSafetyCheck> findByVisitIdAndOverallSafeFalseAndOverriddenByIsNullAndIsActiveTrue(
            UUID visitId);
}
