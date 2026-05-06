/* ═══════════════════════════════════════════════════════════════
   Frontend catalog of the 54 clinical signs we track over time.

   Mirrors the backend `ClinicalSignDefinitions.java` — codes and
   categories MUST stay in sync. Adding a new sign requires updating:
     1. backend ClinicalSignDefinitions.java (mapping + category)
     2. backend V27 (or successor) migration's backfill INSERT
     3. this file (label + category)
   The server rejects unknown codes, so frontend-only additions silently
   fail validation rather than producing bad data.

   Order matches the Rwanda triage form order so the UI reads in form
   order rather than alphabetical.
   ═══════════════════════════════════════════════════════════════ */

import type { ClinicalSignCategory } from '@/api/clinicalSigns';

export interface ClinicalSignDefinition {
  code: string;
  label: string;
  category: ClinicalSignCategory;
  /** True when this sign carries an associated glucose value. */
  carriesNumeric: boolean;
  /** Helper hint for the Update form's numeric field. */
  numericLabel?: string;
}

export const CLINICAL_SIGN_DEFINITIONS: ClinicalSignDefinition[] = [
  // ── Emergency (Section 1) ──
  { code: 'EMERGENCY_AIRWAY_COMPROMISE',           label: 'Airway compromise',                category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_BREATHING_DISTRESS',          label: 'Breathing distress',               category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_SEVERE_RESPIRATORY_DISTRESS', label: 'Severe respiratory distress',      category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_CARDIAC_ARREST',              label: 'Cardiac arrest',                   category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_UNCONTROLLED_HAEMORRHAGE',    label: 'Uncontrolled haemorrhage',         category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_STAB_GUN_WOUND_NECK_CHEST',   label: 'Stab / gunshot wound to neck or chest', category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_CONVULSIONS',                 label: 'Convulsions',                      category: 'EMERGENCY', carriesNumeric: true,  numericLabel: 'Glucose (mmol/L)' },
  { code: 'EMERGENCY_COMA',                        label: 'Coma',                             category: 'EMERGENCY', carriesNumeric: true,  numericLabel: 'Glucose (mmol/L)' },
  { code: 'EMERGENCY_HYPOGLYCAEMIA',               label: 'Hypoglycaemia',                    category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_PURPURIC_RASH',               label: 'Purpuric rash',                    category: 'EMERGENCY', carriesNumeric: false },
  { code: 'EMERGENCY_BURN_FACE_INHALATION',        label: 'Burn — face / inhalation involvement', category: 'EMERGENCY', carriesNumeric: false },

  // ── Pediatric Emergency (Section 1b) ──
  { code: 'PEDS_EMERGENCY_CENTRAL_CYANOSIS',       label: 'Central cyanosis (pediatric)',     category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_PULSE_LOW_OR_ABSENT',    label: 'Pulse low or absent (pediatric)',  category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_COLD_HANDS_COMPOSITE',   label: 'Cold hands — any associated sign', category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_COLD_HANDS_LETHARGIC',   label: 'Cold hands + lethargic',           category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_COLD_HANDS_PULSE_WEAK_FAST', label: 'Cold hands + pulse weak / fast', category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_COLD_HANDS_CAP_REFILL',  label: 'Cold hands + delayed capillary refill', category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_SEVERE_DEHYDRATION',     label: 'Severe dehydration (pediatric)',   category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_DEHYDRATION_SKIN_PINCH', label: 'Dehydration — abnormal skin pinch', category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_DEHYDRATION_LETHARGY',   label: 'Dehydration + lethargy',           category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },
  { code: 'PEDS_EMERGENCY_DEHYDRATION_SUNKEN_EYES', label: 'Dehydration + sunken eyes',       category: 'PEDIATRIC_EMERGENCY', carriesNumeric: false },

  // ── mSAT Very Urgent (Section 3) ──
  { code: 'MSAT_VU_FOCAL_NEUROLOGIC_DEFICIT',      label: 'Focal neurological deficit',       category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_ALTERED_MENTAL_STATUS',         label: 'Altered mental status',            category: 'MSAT_VU', carriesNumeric: true, numericLabel: 'Glucose (mmol/L)' },
  { code: 'MSAT_VU_CHEST_PAIN',                    label: 'Chest pain',                       category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_POISONING_OVERDOSE',            label: 'Poisoning / overdose',             category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_PREGNANT_ABDOMINAL_PAIN',       label: 'Pregnant + abdominal pain',        category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_COUGHING_VOMITING_BLOOD',       label: 'Coughing or vomiting blood',       category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_DIABETIC_HIGH_GLUCOSE',         label: 'Diabetic — high glucose',          category: 'MSAT_VU', carriesNumeric: true, numericLabel: 'Glucose (mmol/L)' },
  { code: 'MSAT_VU_AGGRESSION',                    label: 'Aggression / agitation',           category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_SHORTNESS_OF_BREATH',           label: 'Shortness of breath',              category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_BURN_OVER_20_PERCENT',          label: 'Burn over 20% body surface',       category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_OPEN_FRACTURE',                 label: 'Open fracture',                    category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_THREATENED_LIMB',               label: 'Threatened limb',                  category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_EYE_INJURY',                    label: 'Eye injury',                       category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_LARGE_JOINT_DISLOCATION',       label: 'Large joint dislocation',          category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_SEVERE_MECHANISM_OF_INJURY',    label: 'Severe mechanism of injury',       category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_VERY_SEVERE_PAIN',              label: 'Very severe pain',                 category: 'MSAT_VU', carriesNumeric: false },
  { code: 'MSAT_VU_PREGNANT_ABDOMINAL_TRAUMA',     label: 'Pregnant + abdominal trauma',      category: 'MSAT_VU', carriesNumeric: false },

  // ── mSAT Urgent (Section 4) ──
  { code: 'MSAT_URG_UNABLE_TO_DRINK_VOMITS',       label: 'Unable to drink / vomits',         category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_ABDOMINAL_PAIN',               label: 'Abdominal pain',                   category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_VERY_PALE',                    label: 'Very pale',                        category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_PREGNANT_VAGINAL_BLEEDING',    label: 'Pregnant + vaginal bleeding',      category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_DIABETIC_VERY_HIGH_GLUCOSE',   label: 'Diabetic — very high glucose',     category: 'MSAT_URG', carriesNumeric: true, numericLabel: 'Glucose (mmol/L)' },
  { code: 'MSAT_URG_FINGER_TOE_DISLOCATION',       label: 'Finger / toe dislocation',         category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_CLOSED_FRACTURE',              label: 'Closed fracture',                  category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_BURN_WITHOUT_URGENT_SIGNS',    label: 'Burn without urgent signs',        category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_PREGNANT_TRAUMA_NON_ABDOMINAL', label: 'Pregnant + non-abdominal trauma', category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_MODERATE_PAIN',                label: 'Moderate pain',                    category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_LACERATION_ABSCESS',           label: 'Laceration / abscess',             category: 'MSAT_URG', carriesNumeric: false },
  { code: 'MSAT_URG_FOREIGN_BODY_ASPIRATION',      label: 'Foreign body aspiration',          category: 'MSAT_URG', carriesNumeric: false },

  // ── Special considerations ──
  { code: 'SPECIAL_ACUTE_TRAUMA',                  label: 'Acute trauma',                     category: 'SPECIAL', carriesNumeric: false },
  { code: 'SPECIAL_SEIZURE_HISTORY',               label: 'Seizure history',                  category: 'SPECIAL', carriesNumeric: false },
  { code: 'SPECIAL_ASSAULT_ABUSE',                 label: 'Assault / abuse',                  category: 'SPECIAL', carriesNumeric: false },
  { code: 'SPECIAL_SUICIDE_ATTEMPT',               label: 'Suicide attempt / self-harm',      category: 'SPECIAL', carriesNumeric: false },
];

export const SIGN_BY_CODE: Record<string, ClinicalSignDefinition> = Object.fromEntries(
  CLINICAL_SIGN_DEFINITIONS.map((d) => [d.code, d]),
);

/** Human-readable category label for headings. */
export const CATEGORY_LABEL: Record<ClinicalSignCategory, string> = {
  EMERGENCY: 'Emergency Signs',
  PEDIATRIC_EMERGENCY: 'Pediatric Emergency Signs',
  MSAT_VU: 'mSAT — Very Urgent',
  MSAT_URG: 'mSAT — Urgent',
  SPECIAL: 'Special Considerations',
};

/** Order of categories on the page — emergency first, special last. */
export const CATEGORY_ORDER: ClinicalSignCategory[] = [
  'EMERGENCY',
  'PEDIATRIC_EMERGENCY',
  'MSAT_VU',
  'MSAT_URG',
  'SPECIAL',
];

/** Color tokens per category for chips, badges, banners. */
export const CATEGORY_TONE: Record<ClinicalSignCategory, { text: string; bg: string; border: string; dot: string }> = {
  EMERGENCY:           { text: 'text-red-600',     bg: 'bg-red-500/10',     border: 'border-red-500/30',     dot: 'bg-red-500' },
  PEDIATRIC_EMERGENCY: { text: 'text-rose-600',    bg: 'bg-rose-500/10',    border: 'border-rose-500/30',    dot: 'bg-rose-500' },
  MSAT_VU:             { text: 'text-orange-600',  bg: 'bg-orange-500/10',  border: 'border-orange-500/30',  dot: 'bg-orange-500' },
  MSAT_URG:            { text: 'text-yellow-600',  bg: 'bg-yellow-500/10',  border: 'border-yellow-500/30',  dot: 'bg-yellow-500' },
  SPECIAL:             { text: 'text-violet-600',  bg: 'bg-violet-500/10',  border: 'border-violet-500/30',  dot: 'bg-violet-500' },
};
