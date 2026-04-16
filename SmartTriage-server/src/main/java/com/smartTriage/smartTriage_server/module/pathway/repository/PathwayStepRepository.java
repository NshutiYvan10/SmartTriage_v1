package com.smartTriage.smartTriage_server.module.pathway.repository;

import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PathwayStepRepository extends JpaRepository<PathwayStep, UUID> {

    Optional<PathwayStep> findByIdAndIsActiveTrue(UUID id);

    List<PathwayStep> findByPathwayIdAndIsActiveTrueOrderByStepOrderAsc(UUID pathwayId);

    long countByPathwayIdAndIsActiveTrue(UUID pathwayId);

    List<PathwayStep> findByPathwayIdAndIsMandatoryTrueAndIsActiveTrueOrderByStepOrderAsc(UUID pathwayId);
}
