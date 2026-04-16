package com.smartTriage.smartTriage_server.module.pathway.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * PathwayStepCompletion — tracks the completion of individual steps
 * within an activated pathway.
 */
@Entity
@Table(name = "pathway_step_completions", indexes = {
        @Index(name = "idx_step_completion_activation", columnList = "activation_id"),
        @Index(name = "idx_step_completion_step", columnList = "step_id"),
        @Index(name = "idx_step_completion_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PathwayStepCompletion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activation_id", nullable = false)
    private PathwayActivation activation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false)
    private PathwayStep step;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by_name")
    private String completedByName;

    @Column(name = "was_skipped", nullable = false)
    @Builder.Default
    private boolean wasSkipped = false;

    @Column(name = "skip_reason")
    private String skipReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "time_to_complete_minutes")
    private Integer timeToCompleteMinutes;
}
