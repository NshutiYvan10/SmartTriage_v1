/**
 * mSAT Discriminator Lists
 * ─────────────────────────
 * Used in Step 3 of the mSAT protocol when TEWS score is 0-4.
 * Nurses check discriminator symptoms to differentiate between:
 *   - Very Urgent (→ ORANGE, 10 min)
 *   - Urgent (→ YELLOW, 30 min)
 *   - Routine (→ GREEN, 60 min)
 *
 * Organised by clinical system for rapid scanning.
 */

// ── Types ──────────────────────────────────────────────

export interface DiscriminatorItem {
  id: string;
  label: string;
  system: string; // clinical system grouping
}

export interface DiscriminatorGroup {
  system: string;
  icon: string; // emoji or short label
  color: string; // tailwind text-color
  bgColor: string; // tailwind bg-color
  items: DiscriminatorItem[];
}

// ── Very Urgent Discriminators (→ ORANGE) ──────────────

export const VERY_URGENT_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Cardiovascular',
    icon: 'CV',
    color: 'text-red-700',
    bgColor: 'bg-red-50',
    items: [
      { id: 'vu_chest_pain', label: 'Chest pain (ischaemic type / pleuritic)', system: 'Cardiovascular' },
      { id: 'vu_severe_hypertension', label: 'Severe hypertension (SBP ≥ 180 or DBP ≥ 120)', system: 'Cardiovascular' },
      { id: 'vu_acute_pulmonary_oedema', label: 'Acute pulmonary oedema', system: 'Cardiovascular' },
      { id: 'vu_dvt_pe', label: 'Suspected DVT / Pulmonary embolism', system: 'Cardiovascular' },
    ],
  },
  {
    system: 'Respiratory',
    icon: 'RS',
    color: 'text-blue-700',
    bgColor: 'bg-blue-50',
    items: [
      { id: 'vu_acute_asthma', label: 'Acute asthma (unable to complete sentences)', system: 'Respiratory' },
      { id: 'vu_haemoptysis', label: 'Haemoptysis (significant volume)', system: 'Respiratory' },
      { id: 'vu_pneumothorax', label: 'Suspected pneumothorax', system: 'Respiratory' },
    ],
  },
  {
    system: 'Neurological',
    icon: '🧠',
    color: 'text-purple-700',
    bgColor: 'bg-purple-50',
    items: [
      { id: 'vu_severe_headache', label: 'Severe headache with altered mental status / meningism', system: 'Neurological' },
      { id: 'vu_acute_focal_deficit', label: 'New-onset focal neurological deficit (stroke signs)', system: 'Neurological' },
      { id: 'vu_status_post_seizure', label: 'Post-ictal or prolonged seizure (> 5 min)', system: 'Neurological' },
    ],
  },
  {
    system: 'Abdominal',
    icon: '🩺',
    color: 'text-amber-700',
    bgColor: 'bg-amber-50',
    items: [
      { id: 'vu_severe_abdominal_pain', label: 'Severe abdominal pain with guarding / rigidity', system: 'Abdominal' },
      { id: 'vu_gi_haemorrhage', label: 'GI haemorrhage (haematemesis / melaena)', system: 'Abdominal' },
      { id: 'vu_persistent_vomiting', label: 'Persistent vomiting with dehydration signs', system: 'Abdominal' },
    ],
  },
  {
    system: 'Obstetric / Gynaecological',
    icon: '🤰',
    color: 'text-pink-700',
    bgColor: 'bg-pink-50',
    items: [
      { id: 'vu_vaginal_bleeding_pregnancy', label: 'Vaginal bleeding in pregnancy', system: 'Obstetric / Gynaecological' },
      { id: 'vu_eclampsia_signs', label: 'Pre-eclampsia / eclampsia signs', system: 'Obstetric / Gynaecological' },
    ],
  },
  {
    system: 'Musculoskeletal / Trauma',
    icon: '🦴',
    color: 'text-orange-700',
    bgColor: 'bg-orange-50',
    items: [
      { id: 'vu_open_fracture', label: 'Open / compound fracture', system: 'Musculoskeletal / Trauma' },
      { id: 'vu_limb_ischaemia', label: 'Suspected limb ischaemia (pulseless limb)', system: 'Musculoskeletal / Trauma' },
      { id: 'vu_suspected_spinal', label: 'Suspected spinal cord injury', system: 'Musculoskeletal / Trauma' },
    ],
  },
  {
    system: 'Other',
    icon: '⚠️',
    color: 'text-rose-700',
    bgColor: 'bg-rose-50',
    items: [
      { id: 'vu_severe_allergic', label: 'Severe allergic reaction (anaphylaxis features)', system: 'Other' },
      { id: 'vu_eye_injury_vision', label: 'Eye injury with acute vision loss', system: 'Other' },
      { id: 'vu_diabetic_emergency', label: 'Diabetic emergency (DKA / HHS signs)', system: 'Other' },
      { id: 'vu_acute_psychosis', label: 'Acute psychosis / self-harm with injury', system: 'Other' },
    ],
  },
];

