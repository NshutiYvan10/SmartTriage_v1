package com.smartTriage.smartTriage_server.module.triage.dto;

import com.smartTriage.smartTriage_server.common.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Full triage record response — mirrors every field captured from both the
 * Rwanda National Standard Adult Triage Form and Child Triage Form.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriageRecordResponse {

    private UUID id;
    private UUID visitId;
    private UUID triagedById;
    private String triagedByName;
    private UUID vitalSignsId;
    private Instant triageTime;

    // --- Emergency Signs ---
    private boolean hasAirwayCompromise;
    private boolean hasBreathingDistress;
    private boolean hasSevereRespiratoryDistress;
    private boolean hasCardiacArrest;
    private boolean hasUncontrolledHaemorrhage;
    private boolean hasStabGunWoundNeckChest;
    private boolean hasConvulsions;
    private Double convulsionGlucose;
    private boolean hasComa;
    private Double comaGlucose;
    private boolean hasHypoglycaemia;
    private boolean hasPurpuricRash;
    private boolean hasBurnFaceInhalation;

    // --- Child-Specific Emergency Signs (Child Triage Form 3-12 years) ---
    private boolean isChildForm;
    private boolean childCentralCyanosis;
    private boolean childPulseLowOrAbsent;
    private boolean childColdHandsComposite;
    private boolean childColdHandsLethargic;
    private boolean childColdHandsPulseWeakFast;
    private boolean childColdHandsCapRefill;
    private boolean childSevereDehydration;
    private boolean childDehydrationSkinPinch;
    private boolean childDehydrationLethargy;
    private boolean childDehydrationSunkenEyes;
    private Double childWeightKg;
    private Double childHeightCm;

    // --- Additional Vitals ---
    private Integer spo2;
    private Integer diastolicBp;
    private Double bloodGlucose;
    private Integer painScore;
    private Double weightKg;
    private Double heightCm;

    // --- TEWS Components ---
    private MobilityStatus mobility;
    private AvpuScore avpu;
    private TraumaStatus traumaStatus;

    // --- Very Urgent Signs: Medical ---
    private boolean vuFocalNeurologicDeficit;
    private boolean vuAlteredMentalStatus;
    private Double vuNeurologicalGlucose;
    private boolean vuChestPain;
    private boolean vuPoisoningOverdose;
    private boolean vuPregnantAbdominalPain;
    private boolean vuCoughingVomitingBlood;
    private boolean vuDiabeticHighGlucose;
    private Double vuDiabeticGlucose;
    private boolean vuAggression;
    private boolean vuShortnessOfBreath;

    // --- Very Urgent Signs: Trauma ---
    private boolean vuBurnOver20Percent;
    private boolean vuOpenFracture;
    private boolean vuThreatenedLimb;
    private boolean vuEyeInjury;
    private boolean vuLargeJointDislocation;
    private boolean vuSevereMechanismOfInjury;
    private boolean vuVerySeverePain;
    private boolean vuPregnantAbdominalTrauma;

    // --- Urgent Signs ---
    private boolean urgUnableToDrinkVomits;
    private boolean urgAbdominalPain;
    private boolean urgVeryPale;
    private boolean urgPregnantVaginalBleeding;
    private boolean urgDiabeticVeryHighGlucose;
    private Double urgDiabeticGlucose;
    private boolean urgFingerToeDislocation;
    private boolean urgClosedFracture;
    private boolean urgBurnWithoutUrgentSigns;
    private boolean urgPregnantTraumaNonAbdominal;
    private boolean urgModeratePain;
    private boolean urgLacerationAbscess;
    private boolean urgForeignBodyAspiration;

    // --- Computed Results ---
    private int tewsScore;
    private TriageCategory triageCategory;
    private String decisionPath;

    // --- Metadata ---
    private boolean isRetriage;
    private boolean isSystemTriggered;
    private TriageCategory previousCategory;
    private String presentingComplaints;
    private String clinicalNotes;

    // --- Round 3: System-triggered re-triage audit ---
    /** clinical_sign_events.id whose recording caused this triage record (null for manual). */
    private UUID triggeringSignEventId;
    /** Sign code (e.g. EMERGENCY_CARDIAC_ARREST) — denormalised from the trigger event. */
    private String triggeringSignCode;
    /** Human-readable label resolved server-side via ClinicalSignDefinitions. */
    private String triggeringSignLabel;
    /** Status the trigger event recorded — typically PRESENT or WORSENING. */
    private com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignStatus triggeringSignStatus;
    /** When the triggering sign event was recorded. */
    private Instant triggeringSignRecordedAt;

    // --- Special Considerations ---
    private boolean specialAcuteTrauma;
    private boolean specialSeizureHistory;
    private boolean specialAssaultAbuse;
    private boolean specialSuicideAttempt;

    // --- Triage Form Footer ---
    private String triageNurseName;
    private String notifiedDoctorName;
    private Instant doctorNotifiedAt;
    private String attendingDoctorName;
    private Instant doctorAttendedAt;

    // --- Bed Suggestion (Phase G #2) ---
    // Populated only on the response from POST /triage (performTriage), so
    // the form can show the nurse a "Place in suggested bed?" confirm. Null
    // on subsequent reads (history, getLatest) — those return the stored
    // record without re-running the suggestion engine.
    private UUID suggestedBedId;
    private String suggestedBedCode;
    private EdZone suggestedBedZone;
    private boolean suggestedBedHasMonitor;

    private Instant createdAt;
}
