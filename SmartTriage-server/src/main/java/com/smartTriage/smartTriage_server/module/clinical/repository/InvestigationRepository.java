package com.smartTriage.smartTriage_server.module.clinical.repository;

import com.smartTriage.smartTriage_server.common.enums.InvestigationStatus;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvestigationRepository extends JpaRepository<Investigation, UUID> {

    Page<Investigation> findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(
            UUID visitId, Pageable pageable);

    List<Investigation> findByVisitIdAndIsActiveTrueOrderByOrderedAtAsc(UUID visitId);

    List<Investigation> findByVisitIdAndInvestigationTypeAndIsActiveTrueOrderByOrderedAtDesc(
            UUID visitId, InvestigationType investigationType);

    List<Investigation> findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(
            UUID visitId, InvestigationStatus status);

    Optional<Investigation> findByIdAndIsActiveTrue(UUID id);

    long countByVisitIdAndStatusAndIsActiveTrue(UUID visitId, InvestigationStatus status);
}