// ── Urgent Discriminators (→ YELLOW) ──────────────────

export const URGENT_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Pain & General',
    icon: '💊',
    color: 'text-yellow-700',
    bgColor: 'bg-yellow-50',
    items: [
      { id: 'u_moderate_pain', label: 'Moderate pain (4-7 / 10)', system: 'Pain & General' },
      { id: 'u_fever_infant', label: 'Fever in infant < 3 months', system: 'Pain & General' },
      { id: 'u_high_fever', label: 'High fever (≥ 39°C) not responding to antipyretics', system: 'Pain & General' },
      { id: 'u_chronic_exacerbation', label: 'Acute exacerbation of known chronic condition', system: 'Pain & General' },
    ],
  },
  {
    system: 'Neurological',
    icon: '🧠',
    color: 'text-indigo-700',
    bgColor: 'bg-indigo-50',
    items: [
      { id: 'u_headache_persistent', label: 'Persistent headache (no red flags)', system: 'Neurological' },
      { id: 'u_minor_head_injury', label: 'Minor head injury without loss of consciousness', system: 'Neurological' },
      { id: 'u_dizziness_syncope', label: 'Dizziness / Syncope episode', system: 'Neurological' },
    ],
  },
  {
    system: 'Abdominal / GI',
    icon: '🩺',
    color: 'text-lime-700',
    bgColor: 'bg-lime-50',
    items: [
      { id: 'u_vomiting_dehydration', label: 'Vomiting or diarrhoea with mild dehydration', system: 'Abdominal / GI' },
      { id: 'u_abdominal_pain_moderate', label: 'Abdominal pain (moderate, no peritonism)', system: 'Abdominal / GI' },
      { id: 'u_urinary_retention', label: 'Urinary retention', system: 'Abdominal / GI' },
    ],
  },
  {
    system: 'Musculoskeletal',
    icon: '🦴',
    color: 'text-teal-700',
    bgColor: 'bg-teal-50',
    items: [
      { id: 'u_closed_fracture', label: 'Suspected closed fracture (no deformity)', system: 'Musculoskeletal' },
      { id: 'u_joint_dislocation', label: 'Joint dislocation / effusion', system: 'Musculoskeletal' },
      { id: 'u_soft_tissue', label: 'Significant soft-tissue injury', system: 'Musculoskeletal' },
    ],
  },
  {
    system: 'Skin & Wounds',
    icon: '🩹',
    color: 'text-emerald-700',
    bgColor: 'bg-emerald-50',
    items: [
      { id: 'u_laceration_sutures', label: 'Laceration requiring sutures', system: 'Skin & Wounds' },
      { id: 'u_minor_burns', label: 'Minor burns (< 10% BSA, no airway)', system: 'Skin & Wounds' },
      { id: 'u_abscess_cellulitis', label: 'Abscess / Cellulitis requiring drainage', system: 'Skin & Wounds' },
    ],
  },
  {
    system: 'Other',
    icon: '📋',
    color: 'text-slate-700',
    bgColor: 'bg-slate-50',
    items: [
      { id: 'u_allergic_localized', label: 'Allergic reaction (localised, no systemic signs)', system: 'Other' },
      { id: 'u_eye_injury_minor', label: 'Eye injury / foreign body (vision intact)', system: 'Other' },
      { id: 'u_epistaxis', label: 'Epistaxis (not controlled with pressure)', system: 'Other' },
      { id: 'u_anxiety_crisis', label: 'Anxiety / panic attack (no self-harm risk)', system: 'Other' },
    ],
  },
];

