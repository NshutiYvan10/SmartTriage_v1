package com.smartTriage.smartTriage_server.module.clinical.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.InvestigationStatus;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Investigation — tracks diagnostic investigations ordered during an ED visit.
 *
 * The Rwanda triage forms include an "Investigations" section that captures:
 *   - Laboratory tests (FBC, U&E, glucose, blood gas, etc.)
 *   - Radiology (X-ray, CT, ultrasound, MRI)
 *   - ECG
 *   - Point-of-care testing (malaria RDT, urinalysis, blood glucose)
 *
 * Each investigation tracks the full lifecycle:
 *   ORDERED → SPECIMEN_COLLECTED → IN_PROGRESS → RESULTED / CANCELLED
 */
@Entity
@Table(name = "investigations", indexes = {
        @Index(name = "idx_investigation_visit", columnList = "visit_id"),
        @Index(name = "idx_investigation_type", columnList = "investigation_type"),
        @Index(name = "idx_investigation_status", columnList = "status"),
        @Index(name = "idx_investigation_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Investigation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    /** Category of investigation */
    @Enumerated(EnumType.STRING)
    @Column(name = "investigation_type", nullable = false, length = 30)
    private InvestigationType investigationType;

    /** Specific test name (e.g., "Full Blood Count", "Chest X-ray PA") */
    @Column(name = "test_name", nullable = false, length = 255)
    private String testName;

    /** Name of the clinician who ordered this investigation */
    @Column(name = "ordered_by_name", length = 255)
    private String orderedByName;

    /**
     * V62 — Doctor User FK. Service stamps it on create from the
     * SecurityContext so the doctor's aggregate "my investigations"
     * view filters by FK (reliable) instead of name match (typo-
     * prone). Nullable for backward compat with pre-V62 rows; the
     * doctor query falls back to a case-insensitive name match
     * when this is null.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordered_by_id")
    private User orderedBy;

    /** Time the investigation was ordered */
    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;

    /** Time the specimen was collected (for lab tests) */
    @Column(name = "specimen_collected_at")
    private Instant specimenCollectedAt;

    /** Time results were available */
    @Column(name = "resulted_at")
    private Instant resultedAt;

    /** Result summary — free text or structured depending on type */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /**
     * Phase 12b — principal scalar value of the result, e.g. 1.8 for
     * a creatinine reported as "Cr 1.8 mg/dL". Lets downstream
     * calculators (Cockcroft-Gault eGFR, sepsis scoring) read a
     * number without parsing free text.
     */
    @Column(name = "result_numeric")
    private Double resultNumeric;

    /**
     * Phase 12b — unit for resultNumeric. Free text because lab units
     * vary by site (mg/dL vs µmol/L for creatinine, g/dL vs g/L for
     * haemoglobin, mmol/L vs mEq/L for electrolytes).
     */
    @Column(name = "result_unit", length = 32)
    private String resultUnit;

    /** Whether the result is abnormal / critical */
    @Column(name = "is_abnormal")
    @Builder.Default
    private Boolean isAbnormal = false;

    /** Critical value flag — triggers alert */
    @Column(name = "is_critical")
    @Builder.Default
    private Boolean isCritical = false;

    /** Current status of this investigation */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    @Builder.Default
    private InvestigationStatus status = InvestigationStatus.ORDERED;

    /** Priority: STAT, URGENT, ROUTINE */
    @Column(name = "priority", length = 20)
    private String priority;

    /** Clinical notes */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
