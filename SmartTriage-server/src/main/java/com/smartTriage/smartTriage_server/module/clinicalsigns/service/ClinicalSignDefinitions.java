package com.smartTriage.smartTriage_server.module.clinicalsigns.service;

import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignCategory;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Canonical catalog of the 54 clinical signs we track over time.
 *
 * Single source of truth on the server for:
 *   1. Mapping each sign_code to its ClinicalSignCategory (so the request
 *      DTO doesn't have to carry category — the server derives it)
 *   2. Bootstrapping the timeline from a fresh TriageRecord — we know how
 *      to read every triage flag and which sign_code it corresponds to
 *
 * The frontend has its own parallel catalog (`clinicalSignDefinitions.ts`)
 * with the same codes plus human-readable labels. Drift between the two
 * would mean the doctor sees "Update sign" for a code the server doesn't
 * recognize — keep them in sync when adding new signs.
 *
 * The V27 migration's backfill SQL also encodes this mapping; it's
 * intentionally duplicated there because Flyway migrations run before
 * the application boots.
 */
public final class ClinicalSignDefinitions {

    private ClinicalSignDefinitions() {}

    /**
     * Each entry: (sign_code, category, "is positive on this triage record"
     * predicate, optional getter for an associated numeric value such as
     * glucose). The numeric getter returns null when the sign carries no
     * numeric value or the field is unset.
     */
    public record SignMapping(
            String code,
            ClinicalSignCategory category,
            Predicate<TriageRecord> isPositive,
            Function<TriageRecord, Double> numericValue
    ) {}

    private static SignMapping flag(String code, ClinicalSignCategory cat, Predicate<TriageRecord> p) {
        return new SignMapping(code, cat, p, t -> null);
    }

    private static SignMapping flagWithNumeric(
            String code, ClinicalSignCategory cat,
            Predicate<TriageRecord> p, Function<TriageRecord, Double> num) {
        return new SignMapping(code, cat, p, num);
    }

    /**
     * Full catalog. Order is the order signs appear on the Rwanda triage
     * form (Section 1, 1b, 3, 4, special) — preserved here so the doctor's
     * timeline reads in form-order rather than alphabetical.
     */
    public static final List<SignMapping> ALL = List.of(
            // ── Emergency (Section 1) ──
            flag("EMERGENCY_AIRWAY_COMPROMISE",          ClinicalSignCategory.EMERGENCY, TriageRecord::isHasAirwayCompromise),
            flag("EMERGENCY_BREATHING_DISTRESS",         ClinicalSignCategory.EMERGENCY, TriageRecord::isHasBreathingDistress),
            flag("EMERGENCY_SEVERE_RESPIRATORY_DISTRESS",ClinicalSignCategory.EMERGENCY, TriageRecord::isHasSevereRespiratoryDistress),
            flag("EMERGENCY_CARDIAC_ARREST",             ClinicalSignCategory.EMERGENCY, TriageRecord::isHasCardiacArrest),
            flag("EMERGENCY_UNCONTROLLED_HAEMORRHAGE",   ClinicalSignCategory.EMERGENCY, TriageRecord::isHasUncontrolledHaemorrhage),
            flag("EMERGENCY_STAB_GUN_WOUND_NECK_CHEST",  ClinicalSignCategory.EMERGENCY, TriageRecord::isHasStabGunWoundNeckChest),
            flagWithNumeric("EMERGENCY_CONVULSIONS",     ClinicalSignCategory.EMERGENCY, TriageRecord::isHasConvulsions, TriageRecord::getConvulsionGlucose),
            flagWithNumeric("EMERGENCY_COMA",            ClinicalSignCategory.EMERGENCY, TriageRecord::isHasComa, TriageRecord::getComaGlucose),
            flag("EMERGENCY_HYPOGLYCAEMIA",              ClinicalSignCategory.EMERGENCY, TriageRecord::isHasHypoglycaemia),
            flag("EMERGENCY_PURPURIC_RASH",              ClinicalSignCategory.EMERGENCY, TriageRecord::isHasPurpuricRash),
            flag("EMERGENCY_BURN_FACE_INHALATION",       ClinicalSignCategory.EMERGENCY, TriageRecord::isHasBurnFaceInhalation),

            // ── Pediatric Emergency (Section 1b) ──
            flag("PEDS_EMERGENCY_CENTRAL_CYANOSIS",          ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildCentralCyanosis),
            flag("PEDS_EMERGENCY_PULSE_LOW_OR_ABSENT",       ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildPulseLowOrAbsent),
            flag("PEDS_EMERGENCY_COLD_HANDS_COMPOSITE",      ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildColdHandsComposite),
            flag("PEDS_EMERGENCY_COLD_HANDS_LETHARGIC",      ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildColdHandsLethargic),
            flag("PEDS_EMERGENCY_COLD_HANDS_PULSE_WEAK_FAST",ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildColdHandsPulseWeakFast),
            flag("PEDS_EMERGENCY_COLD_HANDS_CAP_REFILL",     ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildColdHandsCapRefill),
            flag("PEDS_EMERGENCY_SEVERE_DEHYDRATION",        ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildSevereDehydration),
            flag("PEDS_EMERGENCY_DEHYDRATION_SKIN_PINCH",    ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildDehydrationSkinPinch),
            flag("PEDS_EMERGENCY_DEHYDRATION_LETHARGY",      ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildDehydrationLethargy),
            flag("PEDS_EMERGENCY_DEHYDRATION_SUNKEN_EYES",   ClinicalSignCategory.PEDIATRIC_EMERGENCY, TriageRecord::isChildDehydrationSunkenEyes),

            // ── mSAT Very Urgent (Section 3) ──
            flag("MSAT_VU_FOCAL_NEUROLOGIC_DEFICIT",     ClinicalSignCategory.MSAT_VU, TriageRecord::isVuFocalNeurologicDeficit),
            flagWithNumeric("MSAT_VU_ALTERED_MENTAL_STATUS", ClinicalSignCategory.MSAT_VU, TriageRecord::isVuAlteredMentalStatus, TriageRecord::getVuNeurologicalGlucose),
            flag("MSAT_VU_CHEST_PAIN",                   ClinicalSignCategory.MSAT_VU, TriageRecord::isVuChestPain),
            flag("MSAT_VU_POISONING_OVERDOSE",           ClinicalSignCategory.MSAT_VU, TriageRecord::isVuPoisoningOverdose),
            flag("MSAT_VU_PREGNANT_ABDOMINAL_PAIN",      ClinicalSignCategory.MSAT_VU, TriageRecord::isVuPregnantAbdominalPain),
            flag("MSAT_VU_COUGHING_VOMITING_BLOOD",      ClinicalSignCategory.MSAT_VU, TriageRecord::isVuCoughingVomitingBlood),
            flagWithNumeric("MSAT_VU_DIABETIC_HIGH_GLUCOSE", ClinicalSignCategory.MSAT_VU, TriageRecord::isVuDiabeticHighGlucose, TriageRecord::getVuDiabeticGlucose),
            flag("MSAT_VU_AGGRESSION",                   ClinicalSignCategory.MSAT_VU, TriageRecord::isVuAggression),
            flag("MSAT_VU_SHORTNESS_OF_BREATH",          ClinicalSignCategory.MSAT_VU, TriageRecord::isVuShortnessOfBreath),
            flag("MSAT_VU_BURN_OVER_20_PERCENT",         ClinicalSignCategory.MSAT_VU, TriageRecord::isVuBurnOver20Percent),
            flag("MSAT_VU_OPEN_FRACTURE",                ClinicalSignCategory.MSAT_VU, TriageRecord::isVuOpenFracture),
            flag("MSAT_VU_THREATENED_LIMB",              ClinicalSignCategory.MSAT_VU, TriageRecord::isVuThreatenedLimb),
            flag("MSAT_VU_EYE_INJURY",                   ClinicalSignCategory.MSAT_VU, TriageRecord::isVuEyeInjury),
            flag("MSAT_VU_LARGE_JOINT_DISLOCATION",      ClinicalSignCategory.MSAT_VU, TriageRecord::isVuLargeJointDislocation),
            flag("MSAT_VU_SEVERE_MECHANISM_OF_INJURY",   ClinicalSignCategory.MSAT_VU, TriageRecord::isVuSevereMechanismOfInjury),
            flag("MSAT_VU_VERY_SEVERE_PAIN",             ClinicalSignCategory.MSAT_VU, TriageRecord::isVuVerySeverePain),
            flag("MSAT_VU_PREGNANT_ABDOMINAL_TRAUMA",    ClinicalSignCategory.MSAT_VU, TriageRecord::isVuPregnantAbdominalTrauma),

            // ── mSAT Urgent (Section 4) ──
            flag("MSAT_URG_UNABLE_TO_DRINK_VOMITS",      ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgUnableToDrinkVomits),
            flag("MSAT_URG_ABDOMINAL_PAIN",              ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgAbdominalPain),
            flag("MSAT_URG_VERY_PALE",                   ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgVeryPale),
            flag("MSAT_URG_PREGNANT_VAGINAL_BLEEDING",   ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgPregnantVaginalBleeding),
            flagWithNumeric("MSAT_URG_DIABETIC_VERY_HIGH_GLUCOSE", ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgDiabeticVeryHighGlucose, TriageRecord::getUrgDiabeticGlucose),
            flag("MSAT_URG_FINGER_TOE_DISLOCATION",      ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgFingerToeDislocation),
            flag("MSAT_URG_CLOSED_FRACTURE",             ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgClosedFracture),
            flag("MSAT_URG_BURN_WITHOUT_URGENT_SIGNS",   ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgBurnWithoutUrgentSigns),
            flag("MSAT_URG_PREGNANT_TRAUMA_NON_ABDOMINAL", ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgPregnantTraumaNonAbdominal),
            flag("MSAT_URG_MODERATE_PAIN",               ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgModeratePain),
            flag("MSAT_URG_LACERATION_ABSCESS",          ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgLacerationAbscess),
            flag("MSAT_URG_FOREIGN_BODY_ASPIRATION",     ClinicalSignCategory.MSAT_URG, TriageRecord::isUrgForeignBodyAspiration),

            // ── Special considerations (form footer) ──
            flag("SPECIAL_ACUTE_TRAUMA",       ClinicalSignCategory.SPECIAL, TriageRecord::isSpecialAcuteTrauma),
            flag("SPECIAL_SEIZURE_HISTORY",    ClinicalSignCategory.SPECIAL, TriageRecord::isSpecialSeizureHistory),
            flag("SPECIAL_ASSAULT_ABUSE",      ClinicalSignCategory.SPECIAL, TriageRecord::isSpecialAssaultAbuse),
            flag("SPECIAL_SUICIDE_ATTEMPT",    ClinicalSignCategory.SPECIAL, TriageRecord::isSpecialSuicideAttempt)
    );

    /** Quick lookup: sign_code → category. Used by the request handler to
     *  derive category server-side rather than trusting the client. */
    public static final Map<String, ClinicalSignCategory> CATEGORY_BY_CODE =
            ALL.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    SignMapping::code, SignMapping::category));

    /** True when `code` is a recognised clinical sign code. */
    public static boolean isKnownCode(String code) {
        return code != null && CATEGORY_BY_CODE.containsKey(code);
    }
}