// ── KFH Pediatric Triage Form — Very Urgent Discriminators ─────
//
// Item IDs match `PerformTriageRequest` boolean field names (in
// snake_case → camelCase translation), so the form's
// `checkedVeryUrgent[id]` map can be passed straight through to the
// backend by deriving the request field from the id.
//
// Items marked INFANT_ONLY appear only on the KFH Infant form (0–3);
// items marked CHILD_ONLY appear only on the Child form (3–12). The
// form filters by ageBand at render time.

export const PEDIATRIC_VERY_URGENT_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Medical',
    icon: '🩺',
    color: 'text-rose-700',
    bgColor: 'bg-rose-50',
    items: [
      { id: 'vu_peds_more_sleepy_than_normal', label: 'Presenting complaint: more sleepy than normal', system: 'Medical' },
      { id: 'vu_focal_neuro_deficit', label: 'Focal neurologic deficit — acute (less than 1 day)', system: 'Medical' },
      { id: 'vu_peds_inconsolable_severe_pain', label: 'Inconsolable crying / severe pain (pain ≥ 7)', system: 'Medical' },
      { id: 'vu_peds_floppy_irritable_restless', label: 'Floppy, irritable, or restless', system: 'Medical' },
      { id: 'vu_chest_pain', label: 'Chest pain', system: 'Medical' },
      { id: 'vu_poisoning_overdose', label: 'Poisoning / overdose', system: 'Medical' },
      // INFANT_ONLY — form filters this out for ≥36 months
      { id: 'vu_peds_tiny_baby_under_2_months', label: 'Tiny baby (younger than 2 months)', system: 'Medical' },
      // CHILD_ONLY — appears only on the 3–12 form
      { id: 'vu_pregnant_abdominal_pain', label: 'Pregnant + abdominal pain', system: 'Medical' },
    ],
  },
  {
    system: 'Trauma',
    icon: '⚠️',
    color: 'text-orange-700',
    bgColor: 'bg-orange-50',
    items: [
      // Peds form burn threshold (10%) — distinct from adult (20%)
      { id: 'vu_peds_burn_over_10_percent', label: 'Burn over 10%, or urgent signs (electrical, chemical, circumferential)', system: 'Trauma' },
      { id: 'vu_open_fracture', label: 'Fracture — open (with skin break)', system: 'Trauma' },
      { id: 'vu_threatened_limb', label: 'Threatened limb (no pulses or pale)', system: 'Trauma' },
      { id: 'vu_eye_injury', label: 'Eye injury', system: 'Trauma' },
      { id: 'vu_large_joint_dislocation', label: 'Dislocation of larger joint (not finger / toe)', system: 'Trauma' },
      { id: 'vu_severe_mechanism_of_injury', label: 'Severe mechanism of injury (Fall > height, RTA, other)', system: 'Trauma' },
      // CHILD_ONLY
      { id: 'vu_pregnant_abdominal_trauma', label: 'Pregnant and abdominal trauma', system: 'Trauma' },
    ],
  },
];

// ── KFH Pediatric Triage Form — Urgent Discriminators ──────────

