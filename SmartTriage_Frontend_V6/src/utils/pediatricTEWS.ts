/**
 * Pediatric TEWS Calculator — King Faisal Hospital triage forms.
 *
 * Two distinct grids per the official KFH triage forms:
 *
 * INFANT (0–3 years):
 *   Score:    3      2          1         0          1         2          3
 *   Mobility  –      –          –     Normal    –     Unable to    –
 *                                     for age          move normally
 *   RR        –     <20       20-25   26-39    40-49      ≥50         –
 *   HR        –     <70       70-79   80-130  131-159    ≥160         –
 *   Temp      –      –       <35       35-38.4   –       >38.4        –
 *   AVPU      –      –         –       Alert    Voice    Pain    Unresponsive
 *   Trauma    –      –         –         No      Yes       –           –
 *
 * CHILD (3–12 years):
 *   Score:    3      2          1         0          1         2          3
 *   Mobility  –      –          –     Normal    –     Unable to    –
 *                                     for age          walk as normal
 *   RR        –     <15       15-16    17-21    22-26     ≥27          –
 *   HR        –     <60       60-79    80-99   100-129    ≥130         –
 *   Temp      –      –       <35       35-38.4   –       >38.4        –
 *   AVPU      –      –     Confused    Alert    Voice    Pain    Unresponsive
 *   Trauma    –      –         –         No      Yes       –           –
 *
 * The two grids are NOT interchangeable. Scoring an infant with the
 * child grid yields false high-acuity TEWS for healthy infants and
 * masks bradycardia in critically ill infants — both directions are
 * patient-safety failures.
 *
 * Boundary at 36 months (3 years): <36mo → INFANT; ≥36mo → CHILD.
 */

import type { AvpuScore } from '@/api/types';

// ── Types ──────────────────────────────────────────────

export type PedMobility = 'NORMAL' | 'UNABLE';
export type PedAVPU = 'ALERT' | 'CONFUSED' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';

/** Which KFH form a patient is on. */
export type PedAgeBand = 'INFANT' | 'CHILD';

export const INFANT_AGE_BOUNDARY_MONTHS = 36;

export function ageBandFromMonths(ageMonths: number): PedAgeBand {
  return ageMonths < INFANT_AGE_BOUNDARY_MONTHS ? 'INFANT' : 'CHILD';
}

export interface PediatricTEWSInput {
  ageBand: PedAgeBand;
  mobility: PedMobility;
  respiratoryRate: number | null;
  heartRate: number | null;
  temperature: number | null;
  avpu: PedAVPU;
  trauma: boolean;
}

export interface PediatricTEWSScoring {
  mobilityScore: number;
  respiratoryRateScore: number;
  heartRateScore: number;
  temperatureScore: number;
  avpuScore: number;
  traumaScore: number;
  totalScore: number;
}

export interface PediatricTEWSColumn {
  /** Which column (score) was matched for display highlighting */
  mobility: number;
  rr: number;
  hr: number;
  temp: number;
  avpu: number;
  trauma: number;
}

// ── Individual scoring functions ─────────────────────

function getMobilityScore(mobility: PedMobility): number {
  return mobility === 'NORMAL' ? 0 : 1;
}

/** RR scoring — INFANT (0–3 years) per KFH form */
export function getInfantRRScore(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 20) return 2;
  if (rr <= 25) return 1;
  if (rr <= 39) return 0;
  if (rr <= 49) return 1;
  return 2; // ≥ 50
}

/** RR scoring — CHILD (3–12 years) per KFH form */
export function getChildRRScore(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 15) return 2;
  if (rr <= 16) return 1;
  if (rr <= 21) return 0;
  if (rr <= 26) return 1;
  return 2; // ≥ 27
}

/** HR scoring — INFANT per KFH form */
export function getInfantHRScore(hr: number | null): number {
  if (hr === null) return 0;
  if (hr < 70) return 2;
  if (hr <= 79) return 1;
  if (hr <= 130) return 0;
  if (hr <= 159) return 1;
  return 2; // ≥ 160
}

/** HR scoring — CHILD per KFH form */
export function getChildHRScore(hr: number | null): number {
  if (hr === null) return 0;
  if (hr < 60) return 2;
  if (hr <= 79) return 1;
  if (hr <= 99) return 0;
  if (hr <= 129) return 1;
  return 2; // ≥ 130
}

/**
 * Temperature scoring — same on both KFH peds forms.
 *
 * Both extremes score +2. Hypothermia in a sick child is at least as
 * dangerous as fever — sepsis-associated cold shock is a common cause
 * of paeds death in LMIC EDs and must not be under-scored.
 */
