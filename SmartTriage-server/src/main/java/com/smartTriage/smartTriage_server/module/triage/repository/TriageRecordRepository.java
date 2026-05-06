package com.smartTriage.smartTriage_server.module.triage.repository;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TriageRecordRepository extends JpaRepository<TriageRecord, UUID> {

    Optional<TriageRecord> findByIdAndIsActiveTrue(UUID id);

    Page<TriageRecord> findByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(UUID visitId, Pageable pageable);

    /**
     * Get the most recent triage record for a visit.
     */
    Optional<TriageRecord> findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(UUID visitId);

    long countByVisitIdAndIsActiveTrue(UUID visitId);

    /**
     * Round 3 idempotency guard: returns true when a recent triage record
     * for the visit already sits at or above the target severity, so we
     * don't write duplicate system-triggered re-triages when a doctor
     * records several worsening signs in the same batch.
     *
     * <p>The query intentionally compares severity rather than category
     * equality — a RED record satisfies "at least RED" idempotency for
     * any subsequent EMERGENCY-driven bump.
     */
    @Query("SELECT COUNT(t) > 0 FROM TriageRecord t " +
            "WHERE t.visit.id = :visitId AND t.isActive = true " +
            "AND t.triageTime >= :since " +
            "AND t.triageCategory IN :categories")
    boolean hasRecentTriageAtOrAboveCategory(
            @Param("visitId") UUID visitId,
            @Param("since") Instant since,
            @Param("categories") java.util.Collection<TriageCategory> categoriesAtOrAbove);
}
