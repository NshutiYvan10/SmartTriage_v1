package com.smartTriage.smartTriage_server.module.triage.dto;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to perform initial triage or re-triage on a visit.
 *
 * This DTO captures EVERY checkbox and data point from both the
 * Rwanda National Standard Adult Triage Form (Over 12 years) AND the
 * Rwanda National Standard Child Triage Form (3-12 years).
 *
 * The back of the child form (Very Urgent / Urgent signs) is identical
 * to the adult form. The FRONT differs in:
 * - Emergency Signs (child has dehydration, central cyanosis, etc.)
 * - TEWS thresholds (handled by PediatricTewsCalculator, not DTO fields)
 *
 * Organized in the exact order of the physical form:
 * Section 1: Emergency Signs (adult + child-specific)
 * Section 2: TEWS Components (Mobility, AVPU, Trauma)
 * Section 3: Very Urgent Signs (Medical + Trauma) — shared
 * Section 4: Urgent Signs — shared
 * Section 5: Clinical metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformTriageRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    // ====================================================================
    // SECTION 1: EMERGENCY SIGNS — "Emergency Signs? CHECK THE COMPLAINT"
    // Any YES → RED (Immediate Resuscitation / ALARM)
    // ====================================================================

    // Airway / Breathing
    /** Not breathing OR Obstructed breathing */
    @Builder.Default
    private boolean hasAirwayCompromise = false;

    /** Severe respiratory distress (separate from airway obstruction) */
    @Builder.Default
    private boolean hasBreathingDistress = false;

    /** Severe respiratory distress — explicit form checkbox */
    @Builder.Default
    private boolean hasSevereRespiratoryDistress = false;

    // Circulation
    /** Cardiac arrest */
    @Builder.Default
    private boolean hasCardiacArrest = false;

    /** Haemorrhage – uncontrolled */
    @Builder.Default
    private boolean hasUncontrolledHaemorrhage = false;

    /** Stab/gunshot wound to neck or chest */
    @Builder.Default
    private boolean hasStabGunWoundNeckChest = false;

    // Convulsions
    /** Current seizure or post ictal (not alert) */
    @Builder.Default
    private boolean hasConvulsions = false;

    /** Glucose reading associated with convulsions (mmol/L) */
    private Double convulsionGlucose;

    // Coma
    /** Unresponsive or responsive only to pain */
    @Builder.Default
    private boolean hasComa = false;

    /** Glucose reading associated with coma (mmol/L) */
    private Double comaGlucose;

    // Other emergency signs
    /** Hypoglycaemia: Glucose < 3 mmol/L or 60 mg/dL */
    @Builder.Default
    private boolean hasHypoglycaemia = false;

    /** Purpuric rash */
    @Builder.Default
    private boolean hasPurpuricRash = false;

    /** Burn – face/inhalation */
    @Builder.Default
    private boolean hasBurnFaceInhalation = false;

    // ====================================================================
    // SECTION 1b: CHILD-SPECIFIC EMERGENCY SIGNS
    // These fields are ONLY populated when using the Child Triage Form (3-12
    // years).
    // They correspond to checkboxes that appear on the child form but NOT
    // on the adult form.
    // ====================================================================

    // Airway / Breathing — child-specific
    /** Central cyanosis (child form only) */
    @Builder.Default
    private boolean childCentralCyanosis = false;

    // Circulation — child-specific
    /** Pulse low or absent (child form only) */
    @Builder.Default
    private boolean childPulseLowOrAbsent = false;

    /**
     * Cold hands PLUS at least one of:
     * □ lethargic □ pulse weak and fast □ cap refill ≥ 3 sec
     * This is a composite sign on the child form (child form only).
     */
    @Builder.Default
    private boolean childColdHandsComposite = false;

    /** Cold hands + lethargic (sub-checkbox of cold hands composite) */
    @Builder.Default
    private boolean childColdHandsLethargic = false;

    /** Cold hands + pulse weak and fast (sub-checkbox of cold hands composite) */
    @Builder.Default
    private boolean childColdHandsPulseWeakFast = false;

    /** Cold hands + cap refill ≥ 3 sec (sub-checkbox of cold hands composite) */
    @Builder.Default
    private boolean childColdHandsCapRefill = false;

    // Dehydration — child-specific (entire section absent from adult form)
    /**
     * Severe dehydration ≥ +2 of the following:
     * □ Skin pinch ≥ 2 sec □ Lethargy □ Sunken eyes
     * (child form only)
     */
    @Builder.Default
    private boolean childSevereDehydration = false;

    /** Dehydration sub-sign: Skin pinch ≥ 2 sec */
    @Builder.Default
    private boolean childDehydrationSkinPinch = false;

    /** Dehydration sub-sign: Lethargy */
    @Builder.Default
    private boolean childDehydrationLethargy = false;

    /** Dehydration sub-sign: Sunken eyes */
    @Builder.Default
    private boolean childDehydrationSunkenEyes = false;

    // Child form footer measurements (not scored in TEWS but recorded)
    /** Child weight in kg (from child form footer: "Weight:___") */
    private Double childWeightKg;

    /** Child height in cm (from child form footer: "Height:___") */
    private Double childHeightCm;

    // ====================================================================
    // VITALS FROM TRIAGE FORM — used for TEWS calculation when no
    // separate VitalSigns record exists in the database
    // ====================================================================

    /** Respiratory rate (breaths per minute) — TEWS scored */
    private Integer respiratoryRate;

    /** Heart rate / pulse (beats per minute) — TEWS scored */
    private Integer heartRate;

    /** Systolic blood pressure (mmHg) — TEWS scored */
    private Integer systolicBP;

    /** Temperature (°C) — TEWS scored */
    private Double temperature;

    // ====================================================================
    // ADDITIONAL VITALS — recorded on triage form but not TEWS-scored
    // ====================================================================

    /** SpO₂ percentage (pulse oximetry) */
    private Integer spo2;

    /** Diastolic blood pressure in mmHg */
    private Integer diastolicBp;

    /** Blood glucose in mmol/L */
    private Double bloodGlucose;

    /** Pain score (0-10 numeric rating scale) */
    private Integer painScore;

    /** Patient weight in kg */
    private Double weightKg;

    /** Patient height in cm */
    private Double heightCm;

    // ====================================================================
    // SECTION 2: TEWS COMPONENTS
    // ====================================================================

    @NotNull(message = "Mobility status is required")
    private MobilityStatus mobility;

    @NotNull(message = "AVPU score is required")
    private AvpuScore avpu;

    @NotNull(message = "Trauma status is required")
    private TraumaStatus traumaStatus;

    /**
     * Reference to already-recorded vital signs (optional — will use latest if
     * null)
     */
    private UUID vitalSignsId;

    // ====================================================================
    // SECTION 3: VERY URGENT SIGNS — "Very Urgent Signs? CHECK THE COMPLAINT"
    // If TEWS 0-4 and any YES → ORANGE
    // ====================================================================

    // --- Medical Very Urgent ---

    /** Focal neurologic deficit – acute (< 1 day). Glucose field in form. */
    @Builder.Default
    private boolean vuFocalNeurologicDeficit = false;

    /** Altered mental status – acute (< 1 day). Glucose field in form. */
    @Builder.Default
    private boolean vuAlteredMentalStatus = false;

    /** Glucose value associated with neurological VU signs (mmol/L) */
    private Double vuNeurologicalGlucose;

    /** Chest pain */
    @Builder.Default
    private boolean vuChestPain = false;

    /** Poisoning / Overdose */
    @Builder.Default
    private boolean vuPoisoningOverdose = false;

    /** Pregnant + abdominal pain */
    @Builder.Default
    private boolean vuPregnantAbdominalPain = false;

    /** Coughing or vomiting blood */
    @Builder.Default
    private boolean vuCoughingVomitingBlood = false;

    /** Unwell with diabetes, glucose > 200 mg/dL or 11 mmol/L */
    @Builder.Default
    private boolean vuDiabeticHighGlucose = false;

    /** Glucose reading for diabetic VU sign (mmol/L) */
    private Double vuDiabeticGlucose;

    /** Aggression */
    @Builder.Default
    private boolean vuAggression = false;

    /** Shortness of breath – acute (Less than 1 day) */
    @Builder.Default
    private boolean vuShortnessOfBreath = false;

    // --- Trauma Very Urgent ---

    /** Burn over 20%, or urgent signs (electrical, chemical, circumferential) */
    @Builder.Default
    private boolean vuBurnOver20Percent = false;

    /** Fracture – Open (with skin break) */
    @Builder.Default
    private boolean vuOpenFracture = false;

    /** Threatened limb (no pulses or pale) */
    @Builder.Default
    private boolean vuThreatenedLimb = false;

    /** Eye injury */
    @Builder.Default
    private boolean vuEyeInjury = false;

    /** Dislocation of larger joint (not finger/toe) */
    @Builder.Default
    private boolean vuLargeJointDislocation = false;

    /**
     * Severe mechanism of injury (Fall > 1 meter, RTA, other significant trauma)
     */
    @Builder.Default
    private boolean vuSevereMechanismOfInjury = false;

    /** Very severe pain (≥ 7) */
    @Builder.Default
    private boolean vuVerySeverePain = false;

    /** Pregnant + abdominal trauma */
    @Builder.Default
    private boolean vuPregnantAbdominalTrauma = false;

    // ====================================================================
    // SECTION 4: URGENT SIGNS — "Urgent signs? CHECK THE COMPLAINT"
    // If TEWS 0-2, no VU signs, and any YES → YELLOW
    // ====================================================================

    /** Unable to drink or vomits everything */
    @Builder.Default
    private boolean urgUnableToDrinkVomits = false;

    /** Abdominal pain */
    @Builder.Default
    private boolean urgAbdominalPain = false;

    /** Very pale */
    @Builder.Default
    private boolean urgVeryPale = false;

    /** Pregnant + vaginal bleeding */
    @Builder.Default
    private boolean urgPregnantVaginalBleeding = false;

    /** Diabetic, glucose > 300 mg/dL or 17 mmol/L */
    @Builder.Default
    private boolean urgDiabeticVeryHighGlucose = false;

    /** Glucose reading for urgent diabetic sign (mmol/L) */
    private Double urgDiabeticGlucose;

    /** Dislocation – finger or toe */
    @Builder.Default
    private boolean urgFingerToeDislocation = false;

    /** Fracture – closed */
    @Builder.Default
    private boolean urgClosedFracture = false;

    /** Burn without urgent signs */
    @Builder.Default
    private boolean urgBurnWithoutUrgentSigns = false;

    /** Pregnant + trauma (not abdominal) */
    @Builder.Default
    private boolean urgPregnantTraumaNonAbdominal = false;

    /** Moderate pain (5-6) */
    @Builder.Default
    private boolean urgModeratePain = false;

    /** Laceration, abscess */
    @Builder.Default
    private boolean urgLacerationAbscess = false;

    /** Foreign body aspiration */
    @Builder.Default
    private boolean urgForeignBodyAspiration = false;

    // ====================================================================
    // V38 — Pediatric form compliance — Very Urgent (peds-only)
    // KFH Infant 0–3 / Child 3–12 form items not present on the adult
    // form. The decision engine reads these only on peds visits.
    // ====================================================================

    @Builder.Default
    private boolean vuPedsMoreSleepyThanNormal = false;

    @Builder.Default
    private boolean vuPedsInconsolableSeverePain = false;

    @Builder.Default
    private boolean vuPedsFloppyIrritableRestless = false;

    /** Infant form (0–3) only. */
    @Builder.Default
    private boolean vuPedsTinyBabyUnder2Months = false;

    /** Peds-form burn threshold (10%) — distinct from adult vuBurnOver20Percent. */
    @Builder.Default
    private boolean vuPedsBurnOver10Percent = false;

    // ====================================================================
    // V38 — Pediatric form compliance — Urgent (peds-only)
    // ====================================================================

    @Builder.Default
    private boolean urgPedsPittingEdemaFaceOrFeet = false;

    @Builder.Default
    private boolean urgPedsSomeRespiratoryDistress = false;

    @Builder.Default
    private boolean urgPedsSevereMalnutritionWasting = false;

    @Builder.Default
    private boolean urgPedsUnwellWithKnownDiabetes = false;

    /**
     * Composite: "Diarrhoea and/or vomiting plus any of: sunken eyes,
     * dry mouth, decreased urine output, skin pinch slow but <2 sec".
     * The composite flag drives the URG decision; sub-flags capture
     * what the nurse saw. The decision engine also fires the URG
     * when the composite flag is false but sub-flags imply it.
     */
    @Builder.Default
    private boolean urgPedsDiarrheaVomitingDehydration = false;

    @Builder.Default
    private boolean urgPedsDehydrationSunkenEyes = false;

    @Builder.Default
    private boolean urgPedsDehydrationDryMouth = false;

    @Builder.Default
    private boolean urgPedsDehydrationDecreasedUrine = false;

    @Builder.Default
    private boolean urgPedsDehydrationSlowSkinPinch = false;

    // ====================================================================
    // SECTION 5: CLINICAL METADATA
    // ====================================================================

    /** Chief complaint / presenting complaints (from top of form) */
    private String presentingComplaints;

    /** Additional clinical notes by triage nurse */
    private String clinicalNotes;

    // ====================================================================
    // SPECIAL CONSIDERATIONS (bottom of triage form)
    // ====================================================================

    /** Acute trauma */
    @Builder.Default
    private boolean specialAcuteTrauma = false;

    /** Seizure history */
    @Builder.Default
    private boolean specialSeizureHistory = false;

    /** Any assault / abuse */
    @Builder.Default
    private boolean specialAssaultAbuse = false;

    /** Suicide attempt */
    @Builder.Default
    private boolean specialSuicideAttempt = false;

    // ====================================================================
    // TRIAGE FORM FOOTER — Nurse & Doctor Notification
    // ====================================================================

    /** Name of the triage nurse performing the assessment */
    private String triageNurseName;

    /** Doctor name notified for RED/ORANGE patients */
    private String notifiedDoctorName;

    /** Timestamp when doctor was notified (ISO 8601 from frontend) */
    private String doctorNotifiedAt;

    /** Doctor who attended the patient */
    private String attendingDoctorName;

    /** Timestamp when doctor attended (ISO 8601 from frontend) */
    private String doctorAttendedAt;

    /**
     * V56 — precise user-id of the notified doctor, captured when the
     * nurse picks from the on-duty picker. NULL when the "Other…"
     * free-text fallback was used (locum / unscheduled doctor); only
     * {@link #notifiedDoctorName} carries the info in that case.
     */
    private java.util.UUID notifiedDoctorUserId;

    /** V56 — precise user-id of the attending doctor. Same semantics. */
    private java.util.UUID attendingDoctorUserId;
}
