/**
 * Rwanda National Standard ADULT Triage Form (Over 12 years) — canonical sign lists.
 *
 * These lists are the EXACT items from the national form, transcribed to match the backend
 * `PerformTriageRequest` fields + `RwandaTriageDecisionEngine` 1:1 (each item's `field` is the
 * backend boolean it drives). This is the adult analogue of the pediatric `discriminators.ts`
 * lists, which already match the backend field-for-field.
 *
 * WHY THIS EXISTS (Track B / patient-safety fix 2b+2c): the adult form previously rendered a
 * generic ABCDE / Manchester-style vocabulary and mapped it lossily to the national-form flags
 * (hardcoding several to false), so the acuity a nurse saw could differ from the acuity the
 * server recorded, and real national-form discriminators (poisoning, pregnant+vaginal bleeding,
 * etc.) never fired server-side. Rendering THESE lists and passing each `field` straight through
 * makes the form and the server use one vocabulary and one source of truth.
 *
 * SOURCE OF TRUTH: backend transcription of the Rwanda National Adult Triage Form. To be
 * validated against the physical MoH form (the numbers/labels below mirror the backend Javadoc:
 * hypoglycaemia < 3 mmol/L; VU diabetic > 11 mmol/L; URG diabetic > 17 mmol/L; very severe
 * pain ≥ 7; moderate pain 5–6).
 */

import type { DiscriminatorGroup } from './discriminators';

export interface AdultSignItem {
  /** stable UI key */
  id: string;
  /** the PerformTriageRequest boolean field this item drives (1:1) */
  field: string;
  label: string;
  system?: string;
}

export interface AdultSignGroup {
  title: string;
  items: AdultSignItem[];
}

// ── SECTION 1: EMERGENCY SIGNS → RED (any one = immediate resuscitation) ──
// Maps to PerformTriageRequest Section 1 (adult). Coma and hypoglycaemia are ALSO derived
// automatically (AVPU P/U; glucose < 3 mmol/L) in the form, so the checkbox is a manual adjunct.
export const ADULT_EMERGENCY_SIGNS: AdultSignGroup[] = [
  {
    title: 'Airway / Breathing',
    items: [
      { id: 'em_airway_compromise', field: 'hasAirwayCompromise', label: 'Not breathing / obstructed airway' },
      { id: 'em_severe_resp_distress', field: 'hasSevereRespiratoryDistress', label: 'Severe respiratory distress' },
    ],
  },
  {
    title: 'Circulation',
    items: [
      { id: 'em_cardiac_arrest', field: 'hasCardiacArrest', label: 'Cardiac arrest' },
      { id: 'em_uncontrolled_haemorrhage', field: 'hasUncontrolledHaemorrhage', label: 'Uncontrolled haemorrhage' },
      { id: 'em_stab_gun_neck_chest', field: 'hasStabGunWoundNeckChest', label: 'Stab / gunshot wound to neck or chest' },
    ],
  },
  {
    title: 'Disability',
    items: [
      { id: 'em_convulsions', field: 'hasConvulsions', label: 'Current seizure or post-ictal' },
      { id: 'em_coma', field: 'hasComa', label: 'Coma — unresponsive or responds only to pain' },
      { id: 'em_hypoglycaemia', field: 'hasHypoglycaemia', label: 'Hypoglycaemia (glucose < 3 mmol/L / < 60 mg/dL)' },
    ],
  },
  {
    title: 'Exposure',
    items: [
      { id: 'em_purpuric_rash', field: 'hasPurpuricRash', label: 'Purpuric rash' },
      { id: 'em_burn_face_inhalation', field: 'hasBurnFaceInhalation', label: 'Burn — face / inhalation injury' },
    ],
  },
];