export const PEDIATRIC_URGENT_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Medical',
    icon: '🩺',
    color: 'text-amber-700',
    bgColor: 'bg-amber-50',
    items: [
      { id: 'urg_peds_pitting_edema_face_or_feet', label: 'Pitting oedema of both feet or face', system: 'Medical' },
      { id: 'urg_unable_to_drink_vomits', label: 'Unable to drink or vomits everything', system: 'Medical' },
      { id: 'urg_very_pale', label: 'Severe pallor', system: 'Medical' },
      { id: 'urg_peds_some_respiratory_distress', label: 'Some respiratory distress', system: 'Medical' },
      // CHILD_ONLY — child form has this in URG section
      { id: 'urg_pregnant_vaginal_bleeding', label: 'Pregnant + vaginal bleeding', system: 'Medical' },
      { id: 'urg_peds_severe_malnutrition_wasting', label: 'Severe malnutrition / wasting', system: 'Medical' },
      { id: 'urg_peds_unwell_with_known_diabetes', label: 'Unwell with known diabetes', system: 'Medical' },
      // Composite "Diarrhoea/vomiting + ANY of: sunken eyes, dry mouth,
      // decreased urine output, skin pinch slow but <2 sec". The
      // composite checkbox is on the form; the backend engine also
      // fires when ≥1 of the four sub-flags is set.
      { id: 'urg_peds_diarrhea_vomiting_dehydration', label: 'Diarrhoea / vomiting + dehydration sign (sunken eyes, dry mouth, low urine, slow skin pinch <2s)', system: 'Medical' },
    ],
  },
  {
    system: 'Trauma',
    icon: '⚠️',
    color: 'text-yellow-700',
    bgColor: 'bg-yellow-50',
    items: [
      { id: 'urg_finger_toe_dislocation', label: 'Dislocation — finger or toe', system: 'Trauma' },
      { id: 'urg_closed_fracture', label: 'Fracture — closed', system: 'Trauma' },
      { id: 'urg_burn_without_urgent_signs', label: 'Burn without urgent signs', system: 'Trauma' },
      { id: 'urg_moderate_pain', label: 'Moderate pain (5–6)', system: 'Trauma' },
      // CHILD_ONLY
      { id: 'urg_pregnant_trauma_non_abdominal', label: 'Pregnant + other trauma', system: 'Trauma' },
    ],
  },
];

/**
 * Discriminator IDs that should only render on the INFANT (0–3) form.
 * The triage form filters discriminator items by this set when ageBand
 * is INFANT.
 */
export const INFANT_ONLY_DISCRIMINATOR_IDS = new Set<string>([
  'vu_peds_tiny_baby_under_2_months',
]);

/**
 * Discriminator IDs that should only render on the CHILD (3–12) form.
 * The triage form filters discriminator items by this set when ageBand
 * is CHILD.
 */
export const CHILD_ONLY_DISCRIMINATOR_IDS = new Set<string>([
  'vu_pregnant_abdominal_pain',
  'vu_pregnant_abdominal_trauma',
  'urg_pregnant_vaginal_bleeding',
  'urg_pregnant_trauma_non_abdominal',
]);

// ── Utility Functions ─────────────────────────────────

/**
 * Flatten all discriminator items from a group array into a single list
 */
export function flattenDiscriminators(groups: DiscriminatorGroup[]): DiscriminatorItem[] {
  return groups.flatMap((g) => g.items);
}

/**
 * Check if any discriminator in the given groups is checked
 */
export function hasCheckedDiscriminators(
  groups: DiscriminatorGroup[],
  checked: Record<string, boolean>,
): boolean {
  return groups.some((g) => g.items.some((item) => checked[item.id]));
}

/**
 * Get list of checked discriminator labels for display
 */
export function getCheckedDiscriminatorLabels(
  groups: DiscriminatorGroup[],
  checked: Record<string, boolean>,
): string[] {
  return groups
    .flatMap((g) => g.items)
    .filter((item) => checked[item.id])
    .map((item) => item.label);
}

/**
 * Determine whether discriminator step is required.
 * Per mSAT protocol: only when TEWS 0-4 and no emergency signs.
 */
export function isDiscriminatorRequired(
  tewsScore: number,
  hasEmergencySigns: boolean,
): boolean {
  return !hasEmergencySigns && tewsScore <= 4;
}
