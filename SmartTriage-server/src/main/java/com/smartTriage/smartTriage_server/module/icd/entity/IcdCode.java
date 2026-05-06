package com.smartTriage.smartTriage_server.module.icd.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * IcdCode — clinical reference data for diagnoses.
 *
 * Drives the diagnosis-entry autocomplete on the doctor's chart so the
 * doctor never has to memorise or type ICD-10 codes. Each row is a single
 * diagnosable condition with its WHO ICD-10 code, a clinical description,
 * the ICD chapter category, and a flag identifying which conditions are
 * common in the Rwandan ED context (pinned to the top of search results).
 *
 * Seeded by V23 from the WHO ICD-10 list, scoped to conditions a Rwandan
 * ED is likely to actually diagnose. New entries can be added via SUPER_ADMIN
 * over time.
 */
@Entity
@Table(name = "icd_codes", indexes = {
        @Index(name = "idx_icd_code", columnList = "code"),
        @Index(name = "idx_icd_description", columnList = "description"),
        @Index(name = "idx_icd_category", columnList = "category"),
        @Index(name = "idx_icd_common_rwanda", columnList = "is_common_in_rwanda"),
        @Index(name = "idx_icd_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IcdCode extends BaseEntity {

    /** ICD-10 code, e.g. "B50.9", "A41.9". Always uppercase in storage. */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    /** Clinical description, e.g. "Plasmodium falciparum malaria, unspecified". */
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /** ICD chapter category — e.g. "Infectious diseases", "Trauma", "Cardiovascular". */
    @Column(name = "category", length = 100)
    private String category;

    /**
     * Flagged true for diagnoses common in the Rwandan ED context (malaria,
     * typhoid, sepsis, HIV, TB, trauma, asthma, etc.). Pinned to the top of
     * autocomplete results so the most frequent conditions are one keystroke
     * away.
     */
    @Column(name = "is_common_in_rwanda", nullable = false)
    @Builder.Default
    private boolean isCommonInRwanda = false;

    /**
     * Optional clinical notes for the diagnosis — e.g. "Test with mRDT or
     * thick smear before treatment". Surfaced in the diagnosis form when
     * the code is selected.
     */
    @Column(name = "clinical_notes", columnDefinition = "TEXT")
    private String clinicalNotes;
}
