/* ═══════════════════════════════════════════════════════════════
   Clinical-sign-code → triage-form field map.

   Round 4a — when a nurse clicks a RETRIAGE_REQUIRED alert and lands
   on AdultTriageForm or PediatricTriageForm, the form needs to know
   which checkbox to pre-flag based on the alert's
   `triggeringSignCode`. This file is the single source of truth for
   that mapping.

   Each entry maps a clinical-sign code (catalogued in
   `clinicalSignDefinitions.ts` and the backend's
   `ClinicalSignDefinitions.java`) to the boolean field name on
   `PerformTriageRequest` that represents the same observation on the
   Rwanda triage form.

   The pre-flag is one-way only: the form sets the boolean to true on
   load. The nurse can untoggle it if they disagree. We never set
   booleans to false based on alert content.

   Drift between this map and either catalog would mean the nurse
   sees the wrong checkbox flagged — keep them in sync when adding
   new signs to the system.
   ═══════════════════════════════════════════════════════════════ */

import type { PerformTriageRequest } from '@/api/types';

/**
 * Type-narrow target: only the boolean keys on PerformTriageRequest
 * (no glucose / weight / nurse-name fields). The compiler enforces
 * that every value below is one of these keys.
 */
type TriageBoolField = {
  [K in keyof PerformTriageRequest]: PerformTriageRequest[K] extends boolean | undefined
    ? K
    : never;
}[keyof PerformTriageRequest];

export const SIGN_CODE_TO_TRIAGE_FIELD: Record<string, TriageBoolField> = {
  // Emergency (Section 1) — shared adult + pediatric
  EMERGENCY_AIRWAY_COMPROMISE: 'hasAirwayCompromise',
  EMERGENCY_BREATHING_DISTRESS: 'hasBreathingDistress',
  EMERGENCY_SEVERE_RESPIRATORY_DISTRESS: 'hasSevereRespiratoryDistress',
  EMERGENCY_CARDIAC_ARREST: 'hasCardiacArrest',
  EMERGENCY_UNCONTROLLED_HAEMORRHAGE: 'hasUncontrolledHaemorrhage',
  EMERGENCY_STAB_GUN_WOUND_NECK_CHEST: 'hasStabGunWoundNeckChest',
  EMERGENCY_CONVULSIONS: 'hasConvulsions',
  EMERGENCY_COMA: 'hasComa',
  EMERGENCY_HYPOGLYCAEMIA: 'hasHypoglycaemia',
  EMERGENCY_PURPURIC_RASH: 'hasPurpuricRash',
  EMERGENCY_BURN_FACE_INHALATION: 'hasBurnFaceInhalation',

  // Pediatric Emergency (Section 1b) — child form only
  PEDS_EMERGENCY_CENTRAL_CYANOSIS: 'childCentralCyanosis',
  PEDS_EMERGENCY_PULSE_LOW_OR_ABSENT: 'childPulseLowOrAbsent',
  PEDS_EMERGENCY_COLD_HANDS_COMPOSITE: 'childColdHandsComposite',
  PEDS_EMERGENCY_COLD_HANDS_LETHARGIC: 'childColdHandsLethargic',
  PEDS_EMERGENCY_COLD_HANDS_PULSE_WEAK_FAST: 'childColdHandsPulseWeakFast',
  PEDS_EMERGENCY_COLD_HANDS_CAP_REFILL: 'childColdHandsCapRefill',
  PEDS_EMERGENCY_SEVERE_DEHYDRATION: 'childSevereDehydration',
  PEDS_EMERGENCY_DEHYDRATION_SKIN_PINCH: 'childDehydrationSkinPinch',
  PEDS_EMERGENCY_DEHYDRATION_LETHARGY: 'childDehydrationLethargy',
  PEDS_EMERGENCY_DEHYDRATION_SUNKEN_EYES: 'childDehydrationSunkenEyes',

  // mSAT Very Urgent — Medical
  MSAT_VU_FOCAL_NEUROLOGIC_DEFICIT: 'vuFocalNeurologicDeficit',
  MSAT_VU_ALTERED_MENTAL_STATUS: 'vuAlteredMentalStatus',
  MSAT_VU_CHEST_PAIN: 'vuChestPain',
  MSAT_VU_POISONING_OVERDOSE: 'vuPoisoningOverdose',
  MSAT_VU_PREGNANT_ABDOMINAL_PAIN: 'vuPregnantAbdominalPain',
  MSAT_VU_COUGHING_VOMITING_BLOOD: 'vuCoughingVomitingBlood',
  MSAT_VU_DIABETIC_HIGH_GLUCOSE: 'vuDiabeticHighGlucose',
  MSAT_VU_AGGRESSION: 'vuAggression',
  MSAT_VU_SHORTNESS_OF_BREATH: 'vuShortnessOfBreath',

  // mSAT Very Urgent — Trauma
  MSAT_VU_BURN_OVER_20_PERCENT: 'vuBurnOver20Percent',
  MSAT_VU_OPEN_FRACTURE: 'vuOpenFracture',
  MSAT_VU_THREATENED_LIMB: 'vuThreatenedLimb',
  MSAT_VU_EYE_INJURY: 'vuEyeInjury',
  MSAT_VU_LARGE_JOINT_DISLOCATION: 'vuLargeJointDislocation',
  MSAT_VU_SEVERE_MECHANISM_OF_INJURY: 'vuSevereMechanismOfInjury',
  MSAT_VU_VERY_SEVERE_PAIN: 'vuVerySeverePain',
  MSAT_VU_PREGNANT_ABDOMINAL_TRAUMA: 'vuPregnantAbdominalTrauma',

  // mSAT Urgent
  MSAT_URG_UNABLE_TO_DRINK_VOMITS: 'urgUnableToDrinkVomits',
  MSAT_URG_ABDOMINAL_PAIN: 'urgAbdominalPain',
  MSAT_URG_VERY_PALE: 'urgVeryPale',
  MSAT_URG_PREGNANT_VAGINAL_BLEEDING: 'urgPregnantVaginalBleeding',
  MSAT_URG_DIABETIC_VERY_HIGH_GLUCOSE: 'urgDiabeticVeryHighGlucose',
  MSAT_URG_FINGER_TOE_DISLOCATION: 'urgFingerToeDislocation',
  MSAT_URG_CLOSED_FRACTURE: 'urgClosedFracture',
  MSAT_URG_BURN_WITHOUT_URGENT_SIGNS: 'urgBurnWithoutUrgentSigns',
  MSAT_URG_PREGNANT_TRAUMA_NON_ABDOMINAL: 'urgPregnantTraumaNonAbdominal',
  MSAT_URG_MODERATE_PAIN: 'urgModeratePain',
  MSAT_URG_LACERATION_ABSCESS: 'urgLacerationAbscess',
  MSAT_URG_FOREIGN_BODY_ASPIRATION: 'urgForeignBodyAspiration',

  // Special considerations (form footer)
  SPECIAL_ACUTE_TRAUMA: 'specialAcuteTrauma',
  SPECIAL_SEIZURE_HISTORY: 'specialSeizureHistory',
  SPECIAL_ASSAULT_ABUSE: 'specialAssaultAbuse',
  SPECIAL_SUICIDE_ATTEMPT: 'specialSuicideAttempt',
};

/** True when a code maps to a boolean on PerformTriageRequest. */
export function triageFieldFor(signCode: string | null | undefined): TriageBoolField | null {
  if (!signCode) return null;
  return SIGN_CODE_TO_TRIAGE_FIELD[signCode] ?? null;
}
