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

// ── Pediatric-specific Very Urgent Discriminators ─────

export const PEDIATRIC_VERY_URGENT_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Neonatal / Infant',
    icon: '👶',
    color: 'text-pink-700',
    bgColor: 'bg-pink-50',
    items: [
      { id: 'pvu_inconsolable_cry', label: 'Inconsolable / high-pitched cry', system: 'Neonatal / Infant' },
      { id: 'pvu_poor_feeding', label: 'Poor feeding / refusal to feed (neonate)', system: 'Neonatal / Infant' },
      { id: 'pvu_bulging_fontanel', label: 'Bulging fontanel', system: 'Neonatal / Infant' },
      { id: 'pvu_bile_stained_vomit', label: 'Bile-stained vomiting', system: 'Neonatal / Infant' },
    ],
  },
  {
    system: 'Respiratory',
    icon: 'RS',
    color: 'text-blue-700',
    bgColor: 'bg-blue-50',
    items: [
      { id: 'pvu_stridor_rest', label: 'Stridor at rest (croup / FB)', system: 'Respiratory' },
      { id: 'pvu_wheeze_severe', label: 'Severe wheeze / unable to drink', system: 'Respiratory' },
      { id: 'pvu_barking_cough', label: 'Barking cough with distress', system: 'Respiratory' },
    ],
  },
  {
    system: 'Neurological',
    icon: '🧠',
    color: 'text-purple-700',
    bgColor: 'bg-purple-50',
    items: [
      { id: 'pvu_febrile_seizure', label: 'Febrile seizure (first episode or prolonged)', system: 'Neurological' },
      { id: 'pvu_altered_consciousness', label: 'Altered level of consciousness', system: 'Neurological' },
      { id: 'pvu_severe_headache_child', label: 'Severe headache with vomiting', system: 'Neurological' },
    ],
  },
  {
    system: 'Other',
    icon: '⚠️',
    color: 'text-rose-700',
    bgColor: 'bg-rose-50',
    items: [
      { id: 'pvu_petechial_rash_fever', label: 'Petechial rash with fever (meningococcal risk)', system: 'Other' },
      { id: 'pvu_severe_dehydration', label: 'Severe dehydration (sunken eyes, no tears, reduced turgor)', system: 'Other' },
      { id: 'pvu_suspected_nai', label: 'Suspected non-accidental injury', system: 'Other' },
      { id: 'pvu_ingestion_toxic', label: 'Toxic ingestion / poisoning', system: 'Other' },
    ],
  },
];

// ── Pediatric-specific Urgent Discriminators ──────────

export const PEDIATRIC_URGENT_DISCRIMINATORS: DiscriminatorGroup[] = [
  {
    system: 'Fever & Infection',
    icon: '🌡️',
    color: 'text-orange-700',
    bgColor: 'bg-orange-50',
    items: [
      { id: 'pu_fever_3_36m', label: 'Fever in child 3-36 months (≥ 39°C)', system: 'Fever & Infection' },
      { id: 'pu_ear_pain', label: 'Ear pain / discharge (otitis)', system: 'Fever & Infection' },
      { id: 'pu_sore_throat_drooling', label: 'Sore throat with difficulty swallowing', system: 'Fever & Infection' },
    ],
  },
  {
    system: 'GI / Hydration',
    icon: '💧',
    color: 'text-cyan-700',
    bgColor: 'bg-cyan-50',
    items: [
      { id: 'pu_mild_dehydration', label: 'Mild-moderate dehydration', system: 'GI / Hydration' },
      { id: 'pu_blood_stool', label: 'Blood in stool', system: 'GI / Hydration' },
      { id: 'pu_abdominal_pain', label: 'Abdominal pain with vomiting', system: 'GI / Hydration' },
    ],
  },
  {
    system: 'Musculoskeletal',
    icon: '🦴',
    color: 'text-teal-700',
    bgColor: 'bg-teal-50',
    items: [
      { id: 'pu_limping', label: 'Limping / refusal to weight-bear', system: 'Musculoskeletal' },
      { id: 'pu_closed_fracture', label: 'Suspected closed fracture', system: 'Musculoskeletal' },
      { id: 'pu_laceration', label: 'Laceration requiring sutures', system: 'Musculoskeletal' },
    ],
  },
  {
    system: 'Other',
    icon: '📋',
    color: 'text-slate-700',
    bgColor: 'bg-slate-50',
    items: [
      { id: 'pu_rash_no_fever', label: 'Rash without fever or systemic signs', system: 'Other' },
      { id: 'pu_mild_wheeze', label: 'Mild wheeze (able to drink, no distress)', system: 'Other' },
      { id: 'pu_foreign_body_nose_ear', label: 'Foreign body (nose / ear, no airway risk)', system: 'Other' },
      { id: 'pu_insect_bite', label: 'Insect / animal bite (not venomous, wound care)', system: 'Other' },
    ],
  },
];

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
