package com.smartTriage.smartTriage_server.module.pathway.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * PathwayActivation — tracks when a clinical pathway is activated for a visit.
 *
 * Represents the "instance" of a pathway being followed for a specific patient.
 * Tracks activation, completion, abandonment, and deviation.
 */
@Entity
@Table(name = "pathway_activations", indexes = {
        @Index(name = "idx_activation_visit", columnList = "visit_id"),
        @Index(name = "idx_activation_pathway", columnList = "pathway_id"),
        @Index(name = "idx_activation_status", columnList = "status"),
        @Index(name = "idx_activation_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PathwayActivation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pathway_id", nullable = false)
    private ClinicalPathway pathway;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "activated_by_name")
    private String activatedByName;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PathwayActivationStatus status = PathwayActivationStatus.ACTIVE;

    @Column(name = "deviation_reason", columnDefinition = "TEXT")
    private String deviationReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
