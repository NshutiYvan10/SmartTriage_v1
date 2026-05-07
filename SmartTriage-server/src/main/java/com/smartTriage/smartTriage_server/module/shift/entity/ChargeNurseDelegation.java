package com.smartTriage.smartTriage_server.module.shift.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Acting Charge Nurse delegation.
 *
 * <p>Records that the on-duty Charge Nurse (CN) has delegated their
 * shift-management authority to a named acting CN for a defined window.
 * While a row is <em>active</em> (see {@link #isCurrentlyActive(Instant)}),
 * the {@code delegate_user_id} carries
 * {@link com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentAuthz#canAssign(org.springframework.security.core.Authentication, java.util.UUID)}
 * authority for the {@link #hospital} as if they were the on-duty CN.
 *
 * <h2>Why this exists</h2>
 * Rwandan EDs operate with a single CN per shift. When the CN steps off the
 * floor for a finite period (off-site meeting, urgent personal matter), there
 * has to be a documented authority transfer — otherwise the unit either
 * routes Tier 1 alerts to an unattended phone, or hands authority informally
 * over WhatsApp with no audit trail. Both are silent-failure modes that the
 * SmartTriage clinical-safety standard rules out.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>The delegation is <b>additive</b>: both the original CN and the
 *       delegate hold authority while active. The delegating CN can still
 *       act if they happen to be reachable.</li>
 *   <li>{@link #endsAt} {@code NULL} means open-ended ("until I revoke");
 *       the UI surfaces these prominently and the reminder scheduler nags
 *       after 24h.</li>
 *   <li>Early termination uses {@link #revokedAt}, not row deletion —
 *       clinical audit data is never destroyed.</li>
 * </ul>
 */
@Entity
@Table(name = "charge_nurse_delegations", indexes = {
        @Index(name = "idx_cnd_hospital_active_lookup",
               columnList = "hospital_id, delegate_user_id, revoked_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChargeNurseDelegation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    /** The Charge Nurse delegating authority. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegating_user_id", nullable = false)
    private User delegatingUser;

    /** The acting CN — typically a Senior Nurse. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegate_user_id", nullable = false)
    private User delegate;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** {@code null} = open-ended (until revoked). */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by_id")
    private User revokedBy;

    @Column(name = "revocation_reason", columnDefinition = "TEXT")
    private String revocationReason;

    /**
     * @return true iff this delegation is currently in effect at {@code now}.
     *         Mirrors the WHERE-clause used by the active-delegation index.
     */
    public boolean isCurrentlyActive(Instant now) {
        if (!isActive() || revokedAt != null) {
            return false;
        }
        if (now.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || now.isBefore(endsAt);
    }
}
