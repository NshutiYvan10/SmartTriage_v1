package com.smartTriage.smartTriage_server.module.medsafety.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import jakarta.persistence.*;
import lombok.*;

/**
 * DrugFormulary — reference data for the Rwanda Essential Medicines List (REML)
 * and hospital-specific formulary entries.
 *
 * Stores safe dose ranges, allergen cross-reactivity groups, interaction data,
 * and high-alert medication flags. Used by the MedicationSafetyEngine to
 * validate prescriptions before administration.
 *
 * A null hospital means the entry is system-wide (national REML).
 * A non-null hospital means the entry is hospital-specific.
 */
@Entity
@Table(name = "drug_formularies", indexes = {
        @Index(name = "idx_formulary_generic_name", columnList = "generic_name"),
        @Index(name = "idx_formulary_atc_code", columnList = "atc_code"),
        @Index(name = "idx_formulary_drug_class", columnList = "drug_class"),
        @Index(name = "idx_formulary_hospital", columnList = "hospital_id"),
        @Index(name = "idx_formulary_high_alert", columnList = "is_high_alert"),
        @Index(name = "idx_formulary_reml", columnList = "is_on_reml"),
        @Index(name = "idx_formulary_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DrugFormulary extends BaseEntity {

    /** Generic drug name — e.g., "Paracetamol", "Amoxicillin" */
    @Column(name = "generic_name", nullable = false, length = 255)
    private String genericName;

    /** Comma-separated brand names — e.g., "Tylenol,Panadol,Doliprane" */
    @Column(name = "brand_names", columnDefinition = "TEXT")
    private String brandNames;

    /** Pharmacological drug class — e.g., "Analgesic/Antipyretic", "Aminopenicillin" */
    @Column(name = "drug_class", length = 255)
    private String drugClass;

    /** WHO ATC classification code — e.g., "N02BE01" for paracetamol */
    @Column(name = "atc_code", length = 20)
    private String atcCode;

    /** Rwanda Essential Medicines List category */
    @Column(name = "reml_category", length = 255)
    private String remlCategory;

    // ====================================================================
    // DOSING
    // ====================================================================

    /**
     * Unit the numeric dose bounds below are expressed in (DB column
     * {@code dose_unit}, added by V31). Despite the {@code *_mg} /
     * {@code *_mg_per_kg} column names, the stored numbers are
     * interpreted in THIS unit. Allowed values: MG, MCG, G, UNITS, IU,
     * ML, SACHETS, TABLETS, PUFFS, DROPS.
     *
     * <p>S2: the {@code MedicationSafetyEngine} only runs its mg-based
     * numeric dose-range comparison when this is {@code MG} (the default
     * for the overwhelming majority of REML drugs). For any other unit
     * the stored bounds are not mg-comparable — and in at least one case
     * (Magnesium Sulfate, set to {@code G} in V31 while its values stayed
     * in mg) the column and the values disagree — so a cross-unit numeric
     * comparison would produce false over/under-dose findings. The engine
     * therefore SKIPS the numeric dose-range check for non-MG units
     * (allergy / interaction / duplicate checks still run), matching the
     * documented design intent in V31's header.
     */
    @Column(name = "dose_unit", length = 20, nullable = false)
    @Builder.Default
    private String doseUnit = "MG";

    /** Adult minimum single dose in milligrams */
    @Column(name = "adult_min_dose_mg")
    private Double adultMinDoseMg;

    /** Adult maximum single dose in milligrams */
    @Column(name = "adult_max_dose_mg")
    private Double adultMaxDoseMg;

    /** Adult maximum total daily dose in milligrams */
    @Column(name = "adult_max_daily_dose_mg")
    private Double adultMaxDailyDoseMg;

    /** Pediatric minimum dose in mg/kg body weight */
    @Column(name = "pediatric_min_dose_mg_per_kg")
    private Double pediatricMinDoseMgPerKg;

    /** Pediatric maximum dose in mg/kg body weight */
    @Column(name = "pediatric_max_dose_mg_per_kg")
    private Double pediatricMaxDoseMgPerKg;

    /** Pediatric maximum total daily dose in mg/kg body weight */
    @Column(name = "pediatric_max_daily_dose_mg_per_kg")
    private Double pediatricMaxDailyDoseMgPerKg;

    /** Percentage dose reduction for geriatric patients (e.g., 25 means reduce by 25%) */
    @Column(name = "geriatric_adjustment_percent")
    private Double geriatricAdjustmentPercent;

    /** Whether dose adjustment is required for renal impairment */
    @Column(name = "renal_adjustment_required", nullable = false)
    @Builder.Default
    private boolean renalAdjustmentRequired = false;

    /** Whether dose adjustment is required for hepatic impairment */
    @Column(name = "hepatic_adjustment_required", nullable = false)
    @Builder.Default
    private boolean hepaticAdjustmentRequired = false;

    // ====================================================================
    // ROUTES
    // ====================================================================

    /** Comma-separated valid administration routes — e.g., "PO,IV,IM,RECTAL" */
    @Column(name = "available_routes", length = 255)
    private String availableRoutes;

    // ====================================================================
    // INTERACTIONS
    // ====================================================================

    /** Conditions where drug is contraindicated — e.g., "severe hepatic failure,active GI bleeding" */
    @Column(name = "contraindications", columnDefinition = "TEXT")
    private String contraindications;

    /** Comma-separated drug names with major interactions */
    @Column(name = "major_interactions", columnDefinition = "TEXT")
    private String majorInteractions;

    /** Comma-separated allergen categories — e.g., "penicillin,beta-lactam,sulfa" */
    @Column(name = "allergen_groups", columnDefinition = "TEXT")
    private String allergenGroups;

    // ====================================================================
    // SAFETY
    // ====================================================================

    /** ISMP high-alert medication flag */
    @Column(name = "is_high_alert", nullable = false)
    @Builder.Default
    private boolean isHighAlert = false;

    /** Whether double-check by second clinician is required before administration */
    @Column(name = "requires_double_check", nullable = false)
    @Builder.Default
    private boolean requiresDoubleCheck = false;

    /** FDA black box warning text */
    @Column(name = "black_box_warning", columnDefinition = "TEXT")
    private String blackBoxWarning;

    /** Pregnancy risk category: A, B, C, D, or X */
    @Column(name = "pregnancy_category", length = 5)
    private String pregnancyCategory;

    /** Whether this drug is on the Rwanda Essential Medicines List */
    @Column(name = "is_on_reml", nullable = false)
    @Builder.Default
    private boolean isOnReml = false;

    // ====================================================================
    // SCOPING
    // ====================================================================

    /** Hospital scope — null means system-wide (national REML entry) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id")
    private Hospital hospital;
}
