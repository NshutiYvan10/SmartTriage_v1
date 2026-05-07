package com.smartTriage.smartTriage_server.module.shift.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.LeaveStatus;
import com.smartTriage.smartTriage_server.common.enums.LeaveType;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A planned or retroactive staff absence that affects shift scheduling.
 *
 * <p>An {@link LeaveStatus#APPROVED} row inside the shift's date range tells
 * the planner the user cannot be assigned and contributes to the coverage
 * gap surface. Other statuses are advisory or inert (see {@link LeaveStatus}).
 *
 * <p>This is not an HR record. SmartTriage's responsibility is "who can't
 * be on the floor"; payroll, accrual, and balance tracking are external
 * concerns that a downstream HRIS may attach to via {@link #externalReference}.
 */
@Entity
@Table(name = "staff_leaves", indexes = {
        @Index(name = "idx_staff_leave_user", columnList = "user_id"),
        @Index(name = "idx_staff_leave_hospital", columnList = "hospital_id"),
        @Index(name = "idx_staff_leave_status", columnList = "leave_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffLeave extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 20)
    private LeaveType leaveType;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_status", nullable = false, length = 15)
    @Builder.Default
    private LeaveStatus leaveStatus = LeaveStatus.REQUESTED;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private Instant requestedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id")
    private User requestedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_id")
    private User approvedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by_id")
    private User rejectedBy;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by_id")
    private User cancelledBy;

    /** Opaque identifier for an external HR record, when one exists. */
    @Column(name = "external_reference", length = 120)
    private String externalReference;

    /**
     * @return true iff this leave is approved and the given date falls
     *         inside [startsOn, endsOn] inclusive.
     */
    public boolean blocksDate(LocalDate date) {
        if (leaveStatus != LeaveStatus.APPROVED || !isActive()) {
            return false;
        }
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }
}
