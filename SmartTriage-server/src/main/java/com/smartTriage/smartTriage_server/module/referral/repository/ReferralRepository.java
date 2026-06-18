package com.smartTriage.smartTriage_server.module.referral.repository;

import com.smartTriage.smartTriage_server.module.referral.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    Optional<Referral> findByIdAndIsActiveTrue(UUID id);

    List<Referral> findByVisitIdAndIsActiveTrueOrderByRequestedAtDesc(UUID visitId);

    /** Hospital-scope authz projection: referral → visit id. */
    @Query("select r.visit.id from Referral r where r.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);
}
