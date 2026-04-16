package com.smartTriage.smartTriage_server.module.sepsis.repository;

import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
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
public interface SepsisScreeningRepository extends JpaRepository<SepsisScreening, UUID> {

    Optional<SepsisScreening> findByIdAndIsActiveTrue(UUID id);

    Page<SepsisScreening> findByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(UUID visitId, Pageable pageable);

    /**
     * Get the most recent active screening for a visit.
     */
    Optional<SepsisScreening> findFirstByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(UUID visitId);

    /**
     * Find all active sepsis cases (non-NO_SEPSIS) for a hospital.
     */
    @Query("SELECT s FROM SepsisScreening s JOIN s.visit v WHERE v.hospital.id = :hospitalId " +
            "AND s.isActive = true AND s.sepsisStatus <> 'NO_SEPSIS' " +
            "ORDER BY s.screenedAt DESC")
    List<SepsisScreening> findActiveSepsisCasesByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find screenings where bundle is started but not completed — for bundle compliance monitoring.
     */
    @Query("SELECT s FROM SepsisScreening s WHERE s.isActive = true " +
            "AND s.bundleStartedAt IS NOT NULL AND s.bundleCompletedAt IS NULL")
    List<SepsisScreening> findActiveBundlesInProgress();

    /**
     * Find screenings where sepsis was detected but bundle not yet started — for compliance alerts.
     */
    @Query("SELECT s FROM SepsisScreening s WHERE s.isActive = true " +
            "AND s.sepsisStatus IN :statuses " +
            "AND s.bundleStartedAt IS NULL")
    List<SepsisScreening> findSepsisWithoutBundle(
            @Param("statuses") List<SepsisStatus> statuses);
}
