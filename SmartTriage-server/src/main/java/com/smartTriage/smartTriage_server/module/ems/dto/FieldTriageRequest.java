package com.smartTriage.smartTriage_server.module.ems.dto;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Field-triage submission from the paramedic.
 *
 * <p>This is the pre-hospital analogue of {@code PerformTriageRequest}. It
 * carries the field vitals + the TEWS components (mobility / AVPU / trauma)
 * + a <em>focused</em> set of emergency / very-urgent / urgent discriminators
 * that are realistic to assess on scene. The service maps it onto a real
 * {@code PerformTriageRequest} and runs the <b>same</b> Rwanda adult engine
 * (or the KFH pediatric engine for a child) so the computed category is
 * identical to the call the ED would make — never a manual pick.
 *
 * <p>All discriminator flags default to {@code false}; vitals/components are
 * optional (a null vital simply scores 0 in TEWS, exactly as in-hospital).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldTriageRequest {

    // ── Field vitals (drive TEWS + RED overrides) ───────────────────
    private Integer respiratoryRate;   // breaths/min
    private Integer heartRate;         // bpm
    private Integer systolicBp;        // mmHg
    private Integer diastolicBp;       // mmHg (recorded, not TEWS-scored)
    private Integer spo2;              // % (SpO2 < 92 → RED)
    private Double  temperature;       // °C
    private Double  bloodGlucose;      // mmol/L (recorded)
    private Integer gcs;               // Glasgow Coma Scale 3-15 (recorded)
    private Integer painScore;         // 0-10

    // ── TEWS components ─────────────────────────────────────────────
    private MobilityStatus mobility;
    private AvpuScore avpu;
    private TraumaStatus traumaStatus;

    /**
     * Force the pediatric (KFH) form/engine. When null, the service
     * derives it from the run's {@code patientAgeYears} (&lt;13 → child).
     */
    private Boolean isChild;

    /** Paramedic's free-text rationale (stored as fieldTriageReason). */
    private String reason;

    /**
     * Must be true to record a re-compute that LOWERS the field acuity below
     * a previously computed category. Guards against a silent downgrade when
     * an en-route run is re-triaged. Ignored on the first computation.
     */
    private boolean acknowledgeDowngrade;

    // ── Emergency signs (any → RED) — shared adult/peds ─────────────
    private boolean hasAirwayCompromise;
    private boolean hasSevereRespiratoryDistress;
    private boolean hasCardiacArrest;
    private boolean hasUncontrolledHaemorrhage;
    private boolean hasStabGunWoundNeckChest;
    private boolean hasConvulsions;
    private boolean hasComa;
    private boolean hasHypoglycaemia;
    private boolean hasBurnFaceInhalation;

    // ── Pediatric-only emergency signs (used when isChild) ──────────
    private boolean childCentralCyanosis;
    private boolean childPulseLowOrAbsent;

    // ── Very urgent signs (focused) ─────────────────────────────────
    private boolean vuAlteredMentalStatus;
    private boolean vuFocalNeurologicDeficit;
    private boolean vuChestPain;
    private boolean vuShortnessOfBreath;
    private boolean vuPoisoningOverdose;
    private boolean vuCoughingVomitingBlood;
    private boolean vuSevereMechanismOfInjury;
    private boolean vuOpenFracture;
    private boolean vuThreatenedLimb;
    private boolean vuVerySeverePain;
    private boolean vuBurnOver20Percent;

    // ── Urgent signs (focused) ──────────────────────────────────────
    private boolean urgAbdominalPain;
    private boolean urgModeratePain;
    private boolean urgClosedFracture;
    private boolean urgLacerationAbscess;
    private boolean urgVeryPale;
    private boolean urgUnableToDrinkVomits;
}
