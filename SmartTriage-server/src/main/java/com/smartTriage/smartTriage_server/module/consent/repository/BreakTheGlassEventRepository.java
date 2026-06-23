package com.smartTriage.smartTriage_server.module.consent.repository;

import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BreakTheGlassEventRepository extends JpaRepository<BreakTheGlassEvent, UUID> {

    List<BreakTheGlassEvent> findByPersonIdentityIdAndIsActiveTrueOrderByAccessedAtDesc(UUID personIdentityId);
}
