package com.smartTriage.smartTriage_server.module.pathway.repository;

import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayActivation;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
