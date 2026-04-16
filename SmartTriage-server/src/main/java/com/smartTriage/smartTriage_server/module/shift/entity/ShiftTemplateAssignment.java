package com.smartTriage.smartTriage_server.module.shift.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

/**
 * A single row inside a {@link ShiftTemplate} — "nurse X works zone RESUS as
 * ZONE_NURSE whenever this template is applied".
 *
 * When the scheduler materializes a template at a shift boundary, each of
 * these rows becomes a concrete {@link ShiftAssignment} row for that specific
 * shift date / period. Exactly one row per template may carry
 * {@code isShiftLead = true}; enforced by partial unique index
 * {@code uk_shift_template_lead} (see V16 migration).
 */
@Entity
@Table(name = "shift_template_assignments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_shift_template_user", columnNames = { "template_id", "user_id" })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftTemplateAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private ShiftTemplate template;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone", nullable = false, length = 20)
    private EdZone zone;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_function", nullable = false, length = 30)
    private ShiftFunction shiftFunction;

    /**
     * Whether this template row should materialize as the shift-lead for the
     * resulting shift. At most one per template (partial unique index).
     */
    @Column(name = "is_shift_lead", nullable = false)
    @Builder.Default
    private boolean isShiftLead = false;
}
