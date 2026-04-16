package com.smartTriage.smartTriage_server.module.shift.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * A reusable, per-hospital layout for a given shift period (DAY or NIGHT).
 *
 * Templates are the "default roster" the scheduler materializes into
 * {@link ShiftAssignment} rows at each shift boundary (06:45 / 18:45). A
 * Hospital Admin (or shift lead, on a per-template basis) edits the template;
 * the next shift automatically inherits it.
 *
 * Invariants:
 * <ul>
 *   <li>At most one <b>active</b> template per (hospital, shiftPeriod).
 *       Enforced by partial unique index {@code uk_shift_template_active_per_period}
 *       (see V16 migration). Old templates are soft-deleted, not dropped, so
 *       history is preserved.</li>
 *   <li>The assignment rows inside a template describe default (user → zone
 *       → function) placements. Exactly one of them may carry the shift-lead
 *       badge.</li>
 * </ul>
 */
@Entity
@Table(name = "shift_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftTemplate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_period", nullable = false, length = 15)
    private ShiftPeriod shiftPeriod;

    /**
     * Per-template rows that describe which user works which zone in which
     * function. Cascade-delete so editing a template atomically replaces its
     * roster.
     */
    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ShiftTemplateAssignment> assignments = new ArrayList<>();
}
