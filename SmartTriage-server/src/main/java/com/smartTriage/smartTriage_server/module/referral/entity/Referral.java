package com.smartTriage.smartTriage_server.module.referral.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ReferralStatus;
import com.smartTriage.smartTriage_server.common.enums.ReferralType;
import com.smartTriage.smartTriage_server.common.enums.ReferralUrgency;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Referral / Consultation — a structured request to another specialty (or
 * facility, or ICU) AND the consultant's structured reply.
 *
 * <p>Captures the request (specialty, urgency, reason, specific clinical
 * question) with its authenticated requester, and the response (accept / decline
 * / complete) with the authenticated responder and their reply notes. Both
 * identities are derived server-side from the security principal.
 */
@Entity
@Table(name = "referrals", indexes = {
        @Index(name = "idx_referral_visit", columnList = "visit_id"),
        @Index(name = "idx_referral_status", columnList = "status"),
        @Index(name = "idx_referral_specialty", columnList = "specialty"),
        @Index(name = "idx_referral_requested_by", columnList = "requested_by_user_id"),
        @Index(name = "idx_referral_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(name = "referral_type", nullable = false, length = 30)
    private ReferralType referralType;

    /** Target specialty / service (e.g. "Cardiology", "ICU", "General Surgery"). */
    @Column(name = "specialty", nullable = false)
    private String specialty;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency", nullable = false, length = 20)
    private ReferralUrgency urgency;

    @Column(name = "reason_for_referral", nullable = false, columnDefinition = "TEXT")
    private String reasonForReferral;

    /** A specific question the requester wants the consultant to address. */
    @Column(name = "clinical_question", columnDefinition = "TEXT")
    private String clinicalQuestion;

    /** Destination facility for an external referral. */
    @Column(name = "target_facility")
    private String targetFacility;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReferralStatus status;

    // ====================================================================
    // REQUESTER (authenticated)
    // ====================================================================

    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;

    @Column(name = "requested_by_name", nullable = false)
    private String requestedByName;

    @Column(name = "requested_by_role")
    private String requestedByRole;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    // ====================================================================
    // RESPONSE (authenticated consultant)
    // ====================================================================

    @Column(name = "responded_by_user_id")
    private UUID respondedByUserId;

    @Column(name = "responded_by_name")
    private String respondedByName;

    @Column(name = "responded_by_role")
    private String respondedByRole;

    @Column(name = "responded_at")
    private Instant respondedAt;

    /** The consultant's structured reply — assessment + recommendations. */
    @Column(name = "response_notes", columnDefinition = "TEXT")
    private String responseNotes;

    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