export function getTempScore(temp: number | null): number {
  if (temp === null) return 0;
  if (temp < 35) return 2;
  if (temp <= 38.4) return 0;
  return 2; // > 38.4 — was incorrectly +1 prior to KFH form audit
}

/**
 * AVPU scoring. CHILD form has Confused = +1; INFANT form has no
 * Confused column — clamp Confused → 0 for infants so a UI misclick
 * doesn't introduce a score the form doesn't acknowledge.
 */
export function getAVPUScore(avpu: PedAVPU, ageBand: PedAgeBand): number {
  if (avpu === 'CONFUSED' && ageBand === 'INFANT') return 0;
  switch (avpu) {
    case 'ALERT': return 0;
    case 'CONFUSED': return 1;
    case 'VOICE': return 1;
    case 'PAIN': return 2;
    case 'UNRESPONSIVE': return 3;
  }
}

function getTraumaScore(trauma: boolean): number {
  return trauma ? 1 : 0;
}

// ── Column highlighting ──────────────────────────────

function getInfantRRColumn(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 20) return -2;
  if (rr <= 25) return -1;
  if (rr <= 39) return 0;
  if (rr <= 49) return 1;
  return 2;
}
function getChildRRColumn(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 15) return -2;
  if (rr <= 16) return -1;
  if (rr <= 21) return 0;
  if (rr <= 26) return 1;
  return 2;
}
function getInfantHRColumn(hr: number | null): number {
  if (hr === null) return 0;
  if (hr < 70) return -2;
  if (hr <= 79) return -1;
  if (hr <= 130) return 0;
  if (hr <= 159) return 1;
  return 2;
}
function getChildHRColumn(hr: number | null): number {
  if (hr === null) return 0;
  if (hr < 60) return -2;
  if (hr <= 79) return -1;
  if (hr <= 99) return 0;
  if (hr <= 129) return 1;
  return 2;
}
function getTempColumn(temp: number | null): number {
  if (temp === null) return 0;
  if (temp < 35) return -2;
  if (temp <= 38.4) return 0;
  return 2; // right-side score-2 column
}
function getAVPUColumn(avpu: PedAVPU, ageBand: PedAgeBand): number {
  if (avpu === 'CONFUSED' && ageBand === 'INFANT') return 0;
  switch (avpu) {
    case 'ALERT': return 0;
    case 'CONFUSED': return -1;
    case 'VOICE': return 1;
    case 'PAIN': return 2;
    case 'UNRESPONSIVE': return 3;
  }
}

// ── Main calculation ─────────────────────────────────

export function calculatePediatricTEWS(input: PediatricTEWSInput): PediatricTEWSScoring {
  const rrScore = input.ageBand === 'INFANT'
    ? getInfantRRScore(input.respiratoryRate)
    : getChildRRScore(input.respiratoryRate);
  const hrScore = input.ageBand === 'INFANT'
    ? getInfantHRScore(input.heartRate)
    : getChildHRScore(input.heartRate);

  const scoring: PediatricTEWSScoring = {
    mobilityScore: getMobilityScore(input.mobility),
    respiratoryRateScore: rrScore,
    heartRateScore: hrScore,
    temperatureScore: getTempScore(input.temperature),
    avpuScore: getAVPUScore(input.avpu, input.ageBand),
    traumaScore: getTraumaScore(input.trauma),
    totalScore: 0,
  };

  scoring.totalScore =
    scoring.mobilityScore +
    scoring.respiratoryRateScore +
    scoring.heartRateScore +
    scoring.temperatureScore +
    scoring.avpuScore +
    scoring.traumaScore;

  return scoring;
}

export function getPediatricTEWSColumns(input: PediatricTEWSInput): PediatricTEWSColumn {
  return {
    mobility: input.mobility === 'NORMAL' ? 0 : 1,
    rr: input.ageBand === 'INFANT'
      ? getInfantRRColumn(input.respiratoryRate)
      : getChildRRColumn(input.respiratoryRate),
    hr: input.ageBand === 'INFANT'
      ? getInfantHRColumn(input.heartRate)
      : getChildHRColumn(input.heartRate),
    temp: getTempColumn(input.temperature),
    avpu: getAVPUColumn(input.avpu, input.ageBand),
    trauma: input.trauma ? 1 : 0,
  };
}

// ── Category determination (pediatric-specific) ──

export type PedTriageCategory = 'RED' | 'ORANGE' | 'YELLOW' | 'GREEN';

