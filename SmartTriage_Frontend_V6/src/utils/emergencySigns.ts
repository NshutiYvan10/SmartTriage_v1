import { EmergencySigns } from '@/types';

/**
 * Check if any emergency signs are present
 * If yes, patient gets RED category immediately
 */
export function hasEmergencySigns(signs: EmergencySigns): boolean {
  return (
    signs.airwayCompromise ||
    signs.coma ||
    signs.severeRespiratoryDistress ||
    signs.severeBurns ||
    signs.shockSigns ||
    signs.convulsions ||
    signs.hypoglycemia
  );
}

/**
 * Get list of present emergency signs
 */
export function getActiveEmergencySigns(signs: EmergencySigns): string[] {
  const active: string[] = [];

  if (signs.airwayCompromise) active.push('Airway Compromise');
  if (signs.coma) active.push('Coma (AVPU = P/U)');
  if (signs.severeRespiratoryDistress) active.push('Severe Respiratory Distress');
  if (signs.severeBurns) active.push('Severe Burns');
  if (signs.shockSigns) active.push('Shock Signs');
  if (signs.convulsions) active.push('Convulsions');
  if (signs.hypoglycemia) active.push('Hypoglycemia');

  return active;
}

/**
 * Emergency signs checklist with descriptions
 */
export const EMERGENCY_SIGNS_CHECKLIST = [
  {
    key: 'airwayCompromise' as keyof EmergencySigns,
    label: 'Airway Compromise',
    description: 'Inability to maintain open airway, severe stridor, or total obstruction',
  },
  {
    key: 'coma' as keyof EmergencySigns,
    label: 'Coma (AVPU = P or U)',
    description: 'Patient only responds to pain or is completely unresponsive',
  },
  {
    key: 'severeRespiratoryDistress' as keyof EmergencySigns,
    label: 'Severe Respiratory Distress',
    description: 'Severe difficulty breathing, gasping, cyanosis, or unable to speak',
  },
  {
    key: 'severeBurns' as keyof EmergencySigns,
    label: 'Severe Burns',
    description: 'Burns covering >20% body surface area or involving airway',
  },
  {
    key: 'shockSigns' as keyof EmergencySigns,
    label: 'Shock Signs',
    description: 'Cold clammy skin, weak rapid pulse, altered mental status, low BP',
  },
  {
    key: 'convulsions' as keyof EmergencySigns,
    label: 'Active Convulsions',
    description: 'Currently seizing or post-ictal state with ongoing seizure activity',
  },
  {
    key: 'hypoglycemia' as keyof EmergencySigns,
    label: 'Hypoglycemia',
    description: 'Blood glucose < 3.0 mmol/L (54 mg/dL) with altered consciousness',
  },
];

/**
 * Very Urgent Symptoms Checklist (for TEWS 0-4)
 */
export const VERY_URGENT_SYMPTOMS = [
  'Chest pain',
  'Severe abdominal pain',
  'Persistent vomiting',
  'Severe headache with altered mental status',
  'Vaginal bleeding in pregnancy',
  'Suspected fracture with deformity',
  'Eye injury with vision changes',
  'Severe allergic reaction',
];

/**
 * Urgent Symptoms Checklist (for TEWS 0-4)
 */
export const URGENT_SYMPTOMS = [
  'Moderate pain',
  'Minor head injury without LOC',
  'Fever in infant < 3 months',
  'Vomiting or diarrhea with dehydration',
  'Minor burns',
  'Laceration requiring sutures',
  'Urinary retention',
  'Acute exacerbation of chronic condition',
];