// ── SECTION 3: VERY URGENT SIGNS → ORANGE (when TEWS 0–6) ──
export const ADULT_VERY_URGENT_SIGNS: AdultSignGroup[] = [
  {
    title: 'Medical',
    items: [
      { id: 'vu_focal_neurologic_deficit', field: 'vuFocalNeurologicDeficit', label: 'Focal neurologic deficit — acute (< 1 day)' },
      { id: 'vu_altered_mental_status', field: 'vuAlteredMentalStatus', label: 'Altered mental status — acute (< 1 day)' },
      { id: 'vu_chest_pain', field: 'vuChestPain', label: 'Chest pain' },
      { id: 'vu_poisoning_overdose', field: 'vuPoisoningOverdose', label: 'Poisoning / overdose' },
      { id: 'vu_pregnant_abdominal_pain', field: 'vuPregnantAbdominalPain', label: 'Pregnant + abdominal pain' },
      { id: 'vu_coughing_vomiting_blood', field: 'vuCoughingVomitingBlood', label: 'Coughing or vomiting blood' },
      { id: 'vu_diabetic_high_glucose', field: 'vuDiabeticHighGlucose', label: 'Unwell with diabetes, glucose > 11 mmol/L (200 mg/dL)' },
      { id: 'vu_aggression', field: 'vuAggression', label: 'Aggression' },
      { id: 'vu_shortness_of_breath', field: 'vuShortnessOfBreath', label: 'Shortness of breath — acute (< 1 day)' },
    ],
  },
  {
    title: 'Trauma',
    items: [
      { id: 'vu_burn_over_20_percent', field: 'vuBurnOver20Percent', label: 'Burn over 20%, or urgent (electrical / chemical / circumferential)' },
      { id: 'vu_open_fracture', field: 'vuOpenFracture', label: 'Open fracture (with skin break)' },
      { id: 'vu_threatened_limb', field: 'vuThreatenedLimb', label: 'Threatened limb (no pulses or pale)' },
      { id: 'vu_eye_injury', field: 'vuEyeInjury', label: 'Eye injury' },
      { id: 'vu_large_joint_dislocation', field: 'vuLargeJointDislocation', label: 'Dislocation of larger joint (not finger / toe)' },
      { id: 'vu_severe_mechanism_of_injury', field: 'vuSevereMechanismOfInjury', label: 'Severe mechanism of injury (fall > 1 m, RTA, significant trauma)' },
      { id: 'vu_very_severe_pain', field: 'vuVerySeverePain', label: 'Very severe pain (≥ 7 / 10)' },
      { id: 'vu_pregnant_abdominal_trauma', field: 'vuPregnantAbdominalTrauma', label: 'Pregnant + abdominal trauma' },
    ],
  },
];

// ── SECTION 4: URGENT SIGNS → YELLOW (when TEWS 0–2, no VU) ──
export const ADULT_URGENT_SIGNS: AdultSignGroup[] = [
  {
    title: 'Medical',
    items: [
      { id: 'urg_unable_to_drink_vomits', field: 'urgUnableToDrinkVomits', label: 'Unable to drink or vomits everything' },
      { id: 'urg_abdominal_pain', field: 'urgAbdominalPain', label: 'Abdominal pain' },
      { id: 'urg_very_pale', field: 'urgVeryPale', label: 'Very pale' },
      { id: 'urg_pregnant_vaginal_bleeding', field: 'urgPregnantVaginalBleeding', label: 'Pregnant + vaginal bleeding' },
      { id: 'urg_diabetic_very_high_glucose', field: 'urgDiabeticVeryHighGlucose', label: 'Diabetic, glucose > 17 mmol/L (300 mg/dL)' },
      { id: 'urg_foreign_body_aspiration', field: 'urgForeignBodyAspiration', label: 'Foreign body aspiration' },
    ],
  },
  {
    title: 'Trauma / Skin',
    items: [
      { id: 'urg_finger_toe_dislocation', field: 'urgFingerToeDislocation', label: 'Dislocation — finger or toe' },
      { id: 'urg_closed_fracture', field: 'urgClosedFracture', label: 'Fracture — closed' },
      { id: 'urg_burn_without_urgent_signs', field: 'urgBurnWithoutUrgentSigns', label: 'Burn without urgent signs' },
      { id: 'urg_pregnant_trauma_non_abdominal', field: 'urgPregnantTraumaNonAbdominal', label: 'Pregnant + trauma (not abdominal)' },
      { id: 'urg_moderate_pain', field: 'urgModeratePain', label: 'Moderate pain (5–6 / 10)' },
      { id: 'urg_laceration_abscess', field: 'urgLacerationAbscess', label: 'Laceration, abscess' },
    ],
  },
];

