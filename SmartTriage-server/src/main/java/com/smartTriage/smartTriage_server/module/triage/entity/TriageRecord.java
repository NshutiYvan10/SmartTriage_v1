package com.smartTriage.smartTriage_server.module.triage.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * TriageRecord — the medico-legal record of each triage event.
 *
 * Captures EVERY data point from both the Rwanda National Standard
 * Adult Triage Form (Over 12 years) AND the Child Triage Form (3-12 years),
 * including:
 * - Form type indicator (adult vs. child)
 * - All emergency sign checkboxes (Section 1, with child-specific fields)
 * - TEWS components and computed score
 * - All Very Urgent sign checkboxes (Section 2 — shared)
 * - All Urgent sign checkboxes (Section 3 — shared)
 * - Special considerations
 * - The triage decision engine result and decision path
 *
 * A visit may have multiple triage records (initial + re-triages).
 */
@Entity
@Table(name = "triage_records", indexes = {
        @Index(name = "idx_triage_visit", columnList = "visit_id"),
        @Index(name = "idx_triage_category", columnList = "triage_category"),
        @Index(name = "idx_triage_time", columnList = "triage_time"),
        @Index(name = "idx_triage_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TriageRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "triaged_by_id")
    private User triagedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vital_signs_id")
    private VitalSigns vitalSigns;

    @Column(name = "triage_time", nullable = false)
    private Instant triageTime;

    // ====================================================================
    // SECTION 1: EMERGENCY SIGNS
    // ====================================================================

    // Airway / Breathing
    @Column(name = "has_airway_compromise", nullable = false)
    @Builder.Default
    private boolean hasAirwayCompromise = false;

    @Column(name = "has_breathing_distress", nullable = false)
    @Builder.Default
    private boolean hasBreathingDistress = false;

    @Column(name = "has_severe_respiratory_distress", nullable = false)
    @Builder.Default
    private boolean hasSevereRespiratoryDistress = false;

    // Circulation
    @Column(name = "has_cardiac_arrest", nullable = false)
    @Builder.Default
    private boolean hasCardiacArrest = false;

    @Column(name = "has_uncontrolled_haemorrhage", nullable = false)
    @Builder.Default
    private boolean hasUncontrolledHaemorrhage = false;

    @Column(name = "has_stab_gun_wound_neck_chest", nullable = false)
    @Builder.Default
    private boolean hasStabGunWoundNeckChest = false;

    // Convulsions
    @Column(name = "has_convulsions", nullable = false)
    @Builder.Default
    private boolean hasConvulsions = false;

    @Column(name = "convulsion_glucose")
    private Double convulsionGlucose;

    // Coma
    @Column(name = "has_coma", nullable = false)
    @Builder.Default
    private boolean hasComa = false;

    @Column(name = "coma_glucose")
    private Double comaGlucose;

    // Other emergency
    @Column(name = "has_hypoglycaemia", nullable = false)
    @Builder.Default
    private boolean hasHypoglycaemia = false;

    @Column(name = "has_purpuric_rash", nullable = false)
    @Builder.Default
    private boolean hasPurpuricRash = false;

    @Column(name = "has_burn_face_inhalation", nullable = false)
    @Builder.Default
    private boolean hasBurnFaceInhalation = false;

    // ====================================================================
    // SECTION 1b: CHILD-SPECIFIC EMERGENCY SIGNS (Child Triage Form 3-12 years)
    // These columns are NULL/false for adult triages.
    // ====================================================================

    /** Which form was used: true = Child (3-12), false = Adult (Over 12) */
    @Column(name = "is_child_form", nullable = false)
    @Builder.Default
    private boolean isChildForm = false;

    // Airway / Breathing — child-specific
    @Column(name = "child_central_cyanosis", nullable = false)
    @Builder.Default
    private boolean childCentralCyanosis = false;

    // Circulation — child-specific
    @Column(name = "child_pulse_low_or_absent", nullable = false)
    @Builder.Default
    private boolean childPulseLowOrAbsent = false;

    @Column(name = "child_cold_hands_composite", nullable = false)
    @Builder.Default
    private boolean childColdHandsComposite = false;

    @Column(name = "child_cold_hands_lethargic", nullable = false)
    @Builder.Default
    private boolean childColdHandsLethargic = false;

    @Column(name = "child_cold_hands_pulse_weak_fast", nullable = false)
    @Builder.Default
    private boolean childColdHandsPulseWeakFast = false;

    @Column(name = "child_cold_hands_cap_refill", nullable = false)
    @Builder.Default
    private boolean childColdHandsCapRefill = false;

    // Dehydration — child-specific
    @Column(name = "child_severe_dehydration", nullable = false)
    @Builder.Default
    private boolean childSevereDehydration = false;

    @Column(name = "child_dehydration_skin_pinch", nullable = false)
    @Builder.Default
    private boolean childDehydrationSkinPinch = false;

    @Column(name = "child_dehydration_lethargy", nullable = false)
    @Builder.Default
    private boolean childDehydrationLethargy = false;

    @Column(name = "child_dehydration_sunken_eyes", nullable = false)
    @Builder.Default
    private boolean childDehydrationSunkenEyes = false;

    // Child form footer measurements
    @Column(name = "child_weight_kg")
    private Double childWeightKg;

    @Column(name = "child_height_cm")
    private Double childHeightCm;

    // ====================================================================
    // ADDITIONAL VITALS — recorded but not TEWS-scored
    // ====================================================================

    @Column(name = "spo2")
    private Integer spo2;

    @Column(name = "diastolic_bp")
    private Integer diastolicBp;

    @Column(name = "blood_glucose")
    private Double bloodGlucose;

    @Column(name = "pain_score")
    private Integer painScore;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Column(name = "height_cm")
    private Double heightCm;

    // ====================================================================
    // SECTION 2: TEWS COMPONENTS
    // ====================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "mobility", length = 15)
    private MobilityStatus mobility;

    @Enumerated(EnumType.STRING)
    @Column(name = "avpu", length = 15)
    private AvpuScore avpu;

    @Enumerated(EnumType.STRING)
    @Column(name = "trauma_status", length = 15)
    private TraumaStatus traumaStatus;

    // ====================================================================
    // SECTION 3: VERY URGENT SIGNS — Medical
    // ====================================================================

    @Column(name = "vu_focal_neurologic_deficit", nullable = false)
    @Builder.Default
    private boolean vuFocalNeurologicDeficit = false;

    @Column(name = "vu_altered_mental_status", nullable = false)
    @Builder.Default
    private boolean vuAlteredMentalStatus = false;

    @Column(name = "vu_neurological_glucose")
    private Double vuNeurologicalGlucose;

    @Column(name = "vu_chest_pain", nullable = false)
    @Builder.Default
    private boolean vuChestPain = false;

    @Column(name = "vu_poisoning_overdose", nullable = false)
    @Builder.Default
    private boolean vuPoisoningOverdose = false;

    @Column(name = "vu_pregnant_abdominal_pain", nullable = false)
    @Builder.Default
    private boolean vuPregnantAbdominalPain = false;

    @Column(name = "vu_coughing_vomiting_blood", nullable = false)
    @Builder.Default
    private boolean vuCoughingVomitingBlood = false;

    @Column(name = "vu_diabetic_high_glucose", nullable = false)
    @Builder.Default
    private boolean vuDiabeticHighGlucose = false;

    @Column(name = "vu_diabetic_glucose")
    private Double vuDiabeticGlucose;

    @Column(name = "vu_aggression", nullable = false)
    @Builder.Default
    private boolean vuAggression = false;

    @Column(name = "vu_shortness_of_breath", nullable = false)
    @Builder.Default
    private boolean vuShortnessOfBreath = false;

    // ====================================================================
    // SECTION 3: VERY URGENT SIGNS — Trauma
    // ====================================================================

    @Column(name = "vu_burn_over_20_percent", nullable = false)
    @Builder.Default
    private boolean vuBurnOver20Percent = false;

    @Column(name = "vu_open_fracture", nullable = false)
    @Builder.Default
    private boolean vuOpenFracture = false;

    @Column(name = "vu_threatened_limb", nullable = false)
    @Builder.Default
    private boolean vuThreatenedLimb = false;

    @Column(name = "vu_eye_injury", nullable = false)
    @Builder.Default
    private boolean vuEyeInjury = false;

    @Column(name = "vu_large_joint_dislocation", nullable = false)
    @Builder.Default
    private boolean vuLargeJointDislocation = false;

    @Column(name = "vu_severe_mechanism_of_injury", nullable = false)
    @Builder.Default
    private boolean vuSevereMechanismOfInjury = false;

    @Column(name = "vu_very_severe_pain", nullable = false)
    @Builder.Default
    private boolean vuVerySeverePain = false;

    @Column(name = "vu_pregnant_abdominal_trauma", nullable = false)
    @Builder.Default
    private boolean vuPregnantAbdominalTrauma = false;

    // ====================================================================
    // SECTION 4: URGENT SIGNS
    // ====================================================================

    @Column(name = "urg_unable_to_drink_vomits", nullable = false)
    @Builder.Default
    private boolean urgUnableToDrinkVomits = false;

    @Column(name = "urg_abdominal_pain", nullable = false)
    @Builder.Default
    private boolean urgAbdominalPain = false;

    @Column(name = "urg_very_pale", nullable = false)
    @Builder.Default
    private boolean urgVeryPale = false;

    @Column(name = "urg_pregnant_vaginal_bleeding", nullable = false)
    @Builder.Default
    private boolean urgPregnantVaginalBleeding = false;

    @Column(name = "urg_diabetic_very_high_glucose", nullable = false)
    @Builder.Default
    private boolean urgDiabeticVeryHighGlucose = false;

    @Column(name = "urg_diabetic_glucose")
    private Double urgDiabeticGlucose;

    @Column(name = "urg_finger_toe_dislocation", nullable = false)
    @Builder.Default
    private boolean urgFingerToeDislocation = false;

    @Column(name = "urg_closed_fracture", nullable = false)
    @Builder.Default
    private boolean urgClosedFracture = false;

    @Column(name = "urg_burn_without_urgent_signs", nullable = false)
    @Builder.Default
    private boolean urgBurnWithoutUrgentSigns = false;

    @Column(name = "urg_pregnant_trauma_non_abdominal", nullable = false)
    @Builder.Default
    private boolean urgPregnantTraumaNonAbdominal = false;

    @Column(name = "urg_moderate_pain", nullable = false)
    @Builder.Default
    private boolean urgModeratePain = false;

    @Column(name = "urg_laceration_abscess", nullable = false)
    @Builder.Default
    private boolean urgLacerationAbscess = false;

    @Column(name = "urg_foreign_body_aspiration", nullable = false)
    @Builder.Default
    private boolean urgForeignBodyAspiration = false;

    // ====================================================================
    // V38 — Pediatric form compliance — Very Urgent (peds-only)
    // These exist on the KFH Infant and/or Child triage forms but NOT on
    // the adult form. The decision engine reads these only for peds
    // visits; adult visits leave them false.
    // ====================================================================

    @Column(name = "vu_peds_more_sleepy_than_normal", nullable = false)
    @Builder.Default
    private boolean vuPedsMoreSleepyThanNormal = false;

    @Column(name = "vu_peds_inconsolable_severe_pain", nullable = false)
    @Builder.Default
    private boolean vuPedsInconsolableSeverePain = false;

    @Column(name = "vu_peds_floppy_irritable_restless", nullable = false)
    @Builder.Default
    private boolean vuPedsFloppyIrritableRestless = false;

    /** Infant form (0–3) only. Decision engine ignores this for ≥36-month visits. */
    @Column(name = "vu_peds_tiny_baby_under_2_months", nullable = false)
    @Builder.Default
    private boolean vuPedsTinyBabyUnder2Months = false;

    /** Peds burn threshold (10%) — distinct from adult vu_burn_over_20_percent. */
    @Column(name = "vu_peds_burn_over_10_percent", nullable = false)
    @Builder.Default
    private boolean vuPedsBurnOver10Percent = false;

    // ====================================================================
    // V38 — Pediatric form compliance — Urgent (peds-only)
    // ====================================================================

    @Column(name = "urg_peds_pitting_edema_face_or_feet", nullable = false)
    @Builder.Default
    private boolean urgPedsPittingEdemaFaceOrFeet = false;

    @Column(name = "urg_peds_some_respiratory_distress", nullable = false)
    @Builder.Default
    private boolean urgPedsSomeRespiratoryDistress = false;

    @Column(name = "urg_peds_severe_malnutrition_wasting", nullable = false)
    @Builder.Default
    private boolean urgPedsSevereMalnutritionWasting = false;

    /**
     * Peds urgent: "Unwell with known diabetes". Glucose value
     * piggybacks on the existing urg_diabetic_glucose column.
     */
    @Column(name = "urg_peds_unwell_with_known_diabetes", nullable = false)
    @Builder.Default
    private boolean urgPedsUnwellWithKnownDiabetes = false;

    /**
     * Composite "Diarrhoea and/or vomiting plus any of:
     *   sunken eyes, dry mouth, decreased urine output,
     *   skin pinch slow but <2 sec".
     *
     * <p>The composite flag is what drives the URG decision. The four
     * sub-flags below capture what the nurse actually saw, for audit
     * and to enforce the composite at the engine level when the nurse
     * ticked sub-items but missed the composite.
     */
    @Column(name = "urg_peds_diarrhea_vomiting_dehydration", nullable = false)
    @Builder.Default
    private boolean urgPedsDiarrheaVomitingDehydration = false;

    @Column(name = "urg_peds_dehydration_sunken_eyes", nullable = false)
    @Builder.Default
    private boolean urgPedsDehydrationSunkenEyes = false;

    @Column(name = "urg_peds_dehydration_dry_mouth", nullable = false)
    @Builder.Default
    private boolean urgPedsDehydrationDryMouth = false;

    @Column(name = "urg_peds_dehydration_decreased_urine", nullable = false)
    @Builder.Default
    private boolean urgPedsDehydrationDecreasedUrine = false;

    @Column(name = "urg_peds_dehydration_slow_skin_pinch", nullable = false)
    @Builder.Default
    private boolean urgPedsDehydrationSlowSkinPinch = false;

    // ====================================================================
    // COMPUTED SCORES & RESULTS
    // ====================================================================

    @Column(name = "tews_score", nullable = false)
    private int tewsScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "triage_category", nullable = false, length = 10)
    private TriageCategory triageCategory;

    /** The decision path audit trail from RwandaTriageDecisionEngine */
    @Column(name = "decision_path", columnDefinition = "TEXT")
    private String decisionPath;

    // ====================================================================
    // METADATA
    // ====================================================================

    @Column(name = "is_retriage", nullable = false)
    @Builder.Default
    private boolean isRetriage = false;

    @Column(name = "is_system_triggered", nullable = false)
    @Builder.Default
    private boolean isSystemTriggered = false;

    /**
     * Audit link for Round 3 system-triggered re-triages: the
     * `clinical_sign_events.id` whose recording caused this triage
     * record to be created. Null for every manual triage. Stored as
     * a plain UUID rather than a ManyToOne to keep the triage module
     * independent of the clinical-signs module — the FK is enforced
     * at the database level (see V33).
     */
    @Column(name = "triggering_sign_event_id")
    private UUID triggeringSignEventId;

    @Column(name = "previous_category", length = 10)
    @Enumerated(EnumType.STRING)
    private TriageCategory previousCategory;

    @Column(name = "clinical_notes", columnDefinition = "TEXT")
    private String clinicalNotes;

    @Column(name = "presenting_complaints", columnDefinition = "TEXT")
    private String presentingComplaints;

    // ====================================================================
    // SPECIAL CONSIDERATIONS (bottom of triage form)
    // ====================================================================

    @Column(name = "special_acute_trauma", nullable = false)
    @Builder.Default
    private boolean specialAcuteTrauma = false;

    @Column(name = "special_seizure_history", nullable = false)
    @Builder.Default
    private boolean specialSeizureHistory = false;

    @Column(name = "special_assault_abuse", nullable = false)
    @Builder.Default
    private boolean specialAssaultAbuse = false;

    @Column(name = "special_suicide_attempt", nullable = false)
    @Builder.Default
    private boolean specialSuicideAttempt = false;

    // ====================================================================
    // TRIAGE FORM METADATA
    // ====================================================================

    /** Nurse who performed triage — name/signature (from form footer) */
    @Column(name = "triage_nurse_name")
    private String triageNurseName;

    /**
     * Doctor notified for RED/ORANGE (from form: "For RED/ORANGE: Dr. ___ notified
     * at: ___")
     */
    @Column(name = "notified_doctor_name")
    private String notifiedDoctorName;

    /** Time doctor was notified */
    @Column(name = "doctor_notified_at")
    private Instant doctorNotifiedAt;

    /** Doctor who attended (from form: "Dr. ___ Attended at: ___") */
    @Column(name = "attending_doctor_name")
    private String attendingDoctorName;

    /** Time doctor attended */
    @Column(name = "doctor_attended_at")
    private Instant doctorAttendedAt;

    /**
     * V56 — precise audit link to the User row of the notified doctor,
     * when the nurse picked from the on-duty dropdown. NULL when the
     * "Other…" free-text fallback was used (locum / unscheduled doctor),
     * in which case only {@link #notifiedDoctorName} carries the info.
     * Having the UUID lets the alert pipeline route Tier 1 to a specific
     * user instead of broadcasting zone-wide.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notified_doctor_user_id")
    private User notifiedDoctor;

    /** V56 — precise audit link to the attending doctor. Same semantics. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attending_doctor_user_id")
    private User attendingDoctor;
}
