package com.smartTriage.smartTriage_server.module.shift.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.SwapStatus;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Peer-to-peer shift trade request.
 *
 * <p>Encapsulates a proposed swap of two existing
 * {@link ShiftAssignment} rows belonging to two different staff members at
 * the same hospital. The trade only takes effect on the APPROVED transition,
 * which is gated on the Charge Nurse confirming that the resulting roster
 * still satisfies clinical-competence requirements.
 *
 * <p>While a request is in any non-terminal state, both underlying
 * assignments are "locked" by the partial unique indices
 * {@code uk_swap_requester_open_assignment} and
 * {@code uk_swap_partner_open_assignment} — so no second swap can pick up
 * either row until this one resolves.
 *
 * <p>See {@link SwapStatus} for the state diagram.
 */
@Entity
@Table(name = "shift_swap_requests", indexes = {
        @Index(name = "idx_swap_hospital", columnList = "hospital_id"),
        @Index(name = "idx_swap_status",   columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftSwapRequest extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_assignment_id", nullable = false)
    private ShiftAssignment requesterAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_assignment_id", nullable = false)
    private ShiftAssignment partnerAssignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_user_id", nullable = false)
    private User requesterUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_user_id", nullable = false)
    private User partnerUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private SwapStatus status = SwapStatus.REQUESTED;

    @Column(name = "request_reason", columnDefinition = "TEXT")
    private String requestReason;

    @Column(name = "partner_responded_at")
    private Instant partnerRespondedAt;

    @Column(name = "partner_response_note", columnDefinition = "TEXT")
    private String partnerResponseNote;

    @Column(name = "charge_responded_at")
    private Instant chargeRespondedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charge_responder_id")
    private User chargeResponder;

    @Column(name = "charge_response_note", columnDefinition = "TEXT")
    private String chargeResponseNote;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;
}
