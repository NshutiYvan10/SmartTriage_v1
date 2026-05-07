package com.smartTriage.smartTriage_server.module.shift.repository;

import com.smartTriage.smartTriage_server.module.shift.entity.ChargeNurseDelegation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChargeNurseDelegationRepository extends JpaRepository<ChargeNurseDelegation, UUID> {

    /**
     * Is there any currently-active delegation that grants the given user
     * acting-CN authority at the given hospital? Used by
     * {@code ShiftAssignmentAuthz#canAssign} on every shift-mutation request,
     * so the index {@code idx_cnd_hospital_active_lookup} backs this exact
     * predicate set.
     */
    @Query("""
            SELECT cnd FROM ChargeNurseDelegation cnd
             WHERE cnd.hospital.id = :hospitalId
               AND cnd.delegate.id = :userId
               AND cnd.isActive = true
               AND cnd.revokedAt IS NULL
               AND cnd.startsAt <= :now
               AND (cnd.endsAt IS NULL OR cnd.endsAt > :now)
            """)
    Optional<ChargeNurseDelegation> findActiveDelegationForDelegate(
            @Param("hospitalId") UUID hospitalId,
            @Param("userId") UUID userId,
            @Param("now") Instant now);

    /**
     * All currently-active delegations at a hospital — used by the shift
     * board to render an "Acting CN" badge and by the reminder scheduler.
     */
    @Query("""
            SELECT cnd FROM ChargeNurseDelegation cnd
             WHERE cnd.hospital.id = :hospitalId
               AND cnd.isActive = true
               AND cnd.revokedAt IS NULL
               AND cnd.startsAt <= :now
               AND (cnd.endsAt IS NULL OR cnd.endsAt > :now)
             ORDER BY cnd.startsAt DESC
            """)
    List<ChargeNurseDelegation> findActiveAtHospital(
            @Param("hospitalId") UUID hospitalId,
            @Param("now") Instant now);

    /**
     * Audit / history — every delegation ever created by a given CN,
     * newest first.
     */
    List<ChargeNurseDelegation> findByDelegatingUserIdOrderByStartsAtDesc(UUID delegatingUserId);

    /**
     * Audit — every time this user has been an acting CN.
     */
    List<ChargeNurseDelegation> findByDelegateIdOrderByStartsAtDesc(UUID delegateId);
}
