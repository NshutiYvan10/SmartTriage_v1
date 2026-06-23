package com.smartTriage.smartTriage_server.module.consent.repository;

import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BreakTheGlassEventRepository extends JpaRepository<BreakTheGlassEvent, UUID> {

    List<BreakTheGlassEvent> findByPersonIdentityIdAndIsActiveTrueOrderByAccessedAtDesc(UUID personIdentityId);

    Optional<BreakTheGlassEvent> findByIdAndIsActiveTrue(UUID id);

    /**
     * Governance feed (all time): every break-the-glass override attributed to a hospital's
     * clinicians (the actor's home hospital — the team with authority to review them), newest
     * first. Forensic surface like the medication Override Audit: returns all events regardless
     * of acknowledgement.
     */
    Page<BreakTheGlassEvent> findByActorHospitalIdAndIsActiveTrueOrderByAccessedAtDesc(
            UUID actorHospitalId, Pageable pageable);

    /** Governance feed bounded to a time window (range filters). */
    Page<BreakTheGlassEvent> findByActorHospitalIdAndAccessedAtGreaterThanEqualAndIsActiveTrueOrderByAccessedAtDesc(
            UUID actorHospitalId, Instant from, Pageable pageable);
}
