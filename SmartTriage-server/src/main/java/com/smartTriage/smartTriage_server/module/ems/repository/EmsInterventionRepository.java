package com.smartTriage.smartTriage_server.module.ems.repository;

import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmsInterventionRepository extends JpaRepository<EmsIntervention, UUID> {

    List<EmsIntervention> findByEmsRunIdAndIsActiveTrueOrderByGivenAtAsc(UUID emsRunId);
}
