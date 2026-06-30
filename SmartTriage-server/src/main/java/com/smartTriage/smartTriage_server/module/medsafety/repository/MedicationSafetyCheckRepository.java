package com.smartTriage.smartTriage_server.module.medsafety.repository;

import com.smartTriage.smartTriage_server.module.medsafety.entity.MedicationSafetyCheck;
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
public interface MedicationSafetyCheckRepository extends JpaRepository<MedicationSafetyCheck, UUID> {

    Optional<MedicationSafetyCheck> findByIdAndIsActiveTrue(UUID id);

    /**
     * Paged checks for a visit — JOIN FETCHes visit → patient and (LEFT)
     * visit.currentBed so the mapper can denormalise patient identity +
     * location (who + where) onto each row without N+1 or
     * LazyInitializationException. Only single-valued associations are
     * fetched, so pagination remains DB-side (no in-memory paging warning).
     */
    @Query("SELECT c FROM MedicationSafetyCheck c "
            + "JOIN FETCH c.visit v "
            + "JOIN FETCH v.patient "
            + "LEFT JOIN FETCH v.currentBed "
            + "WHERE c.visit.id = :visitId AND c.isActive = true "
            + "ORDER BY c.checkedAt DESC")
    Page<MedicationSafetyCheck> findByVisitIdAndIsActiveTrueOrderByCheckedAtDesc(
            @Param("visitId") UUID visitId, Pageable pageable);

    List<MedicationSafetyCheck> findByVisitIdAndIsActiveTrueOrderByCheckedAtDesc(UUID visitId);

    Optional<MedicationSafetyCheck> findByMedicationIdAndIsActiveTrueOrderByCheckedAtDesc(
            UUID medicationId);

    List<MedicationSafetyCheck> findByVisitIdAndOverallSafeFalseAndOverriddenByIsNullAndIsActiveTrue(
            UUID visitId);

    /** Resolve a safety check's visit id — used by ClinicalAuthz to hospital-scope the override endpoint. */
    @Query("SELECT c.visit.id FROM MedicationSafetyCheck c WHERE c.id = :id AND c.isActive = true")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);
}
