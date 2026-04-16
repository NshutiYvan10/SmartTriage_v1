package com.smartTriage.smartTriage_server.module.pathway.repository;

import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayStepCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PathwayStepCompletionRepository extends JpaRepository<PathwayStepCompletion, UUID> {

    List<PathwayStepCompletion> findByActivationIdAndIsActiveTrueOrderByCompletedAtAsc(UUID activationId);

    Optional<PathwayStepCompletion> findByActivationIdAndStepIdAndIsActiveTrue(UUID activationId, UUID stepId);

    long countByActivationIdAndIsActiveTrue(UUID activationId);

    boolean existsByActivationIdAndStepIdAndIsActiveTrue(UUID activationId, UUID stepId);
}
