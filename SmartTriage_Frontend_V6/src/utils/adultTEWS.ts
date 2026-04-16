/**
 * Adult TEWS (Triage Early Warning Score) Calculator
 * King Faisal Hospital, Kigali — Adult Triage (≥ 12 years)
 *
 * Scoring table (adult-specific ranges — Rwanda National Standard):
 *   Score:  3       2           1           0           1           2           3
 * ──────────────────────────────────────────────────────────────────────────────
 * Mobility  –       –        Stretcher   Walking    With help      –           –
 * RR        –      <9          –         9-14       15-20        21-29        ≥30
 * HR        –     <41        41-50       51-100    101-110      111-129       ≥130
 * SBP       –     <71        71-80       81-100    101-199        –          ≥200
 * Temp      –   Cold/<35       –        35-38.4      –        Hot/>38.4       –
 * AVPU      –       –        Confused     Alert      Voice        Pain     Unresponsive
 * Trauma    –       –           –          No         Yes          –           –
 */

// ── Types ──────────────────────────────────────────────

export type AdultMobility = 'WALKING' | 'WITH_HELP' | 'STRETCHER';
export type AdultAVPU = 'ALERT' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';

export interface AdultTEWSInput {
  mobility: AdultMobility;
  respiratoryRate: number | null;
  heartRate: number | null;
  systolicBP: number | null;
  temperature: number | null;
  avpu: AdultAVPU;
  trauma: boolean;
}

export interface AdultTEWSScoring {
  mobilityScore: number;
  respiratoryRateScore: number;
  heartRateScore: number;
  systolicBPScore: number;
  temperatureScore: number;
  avpuScore: number;
  traumaScore: number;
  totalScore: number;
}

export interface AdultTEWSColumn {
  mobility: number;
  rr: number;
  hr: number;
  sbp: number;
  temp: number;
  avpu: number;
  trauma: number;
}

// ── Individual scoring functions ─────────────────────

function getMobilityScore(mobility: AdultMobility): number {
  switch (mobility) {
    case 'WALKING': return 0;
    case 'WITH_HELP': return 1;
    case 'STRETCHER': return 2;
  }
}

/** RR scoring per adult TEWS */
function getRRScore(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 9) return 1;
  if (rr <= 14) return 0;
  if (rr <= 20) return 1;
  if (rr <= 29) return 2;
  return 3; // ≥ 30
}

/** HR scoring per adult TEWS */
function getHRScore(hr: number | null): number {
  if (hr === null) return 0;
  if (hr < 41) return 2;
  if (hr <= 50) return 1;
  if (hr <= 100) return 0;
  if (hr <= 110) return 1;
  if (hr <= 129) return 2;
  return 3; // ≥ 130
}

/** Systolic BP scoring */
function getSBPScore(sbp: number | null): number {
  if (sbp === null) return 0;
  if (sbp < 71) return 2;
  if (sbp <= 80) return 1;
  if (sbp <= 100) return 0;
  if (sbp <= 199) return 1;
  return 3; // > 199
}

/** Temperature scoring */
function getTempScore(temp: number | null): number {
  if (temp === null) return 0;
  if (temp < 35) return 2;  // cold / under 35
  if (temp <= 38.4) return 0;
  return 2; // hot / over 38.4
}

/** AVPU scoring — adult scale */
function getAVPUScore(avpu: AdultAVPU): number {
  switch (avpu) {
    case 'ALERT': return 0;
    case 'VOICE': return 1;
    case 'PAIN': return 2;
    case 'UNRESPONSIVE': return 3;
  }
}

function getTraumaScore(trauma: boolean): number {
  return trauma ? 1 : 0;
}

// ── Column highlighting ──

function getMobilityColumn(mobility: AdultMobility): number {
  switch (mobility) {
    case 'WALKING': return 0;
    case 'WITH_HELP': return 1;
    case 'STRETCHER': return 2;
  }
}

function getRRColumn(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 9) return -1;
  if (rr <= 14) return 0;
  if (rr <= 20) return 1;
  if (rr <= 29) return 2;
  return 3;
}

function getHRColumn(hr: number | null): number {
  if (hr === null) return 0;
  if (hr < 41) return -2;
  if (hr <= 50) return -1;
  if (hr <= 100) return 0;
  if (hr <= 110) return 1;
  if (hr <= 129) return 2;
  return 3;
}

function getSBPColumn(sbp: number | null): number {
  if (sbp === null) return 0;
  if (sbp < 71) return -2;
  if (sbp <= 80) return -1;
  if (sbp <= 100) return 0;
  if (sbp <= 199) return 1;
  return 3;
}

function getTempColumn(temp: number | null): number {
  if (temp === null) return 0;
  if (temp < 35) return -2;
  if (temp <= 38.4) return 0;
  return 2;
}

function getAVPUColumn(avpu: AdultAVPU): number {
  switch (avpu) {
    case 'ALERT': return 0;
    case 'VOICE': return 1;
    case 'PAIN': return 2;
    case 'UNRESPONSIVE': return 3;
  }
}

// ── Main calculation ─────────────────────