export interface PedCategoryResult {
  category: PedTriageCategory;
  reason: string;
  maxTimeToDoctor: string;
}

export function determinePediatricCategory(
  tewsScore: number,
  spo2: number | null,
  hasEmergencySigns: boolean,
  hasVeryUrgentSigns: boolean = false,
  hasUrgentSigns: boolean = false,
): PedCategoryResult {
  if (hasEmergencySigns) {
    return { category: 'RED', reason: 'Emergency signs present', maxTimeToDoctor: 'Immediate' };
  }
  if (spo2 !== null && spo2 < 92) {
    return { category: 'RED', reason: 'SpO₂ < 92%', maxTimeToDoctor: 'Immediate' };
  }
  if (tewsScore >= 7) {
    return { category: 'RED', reason: `TEWS score ${tewsScore} (≥7)`, maxTimeToDoctor: 'Immediate' };
  }
  if (tewsScore >= 5) {
    return { category: 'ORANGE', reason: `TEWS score ${tewsScore} (5-6)`, maxTimeToDoctor: '10 minutes' };
  }
  if (hasVeryUrgentSigns) {
    return { category: 'ORANGE', reason: 'Very urgent criteria present', maxTimeToDoctor: '10 minutes' };
  }
  if (hasUrgentSigns) {
    return { category: 'YELLOW', reason: 'Urgent criteria present', maxTimeToDoctor: '30 minutes' };
  }
  // TEWS 3-4 alone → YELLOW per the form's "No (VU) and TEWS=3-4" arrow
  if (tewsScore >= 3) {
    return { category: 'YELLOW', reason: `TEWS score ${tewsScore} (3-4), no VU`, maxTimeToDoctor: '30 minutes' };
  }
  return { category: 'GREEN', reason: 'No urgent criteria', maxTimeToDoctor: '60 minutes' };
}

// ── Normal ranges (display-only colour coding) ──

export interface InfantNormalRange {
  label: string;
  unit: string;
  min: number;
  max: number;
}

export function getInfantNormalRanges(ageMonths: number): Record<string, InfantNormalRange> {
  if (ageMonths <= 3) {
    return {
      heartRate: { label: 'Heart Rate', unit: 'bpm', min: 100, max: 180 },
      respiratoryRate: { label: 'Respiratory Rate', unit: '/min', min: 30, max: 60 },
      systolicBP: { label: 'Systolic BP', unit: 'mmHg', min: 60, max: 90 },
      spo2: { label: 'SpO₂', unit: '%', min: 94, max: 100 },
      temperature: { label: 'Temperature', unit: '°C', min: 36.5, max: 37.5 },
      glucose: { label: 'Blood Glucose', unit: 'mmol/L', min: 2.6, max: 6.0 },
    };
  }
  if (ageMonths <= 12) {
    return {
      heartRate: { label: 'Heart Rate', unit: 'bpm', min: 80, max: 160 },
      respiratoryRate: { label: 'Respiratory Rate', unit: '/min', min: 25, max: 50 },
      systolicBP: { label: 'Systolic BP', unit: 'mmHg', min: 70, max: 100 },
      spo2: { label: 'SpO₂', unit: '%', min: 94, max: 100 },
      temperature: { label: 'Temperature', unit: '°C', min: 36.5, max: 37.5 },
      glucose: { label: 'Blood Glucose', unit: 'mmol/L', min: 2.6, max: 6.0 },
    };
  }
  // 1-3 years
  return {
    heartRate: { label: 'Heart Rate', unit: 'bpm', min: 80, max: 140 },
    respiratoryRate: { label: 'Respiratory Rate', unit: '/min', min: 20, max: 40 },
    systolicBP: { label: 'Systolic BP', unit: 'mmHg', min: 75, max: 110 },
    spo2: { label: 'SpO₂', unit: '%', min: 94, max: 100 },
    temperature: { label: 'Temperature', unit: '°C', min: 36.5, max: 37.5 },
    glucose: { label: 'Blood Glucose', unit: 'mmol/L', min: 2.6, max: 6.0 },
  };
}

export function getVitalStatus(
  value: number | null,
  range: InfantNormalRange,
): 'normal' | 'warning' | 'critical' {
  if (value === null) return 'normal';
  if (value >= range.min && value <= range.max) return 'normal';
  const span = range.max - range.min;
  if (value < range.min - span * 0.2 || value > range.max + span * 0.2) return 'critical';
  return 'warning';
}

// Re-export AvpuScore type for backwards compat with existing imports
export type { AvpuScore };
