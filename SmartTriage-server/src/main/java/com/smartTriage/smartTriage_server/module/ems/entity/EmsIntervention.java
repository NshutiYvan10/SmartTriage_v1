package com.smartTriage.smartTriage_server.module.ems.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.EmsInterventionType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One pre-hospital intervention attached to an {@link EmsRun}: oxygen
 * delivery, IV access, fluid bolus, drug, defibrillation, etc.
 */
@Entity
@Table(name = "ems_interventions", indexes = {
        @Index(name = "idx_ems_intervention_run", columnList = "ems_run_id"),
        @Index(name = "idx_ems_intervention_given_at", columnList = "given_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmsIntervention extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ems_run_id", nullable = false)
    private EmsRun emsRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private EmsInterventionType type;

    @Column(name = "given_at", nullable = false)
    private Instant givenAt;

    @Column(name = "given_by_name", length = 255)
    private String givenByName;

    /** Free-text description: "O2 6L NRB", "18G L antecubital", "Adrenaline 1mg IV". */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "dose", length = 60)
    private String dose;

    @Column(name = "route", length = 20)
    private String route;

    /** Outcome: "ROSC at 14:08", "tolerated well", "no improvement". */
    @Column(name = "outcome", length = 255)
    private String outcome;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