export function calculateAdultTEWS(input: AdultTEWSInput): AdultTEWSScoring {
  const scoring: AdultTEWSScoring = {
    mobilityScore: getMobilityScore(input.mobility),
    respiratoryRateScore: getRRScore(input.respiratoryRate),
    heartRateScore: getHRScore(input.heartRate),
    systolicBPScore: getSBPScore(input.systolicBP),
    temperatureScore: getTempScore(input.temperature),
    avpuScore: getAVPUScore(input.avpu),
    traumaScore: getTraumaScore(input.trauma),
    totalScore: 0,
  };

  scoring.totalScore =
    scoring.mobilityScore +
    scoring.respiratoryRateScore +
    scoring.heartRateScore +
    scoring.systolicBPScore +
    scoring.temperatureScore +
    scoring.avpuScore +
    scoring.traumaScore;

  return scoring;
}

export function getAdultTEWSColumns(input: AdultTEWSInput): AdultTEWSColumn {
  return {
    mobility: getMobilityColumn(input.mobility),
    rr: getRRColumn(input.respiratoryRate),
    hr: getHRColumn(input.heartRate),
    sbp: getSBPColumn(input.systolicBP),
    temp: getTempColumn(input.temperature),
    avpu: getAVPUColumn(input.avpu),
    trauma: input.trauma ? 1 : 0,
  };
}

// ── Category determination (adult-specific, same logic) ──

export type AdultTriageCategory = 'RED' | 'ORANGE' | 'YELLOW' | 'GREEN';

export interface AdultCategoryResult {
  category: AdultTriageCategory;
  reason: string;
  maxTimeToDoctor: string;
}

export function determineAdultCategory(
  tewsScore: number,
  spo2: number | null,
  hasEmergencySigns: boolean,
  hasVeryUrgentSigns: boolean = false,
  hasUrgentSigns: boolean = false,
): AdultCategoryResult {
  // Rule 1: Any emergency sign → RED
  if (hasEmergencySigns) {
    return { category: 'RED', reason: 'Emergency signs present', maxTimeToDoctor: 'Immediate' };
  }

  // Rule 2: SpO₂ < 92% → RED
  if (spo2 !== null && spo2 < 92) {
    return { category: 'RED', reason: 'SpO₂ < 92%', maxTimeToDoctor: 'Immediate' };
  }

  // Rule 3: TEWS ≥ 7 → RED
  if (tewsScore >= 7) {
    return { category: 'RED', reason: `TEWS score ${tewsScore} (≥7)`, maxTimeToDoctor: 'Immediate' };
  }

  // Rule 4: TEWS 5-6 → ORANGE
  if (tewsScore >= 5) {
    return { category: 'ORANGE', reason: `TEWS score ${tewsScore} (5-6)`, maxTimeToDoctor: '10 minutes' };
  }

  // Rule 5: TEWS 0-4 → check Very Urgent / Urgent
  if (hasVeryUrgentSigns) {
    return { category: 'ORANGE', reason: 'Very urgent criteria present', maxTimeToDoctor: '10 minutes' };
  }

  // Rule 6: TEWS 3-4 without VU signs → YELLOW
  if (tewsScore >= 3 && tewsScore <= 4) {
    return { category: 'YELLOW', reason: `TEWS score ${tewsScore} (3-4)`, maxTimeToDoctor: '30 minutes' };
  }

  // Rule 7: TEWS 0-2 with Urgent signs → YELLOW
  if (hasUrgentSigns) {
    return { category: 'YELLOW', reason: 'Urgent criteria present', maxTimeToDoctor: '30 minutes' };
  }

  // Default: TEWS 0-2, no VU, no Urgent → GREEN
  return { category: 'GREEN', reason: 'No urgent criteria', maxTimeToDoctor: '60 minutes' };
}

// ── Adult normal ranges convenience ──

export interface AdultNormalRange {
  label: string;
  unit: string;
  min: number;
  max: number;
}

export function getAdultNormalRanges(): Record<string, AdultNormalRange> {
  return {
    heartRate: { label: 'Heart Rate', unit: 'bpm', min: 60, max: 100 },
    respiratoryRate: { label: 'Respiratory Rate', unit: '/min', min: 12, max: 20 },
    systolicBP: { label: 'Systolic BP', unit: 'mmHg', min: 90, max: 140 },
    diastolicBP: { label: 'Diastolic BP', unit: 'mmHg', min: 60, max: 90 },
    spo2: { label: 'SpO₂', unit: '%', min: 94, max: 100 },
    temperature: { label: 'Temperature', unit: '°C', min: 36.1, max: 37.5 },
    glucose: { label: 'Blood Glucose', unit: 'mmol/L', min: 3.9, max: 7.8 },
  };
}

/** Returns 'normal' | 'warning' | 'critical' for vital sign colour coding */
export function getAdultVitalStatus(
  value: number | null,
  range: AdultNormalRange,
): 'normal' | 'warning' | 'critical' {
  if (value === null) return 'normal';
  if (value >= range.min && value <= range.max) return 'normal';
  const span = range.max - range.min;
  if (value < range.min - span * 0.2 || value > range.max + span * 0.2) return 'critical';
  return 'warning';
}
