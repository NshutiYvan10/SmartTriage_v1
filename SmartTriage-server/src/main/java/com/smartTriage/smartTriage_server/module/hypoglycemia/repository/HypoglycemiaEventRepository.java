package com.smartTriage.smartTriage_server.module.hypoglycemia.repository;

import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HypoglycemiaEventRepository extends JpaRepository<HypoglycemiaEvent, UUID> {

    Optional<HypoglycemiaEvent> findByIdAndIsActiveTrue(UUID id);

    List<HypoglycemiaEvent> findByVisitIdAndIsActiveTrueOrderByDetectedAtDesc(UUID visitId);

    /**
     * Active (unresolved) hypoglycemia events for a hospital.
     */
    @Query("SELECT h FROM HypoglycemiaEvent h JOIN h.visit v WHERE v.hospital.id = :hospitalId " +
            "AND h.isActive = true AND h.resolved = false ORDER BY h.detectedAt DESC")
    List<HypoglycemiaEvent> findActiveEventsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Check for existing unresolved event for a visit — prevents duplicate events.
     */
    boolean existsByVisitIdAndResolvedFalseAndIsActiveTrue(UUID visitId);
}
