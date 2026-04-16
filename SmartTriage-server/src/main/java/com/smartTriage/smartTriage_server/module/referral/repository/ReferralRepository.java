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

    Optional<Referral> findFirstByVisitIdAndIsActiveTrueOrderByInitiatedAtDesc(UUID visitId);

    /**
     * Active referrals for a hospital — not yet completed or cancelled.
     */
    @Query("SELECT r FROM Referral r WHERE r.referringHospital.id = :hospitalId " +
            "AND r.isActive = true " +
            "AND r.status NOT IN ('COMPLETED', 'CANCELLED') " +
            "ORDER BY r.initiatedAt DESC")
    List<Referral> findActiveReferralsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Check if a visit already has an active (non-terminal) referral.
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Referral r " +
            "WHERE r.visit.id = :visitId AND r.isActive = true " +
            "AND r.status NOT IN ('COMPLETED', 'CANCELLED', 'DECLINED')")
    boolean existsActiveReferralForVisit(@Param("visitId") UUID visitId);
}
