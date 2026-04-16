/**
 * Pediatric TEWS (Triage Early Warning Score) Calculator
 * King Faisal Hospital, Kigali — Infant Triage (0–3 years)
 *
 * Scoring table matches the paper form exactly:
 *   Score:  3       2           1           0           1           2           3
 * ──────────────────────────────────────────────────────────────────────────────
 * Mobility  –       –           –      Normal for age  Unable…      –           –
 * RR        –      <15        15-16       17-21        22-26       ≥27          –
 * HR        –      <60        60-79       80-99       100-129      ≥130         –
 * Temp      –   Cold/<35        –        35-38.4    Hot/>38.4      –           –
 * AVPU      –       –        Confused     Alert       Voice       Pain     Unresponsive
 * Trauma    –       –           –          No          Yes          –           –
 */

// ── Types ──────────────────────────────────────────────

export type PedMobility = 'NORMAL' | 'UNABLE';
export type PedAVPU = 'ALERT' | 'CONFUSED' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE';

export interface PediatricTEWSInput {
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

/** RR scoring per paper form (infant 0-3) */
function getRRScore(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 15) return 2;
  if (rr <= 16) return 1;
  if (rr <= 21) return 0;
  if (rr <= 26) return 1;
  return 2; // ≥ 27
}

/** HR scoring per paper form */
function getHRScore(hr: number | null): number {
  if (hr === null) return 0;
  if (hr < 60) return 2;
  if (hr <= 79) return 1;
  if (hr <= 99) return 0;
  if (hr <= 129) return 1;
  return 2; // ≥ 130
}

/** Temperature scoring */
function getTempScore(temp: number | null): number {
  if (temp === null) return 0;
  if (temp < 35) return 2;  // cold / under 35
  if (temp <= 38.4) return 0;
  return 1; // hot / over 38.4
}

/** AVPU scoring — differs from adult (Confused added) */
function getAVPUScore(avpu: PedAVPU): number {
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

// ── Column highlighting (which column was matched for the table) ──

function getRRColumn(rr: number | null): number {
  if (rr === null) return 0;
  if (rr < 15) return -2;
  if (rr <= 16) return -1;
  if (rr <= 21) return 0;
  if (rr <= 26) return 1;
  return 2;
}

function getHRColumn(hr: number | null): number {
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
  return 1;
}

function getAVPUColumn(avpu: PedAVPU): number {
  switch (avpu) {
    case 'ALERT': return 0;
    case 'CONFUSED': return -1;
    case 'VOICE': return 1;
    case 'PAIN': return 2;
    case 'UNRESPONSIVE': return 3;
  }
}

// ── Main calculation ─────────────────────

export function calculatePediatricTEWS(input: PediatricTEWSInput): PediatricTEWSScoring {
  const scoring: PediatricTEWSScoring = {
    mobilityScore: getMobilityScore(input.mobility),
    respiratoryRateScore: getRRScore(input.respiratoryRate),
    heartRateScore: getHRScore(input.heartRate),
    temperatureScore: getTempScore(input.temperature),
    avpuScore: getAVPUScore(input.avpu),
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
    rr: getRRColumn(input.respiratoryRate),
    hr: getHRColumn(input.heartRate),
    temp: getTempColumn(input.temperature),
    avpu: getAVPUColumn(input.avpu),
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

  if (hasUrgentSigns) {
    return { category: 'YELLOW', reason: 'Urgent criteria present', maxTimeToDoctor: '30 minutes' };
  }

  // Default
  return { category: 'GREEN', reason: 'No urgent criteria', maxTimeToDoctor: '60 minutes' };
}

// ── Infant normal ranges (0-3 years) convenience ──

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
  // 1–3 years
  return {
    heartRate: { label: 'Heart Rate', unit: 'bpm', min: 80, max: 140 },
    respiratoryRate: { label: 'Respiratory Rate', unit: '/min', min: 20, max: 40 },
    systolicBP: { label: 'Systolic BP', unit: 'mmHg', min: 75, max: 110 },
    spo2: { label: 'SpO₂', unit: '%', min: 94, max: 100 },
    temperature: { label: 'Temperature', unit: '°C', min: 36.5, max: 37.5 },
    glucose: { label: 'Blood Glucose', unit: 'mmol/L', min: 2.6, max: 6.0 },
  };
}

/** Returns 'normal' | 'warning' | 'critical' for vital sign colour coding */
export function getVitalStatus(
  value: number | null,
  range: InfantNormalRange,
): 'normal' | 'warning' | 'critical' {
  if (value === null) return 'normal';
  if (value >= range.min && value <= range.max) return 'normal';
  // More than 20% outside range → critical
  const span = range.max - range.min;
  if (value < range.min - span * 0.2 || value > range.max + span * 0.2) return 'critical';
  return 'warning';
}
