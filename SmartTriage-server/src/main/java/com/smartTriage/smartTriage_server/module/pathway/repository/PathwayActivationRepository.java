package com.smartTriage.smartTriage_server.module.pathway.repository;

import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayActivation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PathwayActivationRepository extends JpaRepository<PathwayActivation, UUID> {

    Optional<PathwayActivation> findByIdAndIsActiveTrue(UUID id);

    /**
     * Active activations for a visit, newest first. JOIN FETCH visit →
     * patient (+ LEFT pathway / currentBed) so the mapper can denormalise
     * patient name / zone / bed for board/list rows without N+1 or
     * LazyInitializationException. Mirrors the medication repo pattern.
     */
    @Query("SELECT a FROM PathwayActivation a "
            + "JOIN FETCH a.visit v "
            + "JOIN FETCH v.patient "
            + "JOIN FETCH a.pathway "
            + "LEFT JOIN FETCH v.currentBed "
            + "WHERE v.id = :visitId AND a.status = :status AND a.isActive = true "
            + "ORDER BY a.activatedAt DESC")
    List<PathwayActivation> findByVisitIdAndStatusAndIsActiveTrueOrderByActivatedAtDesc(
            @Param("visitId") UUID visitId, @Param("status") PathwayActivationStatus status);

    List<PathwayActivation> findByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(UUID visitId);

    boolean existsByVisitIdAndPathwayIdAndStatusAndIsActiveTrue(
            UUID visitId, UUID pathwayId, PathwayActivationStatus status);

    /** Projection for hospital-scope authz — the activation's visit id. */
    @Query("SELECT a.visit.id FROM PathwayActivation a WHERE a.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);

    /** All ACTIVE activations hospital-wide — the compliance monitor scans these for overdue steps. */
    List<PathwayActivation> findByStatusAndIsActiveTrue(PathwayActivationStatus status);
}
