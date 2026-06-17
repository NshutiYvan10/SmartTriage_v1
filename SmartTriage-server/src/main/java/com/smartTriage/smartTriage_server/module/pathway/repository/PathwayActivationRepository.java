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

    List<PathwayActivation> findByVisitIdAndStatusAndIsActiveTrueOrderByActivatedAtDesc(
            UUID visitId, PathwayActivationStatus status);

    List<PathwayActivation> findByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(UUID visitId);

    boolean existsByVisitIdAndPathwayIdAndStatusAndIsActiveTrue(
            UUID visitId, UUID pathwayId, PathwayActivationStatus status);

    /** Projection for hospital-scope authz — the activation's visit id. */
    @Query("SELECT a.visit.id FROM PathwayActivation a WHERE a.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);

    /** All ACTIVE activations hospital-wide — the compliance monitor scans these for overdue steps. */
    List<PathwayActivation> findByStatusAndIsActiveTrue(PathwayActivationStatus status);
}
