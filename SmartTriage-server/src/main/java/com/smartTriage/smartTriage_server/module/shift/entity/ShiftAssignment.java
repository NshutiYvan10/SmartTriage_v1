package com.smartTriage.smartTriage_server.module.shift.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Records which staff member is assigned to which ED zone during a specific
 * shift.
 * Set by the charge nurse at the start of each shift.
 *
 * Example:
 * 2026-03-05 | MORNING | Dr. Kamanzi | ACUTE | PRIMARY_DOCTOR
 * 2026-03-05 | MORNING | Dr. Mugisha | RESUS | PRIMARY_DOCTOR
 * 2026-03-05 | MORNING | Nurse Uwase | ACUTE | ZONE_NURSE
 * 2026-03-05 | MORNING | Nurse Habimana | TRIAGE | TRIAGE_NURSE
 */
@Entity
@Table(name = "shift_assignments", indexes = {
        @Index(name = "idx_shift_hospital_date", columnList = "hospital_id, shift_date, shift_period"),
        @Index(name = "idx_shift_user", columnList = "user_id"),
        @Index(name = "idx_shift_zone", columnList = "zone"),
        @Index(name = "idx_shift_active", columnList = "is_active")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_shift_user_date_period", columnNames = { "user_id", "shift_date", "shift_period" })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_period", nullable = false, length = 15)
    private ShiftPeriod shiftPeriod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone", nullable = false, length = 20)
    private EdZone zone;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_function", nullable = false, length = 30)
    private ShiftFunction shiftFunction;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /**
     * Shift-lead badge — authority bound to this shift row, not to a specific
     * user. Exactly one active shift assignment per (hospital, shiftDate,
     * shiftPeriod) may have this flag set; enforced by the partial unique
     * index {@code uk_shift_lead_per_shift} (see V16 migration).
     *
     * The user carrying this flag is the current Charge Nurse / Shift Lead
     * for that period and has authority to assign other staff to zones.
     */
    @Column(name = "is_shift_lead", nullable = false)
    @Builder.Default
    private boolean isShiftLead = false;

    /**
     * V55 — back-link to the {@link ShiftTemplate} this assignment was
     * applied from. NULL means the row was created manually (direct
     * assignToZone call). When a Charge Nurse edits the template, the
     * propagation logic uses this column to find every future calendar
     * slot that originated from that template. Manual rows are
     * deliberately untouched by template updates.
     *
     * ON DELETE SET NULL — deleting a template doesn't cascade-delete
     * the materialized assignments, just severs the back-link.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private ShiftTemplate template;
}
