package com.smartTriage.smartTriage_server.module.vital.repository;

import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VitalSignsRepository extends JpaRepository<VitalSigns, UUID> {

    Optional<VitalSigns> findByIdAndIsActiveTrue(UUID id);

    Page<VitalSigns> findByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(UUID visitId, Pageable pageable);

    /**
     * Get the most recent vital signs for a visit — critical for TEWS recalculation.
     */
    Optional<VitalSigns> findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(UUID visitId);

    /**
     * Get vitals within a time range — for trend analysis and deterioration detection.
     */
    @Query("SELECT v FROM VitalSigns v WHERE v.visit.id = :visitId AND v.isActive = true " +
            "AND v.recordedAt BETWEEN :from AND :to ORDER BY v.recordedAt ASC")
    List<VitalSigns> findVitalsInTimeRange(
            @Param("visitId") UUID visitId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