// ── helpers ──

export function flattenAdultSigns(groups: AdultSignGroup[]): AdultSignItem[] {
  return groups.flatMap((g) => g.items);
}

/** Any item in the given groups checked? */
export function anyAdultSignChecked(groups: AdultSignGroup[], checked: Record<string, boolean>): boolean {
  return groups.some((g) => g.items.some((it) => checked[it.id]));
}

/**
 * Build the {backendField: boolean} slice for a group array from the checked-map, so the
 * triage submit can spread it straight into PerformTriageRequest with no lossy per-item mapping.
 */
export function adultSignsToRequest(
  groups: AdultSignGroup[],
  checked: Record<string, boolean>,
): Record<string, boolean> {
  const out: Record<string, boolean> = {};
  for (const g of groups) {
    for (const it of g.items) {
      out[it.field] = !!checked[it.id];
    }
  }
  return out;
}

/** Labels of checked items (for the review summary). */
export function checkedAdultSignLabels(groups: AdultSignGroup[], checked: Record<string, boolean>): string[] {
  return flattenAdultSigns(groups).filter((it) => checked[it.id]).map((it) => it.label);
}

// ════════════════════════════════════════════════════════════════════════════
// Unified discriminator lists for the adult form's VU / Urgent step — national
// signs (each with the backend `field` it drives 1:1) PLUS the extra clinical
// signs we keep. Rendered as DiscriminatorGroup[] (same shape the form already
// renders). Items WITHOUT a `field` are the extras → routed to the backend's
// additional-signs channel at the group's tier. Nothing is dropped or hard-coded.
// ════════════════════════════════════════════════════════════════════════════

/** Maps a discriminator item id → the PerformTriageRequest boolean it sets (national signs only). */
export const ADULT_DISCRIMINATOR_FIELD: Record<string, string> = {
  // Very Urgent (national)
  vu_focal_neurologic_deficit: 'vuFocalNeurologicDeficit',
  vu_altered_mental_status: 'vuAlteredMentalStatus',
  vu_chest_pain: 'vuChestPain',
  vu_poisoning_overdose: 'vuPoisoningOverdose',
  vu_pregnant_abdominal_pain: 'vuPregnantAbdominalPain',
  vu_coughing_vomiting_blood: 'vuCoughingVomitingBlood',
  vu_diabetic_high_glucose: 'vuDiabeticHighGlucose',
  vu_aggression: 'vuAggression',
  vu_shortness_of_breath: 'vuShortnessOfBreath',
  vu_burn_over_20_percent: 'vuBurnOver20Percent',
  vu_open_fracture: 'vuOpenFracture',
  vu_threatened_limb: 'vuThreatenedLimb',
  vu_eye_injury: 'vuEyeInjury',
  vu_large_joint_dislocation: 'vuLargeJointDislocation',
  vu_severe_mechanism_of_injury: 'vuSevereMechanismOfInjury',
  vu_very_severe_pain: 'vuVerySeverePain',
  vu_pregnant_abdominal_trauma: 'vuPregnantAbdominalTrauma',
  // Urgent (national)
  urg_unable_to_drink_vomits: 'urgUnableToDrinkVomits',
  urg_abdominal_pain: 'urgAbdominalPain',
  urg_very_pale: 'urgVeryPale',
  urg_pregnant_vaginal_bleeding: 'urgPregnantVaginalBleeding',
  urg_diabetic_very_high_glucose: 'urgDiabeticVeryHighGlucose',
  urg_foreign_body_aspiration: 'urgForeignBodyAspiration',
  urg_finger_toe_dislocation: 'urgFingerToeDislocation',
  urg_closed_fracture: 'urgClosedFracture',
  urg_burn_without_urgent_signs: 'urgBurnWithoutUrgentSigns',
  urg_pregnant_trauma_non_abdominal: 'urgPregnantTraumaNonAbdominal',
  urg_moderate_pain: 'urgModeratePain',
  urg_laceration_abscess: 'urgLacerationAbscess',
};

