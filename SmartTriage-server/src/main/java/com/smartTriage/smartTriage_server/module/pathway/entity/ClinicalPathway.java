package com.smartTriage.smartTriage_server.module.pathway.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.PathwayCategory;
import jakarta.persistence.*;
import lombok.*;

/**
 * ClinicalPathway — an evidence-based clinical pathway (protocol/checklist)
 * for managing common emergency presentations.
 *
 * Pathways are pre-defined clinical protocols that guide clinicians through
 * step-by-step management of specific conditions. Each pathway references
 * national or international clinical guidelines.
 *
 * Rwanda-specific pathways are seeded on application startup.
 */
@Entity
@Table(name = "clinical_pathways", indexes = {
        @Index(name = "idx_pathway_code", columnList = "pathway_code", unique = true),
        @Index(name = "idx_pathway_category", columnList = "category"),
        @Index(name = "idx_pathway_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClinicalPathway extends BaseEntity {

    @Column(name = "pathway_code", nullable = false, unique = true, length = 30)
    private String pathwayCode;

    @Column(name = "pathway_name", nullable = false)
    private String pathwayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private PathwayCategory category;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "target_population", length = 30)
    private String targetPopulation;

    @Column(name = "protocol_version", length = 20)
    private String protocolVersion;

    @Column(name = "source_guideline")
    private String sourceGuideline;
}
