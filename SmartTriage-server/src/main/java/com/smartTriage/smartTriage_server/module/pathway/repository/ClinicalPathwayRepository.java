package com.smartTriage.smartTriage_server.module.pathway.repository;

import com.smartTriage.smartTriage_server.common.enums.PathwayCategory;
import com.smartTriage.smartTriage_server.module.pathway.entity.ClinicalPathway;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClinicalPathwayRepository extends JpaRepository<ClinicalPathway, UUID> {

    Optional<ClinicalPathway> findByIdAndIsActiveTrue(UUID id);

    Optional<ClinicalPathway> findByPathwayCodeAndIsActiveTrue(String pathwayCode);

    List<ClinicalPathway> findAllByIsActiveTrueOrderByPathwayNameAsc();

    List<ClinicalPathway> findByCategoryAndIsActiveTrueOrderByPathwayNameAsc(PathwayCategory category);

    boolean existsByPathwayCode(String pathwayCode);
}