export const ADULT_VU_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Medical — national form', icon: '🩺', color: 'text-orange-700', bgColor: 'bg-orange-50',
    items: [
      { id: 'vu_focal_neurologic_deficit', label: 'Focal neurologic deficit — acute (< 1 day)', system: 'Medical' },
      { id: 'vu_altered_mental_status', label: 'Altered mental status — acute (< 1 day)', system: 'Medical' },
      { id: 'vu_chest_pain', label: 'Chest pain', system: 'Medical' },
      { id: 'vu_poisoning_overdose', label: 'Poisoning / overdose', system: 'Medical' },
      { id: 'vu_pregnant_abdominal_pain', label: 'Pregnant + abdominal pain', system: 'Medical' },
      { id: 'vu_coughing_vomiting_blood', label: 'Coughing or vomiting blood', system: 'Medical' },
      { id: 'vu_diabetic_high_glucose', label: 'Unwell with diabetes, glucose > 11 mmol/L (200 mg/dL)', system: 'Medical' },
      { id: 'vu_aggression', label: 'Aggression', system: 'Medical' },
      { id: 'vu_shortness_of_breath', label: 'Shortness of breath — acute (< 1 day)', system: 'Medical' },
    ],
  },
  {
    system: 'Trauma — national form', icon: '🦴', color: 'text-orange-700', bgColor: 'bg-orange-50',
    items: [
      { id: 'vu_burn_over_20_percent', label: 'Burn over 20%, or urgent (electrical / chemical / circumferential)', system: 'Trauma' },
      { id: 'vu_open_fracture', label: 'Open fracture (with skin break)', system: 'Trauma' },
      { id: 'vu_threatened_limb', label: 'Threatened limb (no pulses or pale)', system: 'Trauma' },
      { id: 'vu_eye_injury', label: 'Eye injury', system: 'Trauma' },
      { id: 'vu_large_joint_dislocation', label: 'Dislocation of larger joint (not finger / toe)', system: 'Trauma' },
      { id: 'vu_severe_mechanism_of_injury', label: 'Severe mechanism of injury (fall > 1 m, RTA, significant trauma)', system: 'Trauma' },
      { id: 'vu_very_severe_pain', label: 'Very severe pain (≥ 7 / 10)', system: 'Trauma' },
      { id: 'vu_pregnant_abdominal_trauma', label: 'Pregnant + abdominal trauma', system: 'Trauma' },
    ],
  },
  {
    system: 'Additional signs (kept)', icon: '➕', color: 'text-orange-700', bgColor: 'bg-orange-50',
    items: [
      { id: 'ex_vu_severe_htn', label: 'Severe hypertension (SBP ≥ 180 / DBP ≥ 120)', system: 'Additional' },
      { id: 'ex_vu_pulmonary_oedema', label: 'Acute pulmonary oedema', system: 'Additional' },
      { id: 'ex_vu_dvt_pe', label: 'Suspected DVT / pulmonary embolism', system: 'Additional' },
      { id: 'ex_vu_pneumothorax', label: 'Suspected pneumothorax', system: 'Additional' },
      { id: 'ex_vu_prolonged_seizure', label: 'Prolonged / post-ictal seizure (> 5 min)', system: 'Additional' },
      { id: 'ex_vu_severe_abdo_pain', label: 'Severe abdominal pain (guarding / rigidity)', system: 'Additional' },
      { id: 'ex_vu_persistent_vomiting', label: 'Persistent vomiting with dehydration', system: 'Additional' },
      { id: 'ex_vu_eclampsia', label: 'Pre-eclampsia / eclampsia signs', system: 'Additional' },
      { id: 'ex_vu_anaphylaxis', label: 'Anaphylaxis / severe allergic reaction', system: 'Additional' },
      { id: 'ex_vu_acute_psychosis', label: 'Acute psychosis / self-harm with injury', system: 'Additional' },
    ],
  },
];

