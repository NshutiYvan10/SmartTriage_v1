package com.smartTriage.smartTriage_server.module.triage.repository;

import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
