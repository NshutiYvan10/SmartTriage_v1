package com.smartTriage.smartTriage_server.module.icd.repository;

import com.smartTriage.smartTriage_server.module.icd.entity.IcdCode;
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
public interface IcdCodeRepository extends JpaRepository<IcdCode, UUID> {

    Optional<IcdCode> findByIdAndIsActiveTrue(UUID id);

    Optional<IcdCode> findByCodeIgnoreCaseAndIsActiveTrue(String code);

    /**
     * Search by code or description, case-insensitive substring.
     *
     * Ordering puts Rwanda-common diagnoses first so when a doctor types
     * "mal" they get "Plasmodium falciparum malaria" before less relevant
     * codes that happen to contain those letters. Within each tier, results
     * are alphabetical by description.
     */
    @Query("SELECT i FROM IcdCode i WHERE i.isActive = true " +
            "AND (LOWER(i.code) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR LOWER(i.description) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "ORDER BY i.isCommonInRwanda DESC, i.description ASC")
    List<IcdCode> searchActive(@Param("query") String query);

    /**
     * Browse the full catalog. Common-in-Rwanda first, then alphabetical.
     */
    @Query("SELECT i FROM IcdCode i WHERE i.isActive = true " +
            "ORDER BY i.isCommonInRwanda DESC, i.description ASC")
    Page<IcdCode> findAllActive(Pageable pageable);

    /**
     * Curated short-list for "common conditions" quick-pick.
     */
    List<IcdCode> findByIsCommonInRwandaTrueAndIsActiveTrueOrderByDescriptionAsc();
}