export const ADULT_URG_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Medical — national form', icon: '🩺', color: 'text-yellow-700', bgColor: 'bg-yellow-50',
    items: [
      { id: 'urg_unable_to_drink_vomits', label: 'Unable to drink or vomits everything', system: 'Medical' },
      { id: 'urg_abdominal_pain', label: 'Abdominal pain', system: 'Medical' },
      { id: 'urg_very_pale', label: 'Very pale', system: 'Medical' },
      { id: 'urg_pregnant_vaginal_bleeding', label: 'Pregnant + vaginal bleeding', system: 'Medical' },
      { id: 'urg_diabetic_very_high_glucose', label: 'Diabetic, glucose > 17 mmol/L (300 mg/dL)', system: 'Medical' },
      { id: 'urg_foreign_body_aspiration', label: 'Foreign body aspiration', system: 'Medical' },
    ],
  },
  {
    system: 'Trauma / skin — national form', icon: '🩹', color: 'text-yellow-700', bgColor: 'bg-yellow-50',
    items: [
      { id: 'urg_finger_toe_dislocation', label: 'Dislocation — finger or toe', system: 'Trauma' },
      { id: 'urg_closed_fracture', label: 'Fracture — closed', system: 'Trauma' },
      { id: 'urg_burn_without_urgent_signs', label: 'Burn without urgent signs', system: 'Trauma' },
      { id: 'urg_pregnant_trauma_non_abdominal', label: 'Pregnant + trauma (not abdominal)', system: 'Trauma' },
      { id: 'urg_moderate_pain', label: 'Moderate pain (5–6 / 10)', system: 'Trauma' },
      { id: 'urg_laceration_abscess', label: 'Laceration, abscess', system: 'Trauma' },
    ],
  },
  {
    system: 'Additional signs (kept)', icon: '➕', color: 'text-yellow-700', bgColor: 'bg-yellow-50',
    items: [
      { id: 'ex_urg_fever_infant', label: 'Fever in infant < 3 months', system: 'Additional' },
      { id: 'ex_urg_high_fever', label: 'High fever unresponsive to antipyretics', system: 'Additional' },
      { id: 'ex_urg_chronic_exacerbation', label: 'Acute exacerbation of chronic condition', system: 'Additional' },
      { id: 'ex_urg_persistent_headache', label: 'Persistent headache (no red flags)', system: 'Additional' },
      { id: 'ex_urg_minor_head_injury', label: 'Minor head injury (no loss of consciousness)', system: 'Additional' },
      { id: 'ex_urg_dizziness_syncope', label: 'Dizziness / syncope episode', system: 'Additional' },
      { id: 'ex_urg_urinary_retention', label: 'Urinary retention', system: 'Additional' },
      { id: 'ex_urg_soft_tissue', label: 'Significant soft-tissue injury', system: 'Additional' },
      { id: 'ex_urg_allergic_localized', label: 'Localised allergic reaction', system: 'Additional' },
      { id: 'ex_urg_minor_eye_injury', label: 'Minor eye injury / foreign body (vision intact)', system: 'Additional' },
      { id: 'ex_urg_epistaxis', label: 'Uncontrolled epistaxis', system: 'Additional' },
      { id: 'ex_urg_anxiety', label: 'Anxiety / panic (no self-harm risk)', system: 'Additional' },
    ],
  },
];

export interface RoutedDiscriminators {
  /** national backend booleans to set true */
  fields: Record<string, boolean>;
  /** labels of checked EXTRA signs → additional-signs channel */
  extraLabels: string[];
}

/**
 * Split a checked-discriminator map into national backend booleans (via ADULT_DISCRIMINATOR_FIELD)
 * and extra-sign labels (for the additional-signs channel). No sign is dropped: a national item
 * sets its 1:1 flag; an extra item contributes its label to the additional list.
 */
export function routeDiscriminators(
  groups: DiscriminatorGroup[],
  checked: Record<string, boolean>,
): RoutedDiscriminators {
  const fields: Record<string, boolean> = {};
  const extraLabels: string[] = [];
  for (const g of groups) {
    for (const it of g.items) {
      if (!checked[it.id]) continue;
      const field = ADULT_DISCRIMINATOR_FIELD[it.id];
      if (field) fields[field] = true;
      else extraLabels.push(it.label);
    }
  }
  return { fields, extraLabels };
}
