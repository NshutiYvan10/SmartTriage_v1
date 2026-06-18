package com.smartTriage.smartTriage_server.module.consent.repository;

import com.smartTriage.smartTriage_server.module.consent.entity.InformedConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InformedConsentRepository extends JpaRepository<InformedConsent, UUID> {

    Optional<InformedConsent> findByIdAndIsActiveTrue(UUID id);

    List<InformedConsent> findByVisitIdAndIsActiveTrueOrderByObtainedAtDesc(UUID visitId);

    /** Hospital-scope authz projection: consent → visit id. */
    @Query("select c.visit.id from InformedConsent c where c.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);
}
