package com.smartTriage.smartTriage_server.module.invitation.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Invitation token — a time-limited, single-use token sent via email
 * for new users to activate their account and set their password.
 */
@Entity
@Table(name = "invitation_tokens", indexes = {
        @Index(name = "idx_invitation_token", columnList = "token", unique = true),
        @Index(name = "idx_invitation_user_id", columnList = "user_id"),
        @Index(name = "idx_invitation_expires", columnList = "expires_at"),
        @Index(name = "idx_invitation_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvitationToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    public boolean isUsed() {
        return this.usedAt != null;
    }
}
