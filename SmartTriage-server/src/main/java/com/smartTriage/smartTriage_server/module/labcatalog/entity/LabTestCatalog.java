package com.smartTriage.smartTriage_server.module.labcatalog.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import jakarta.persistence.*;
import lombok.*;

/**
 * LabTestCatalog — reference data for laboratory and diagnostic tests.
 *
 * Drives the investigation-order autocomplete on the doctor's chart so
 * the doctor never has to type test names from scratch. Each entry gives
 * the canonical test name, its investigation type (LABORATORY / RADIOLOGY /
 * etc., aligned with the existing InvestigationType enum), the specimen
 * type (whole blood, serum, urine, …) and a typical turnaround time the
 * lab can be expected to meet for STAT vs ROUTINE.
 *
 * Seeded by V24 with tests routinely available in Rwandan hospitals.
 * SUPER_ADMIN can extend the catalog over time.
 */
@Entity
@Table(name = "lab_test_catalog", indexes = {
        @Index(name = "idx_lab_test_name", columnList = "test_name"),
        @Index(name = "idx_lab_test_type", columnList = "investigation_type"),
        @Index(name = "idx_lab_test_common", columnList = "is_common_in_rwanda"),
        @Index(name = "idx_lab_test_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabTestCatalog extends BaseEntity {

    /** Canonical test name, e.g. "Full Blood Count", "Malaria Rapid Diagnostic Test". */
    @Column(name = "test_name", nullable = false, length = 200)
    private String testName;

    /** Common short alias / synonym, e.g. "FBC", "mRDT", "U&E". */
    @Column(name = "short_name", length = 50)
    private String shortName;

    /** Maps to the existing InvestigationType enum (LABORATORY / RADIOLOGY / ECG / …). */
    @Enumerated(EnumType.STRING)
    @Column(name = "investigation_type", nullable = false, length = 30)
    private InvestigationType investigationType;

    /** Pharmacological / pathological category, e.g. "Hematology", "Biochemistry", "Microbiology". */
    @Column(name = "category", length = 100)
    private String category;

    /** Specimen required, e.g. "EDTA whole blood", "Serum", "Urine", "Sputum". */
    @Column(name = "specimen_type", length = 100)
    private String specimenType;

    /** Typical turnaround time in minutes for STAT processing (informational). */
    @Column(name = "stat_turnaround_minutes")
    private Integer statTurnaroundMinutes;

    /** Typical turnaround time in minutes for ROUTINE processing (informational). */
    @Column(name = "routine_turnaround_minutes")
    private Integer routineTurnaroundMinutes;

    /**
     * Common reference / clinical-use note shown to the doctor when the
     * test is selected — e.g. "Confirms or excludes malaria". Kept short.
     */
    @Column(name = "clinical_use", columnDefinition = "TEXT")
    private String clinicalUse;

    /**
     * Pinned to the top of search results when true. Set for the routine
     * Rwandan ED panel: FBC, U&E, glucose, malaria RDT, HIV test, etc.
     */
    @Column(name = "is_common_in_rwanda", nullable = false)
    @Builder.Default
    private boolean isCommonInRwanda = false;

    // ── Result interpretation (V81) — drives unit-safe critical-value detection,
    //    the abnormal-vs-range flag, and result-entry pre-fill. NULL for panels and
    //    qualitative tests where a single numeric threshold isn't meaningful. ──

    /** Canonical unit a numeric result is expected in (e.g. "mmol/L", "µmol/L", "pH").
     *  The critical-value engine only auto-evaluates when the entered result unit
     *  matches this, so a value in a different unit is never mis-flagged. */
    @Column(name = "result_unit", length = 30)
    private String resultUnit;

    /** Lower bound of the normal reference range (in {@link #resultUnit}). */
    @Column(name = "reference_low")
    private Double referenceLow;

    /** Upper bound of the normal reference range (in {@link #resultUnit}). */
    @Column(name = "reference_high")
    private Double referenceHigh;

    /** Critical (panic) low threshold — at/below requires immediate notification.
     *  NULL when the test has no clinically-meaningful critical low. */
    @Column(name = "critical_low")
    private Double criticalLow;

    /** Critical (panic) high threshold — at/above requires immediate notification.
     *  NULL when the test has no clinically-meaningful critical high. */
    @Column(name = "critical_high")
    private Double criticalHigh;
}
