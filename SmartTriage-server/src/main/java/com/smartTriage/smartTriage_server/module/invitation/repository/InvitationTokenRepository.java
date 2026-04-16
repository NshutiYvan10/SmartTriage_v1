package com.smartTriage.smartTriage_server.module.invitation.repository;

import com.smartTriage.smartTriage_server.module.invitation.entity.InvitationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationTokenRepository extends JpaRepository<InvitationToken, UUID> {

    Optional<InvitationToken> findByTokenAndIsActiveTrue(String token);

    /** Find the latest active (unused) invitation for a user */
    Optional<InvitationToken> findFirstByUserIdAndUsedAtIsNullAndIsActiveTrueOrderByCreatedAtDesc(UUID userId);
}
