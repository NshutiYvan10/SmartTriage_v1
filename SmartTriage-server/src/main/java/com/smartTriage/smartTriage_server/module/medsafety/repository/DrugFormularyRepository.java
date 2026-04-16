package com.smartTriage.smartTriage_server.module.medsafety.repository;

import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
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
public interface DrugFormularyRepository extends JpaRepository<DrugFormulary, UUID> {

    Optional<DrugFormulary> findByIdAndIsActiveTrue(UUID id);

    Optional<DrugFormulary> findByGenericNameIgnoreCaseAndHospitalIdAndIsActiveTrue(
            String genericName, UUID hospitalId);

    Optional<DrugFormulary> findByGenericNameIgnoreCaseAndHospitalIsNullAndIsActiveTrue(
            String genericName);

    /**
     * Browse formulary for a hospital — includes hospital-specific and system-wide entries.
     */
    @Query("SELECT f FROM DrugFormulary f WHERE f.isActive = true " +
            "AND (f.hospital.id = :hospitalId OR f.hospital IS NULL) " +
            "ORDER BY f.genericName ASC")
    Page<DrugFormulary> findFormularyForHospital(
            @Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Search formulary by drug name (generic or brand) — includes hospital-specific and system-wide.
     */
    @Query("SELECT f FROM DrugFormulary f WHERE f.isActive = true " +
            "AND (LOWER(f.genericName) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR LOWER(f.brandNames) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "ORDER BY f.genericName ASC")
    List<DrugFormulary> searchByName(@Param("query") String query);

    /**
     * System-wide formulary entries only (no hospital scope).
     */
    Page<DrugFormulary> findByHospitalIsNullAndIsActiveTrueOrderByGenericNameAsc(Pageable pageable);
}
