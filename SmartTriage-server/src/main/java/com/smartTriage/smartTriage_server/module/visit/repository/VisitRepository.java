package com.smartTriage.smartTriage_server.module.visit.repository;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
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
public interface VisitRepository extends JpaRepository<Visit, UUID> {

        Optional<Visit> findByIdAndIsActiveTrue(UUID id);

        Optional<Visit> findByVisitNumberAndIsActiveTrue(String visitNumber);

        Page<Visit> findByHospitalIdAndIsActiveTrue(UUID hospitalId, Pageable pageable);

        Page<Visit> findByPatientIdAndIsActiveTrue(UUID patientId, Pageable pageable);

        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        Page<Visit> findActiveVisits(@Param("hospitalId") UUID hospitalId, Pageable pageable);

        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.currentTriageCategory = :category " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        List<Visit> findActiveVisitsByTriageCategory(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("category") TriageCategory category);

        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.status = :status")
        Page<Visit> findByHospitalIdAndStatus(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("status") VisitStatus status,
                        Pageable pageable);

        /**
         * Active visits whose triage category maps to a given set of categories
         * (zone-based).
         * Used for "My Patients" — doctor sees only patients in their zone.
         */
        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true " +
                        "AND v.currentTriageCategory IN :categories " +
                        "AND v.status NOT IN ('DISCHARGED', 'ADMITTED', 'TRANSFERRED', 'ICU_ADMITTED', 'DECEASED', 'LEFT_WITHOUT_BEING_SEEN')")
        List<Visit> findActiveVisitsByTriageCategories(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("categories") List<TriageCategory> categories);

        /**
         * Active visits filtered by specific statuses — used by retriage and monitoring engines.
         */
        @Query("SELECT v FROM Visit v WHERE v.hospital.id = :hospitalId AND v.isActive = true AND v.status IN :statuses")
        List<Visit> findActiveVisitsByStatuses(
                        @Param("hospitalId") UUID hospitalId,
                        @Param("statuses") List<VisitStatus> statuses);

        /**
         * All active visits across all hospitals filtered by statuses — for scheduled monitoring tasks.
         */
        @Query("SELECT v FROM Visit v WHERE v.isActive = true AND v.status IN :statuses")
        List<Visit> findAllActiveVisitsByStatuses(@Param("statuses") List<VisitStatus> statuses);
}
