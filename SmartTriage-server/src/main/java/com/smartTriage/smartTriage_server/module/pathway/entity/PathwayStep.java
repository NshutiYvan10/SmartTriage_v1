package com.smartTriage.smartTriage_server.module.pathway.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * PathwayStep — a single step in a clinical pathway.
 *
 * Steps are ordered and may have time targets. Mandatory steps
 * must be completed (or explicitly skipped with reason) before
 * the pathway can be marked complete.
 */
@Entity
@Table(name = "pathway_steps", indexes = {
        @Index(name = "idx_step_pathway", columnList = "pathway_id"),
        @Index(name = "idx_step_order", columnList = "step_order"),
        @Index(name = "idx_step_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PathwayStep extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pathway_id", nullable = false)
    private ClinicalPathway pathway;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "step_title", nullable = false)
    private String stepTitle;

    @Column(name = "step_description", nullable = false, columnDefinition = "TEXT")
    private String stepDescription;

    @Column(name = "timeframe_minutes")
    private Integer timeframeMinutes;

    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private boolean isMandatory = true;

    @Column(name = "category", length = 30)
    private String category;
}
