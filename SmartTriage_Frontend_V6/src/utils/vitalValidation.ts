/**
 * Vital Sign Input Validation Utility
 * King Faisal Hospital, Kigali — Module 3: TEWS Calculator Enhancement
 *
 * Validates vital sign inputs against physiologic ranges:
 *   - IMPOSSIBLE: values that cannot exist (e.g. HR 500, Temp 50°C)
 *   - CRITICAL:   extreme but survivable values requiring immediate action
 *   - WARNING:    outside normal but clinically possible
 *   - NORMAL:     within expected range
 *
 * All ranges are evidence-based (WHO, PALS, ATLS guidelines).
 */

// ── Types ──────────────────────────────────────────────

export type ValidationSeverity = 'normal' | 'warning' | 'critical' | 'impossible';

export interface ValidationResult {
  field: string;
  label: string;
  value: number | null;
  severity: ValidationSeverity;
  message: string;
  /** Suggested corrected range if impossible */
  suggestedRange?: { min: number; max: number };
}

export interface VitalRangeSpec {
  /** Absolute physiologic limits — outside this = impossible / data-entry error */
  absoluteMin: number;
  absoluteMax: number;
  /** Critical low / high — still possible but life-threatening */
  criticalMin: number;
  criticalMax: number;
  /** Normal range */
  normalMin: number;
  normalMax: number;
  unit: string;
  label: string;
}

// ── Adult physiologic ranges ──────────────────────────

const ADULT_RANGES: Record<string, VitalRangeSpec> = {
  heartRate: {
    absoluteMin: 10,
    absoluteMax: 300,
    criticalMin: 40,
    criticalMax: 180,
    normalMin: 60,
    normalMax: 100,
    unit: 'bpm',
    label: 'Heart Rate',
  },
  respiratoryRate: {
    absoluteMin: 2,
    absoluteMax: 80,
    criticalMin: 8,
    criticalMax: 40,
    normalMin: 12,
    normalMax: 20,
    unit: '/min',
    label: 'Respiratory Rate',
  },
  systolicBP: {
    absoluteMin: 30,
    absoluteMax: 300,
    criticalMin: 70,
    criticalMax: 220,
    normalMin: 90,
    normalMax: 140,
    unit: 'mmHg',
    label: 'Systolic BP',
  },
  diastolicBP: {
    absoluteMin: 10,
    absoluteMax: 200,
    criticalMin: 40,
    criticalMax: 130,
    normalMin: 60,
    normalMax: 90,
    unit: 'mmHg',
    label: 'Diastolic BP',
  },
  temperature: {
    absoluteMin: 25.0,
    absoluteMax: 45.0,
    criticalMin: 32.0,
    criticalMax: 41.5,
    normalMin: 36.1,
    normalMax: 37.5,
    unit: '\u00b0C',
    label: 'Temperature',
  },
  spo2: {
    absoluteMin: 30,
    absoluteMax: 100,
    criticalMin: 70,
    criticalMax: 100,  // Can't be over 100
    normalMin: 94,
    normalMax: 100,
    unit: '%',
    label: 'SpO\u2082',
  },
  glucose: {
    absoluteMin: 0.5,
    absoluteMax: 50.0,
    criticalMin: 2.0,
    criticalMax: 30.0,
    normalMin: 3.9,
    normalMax: 7.8,
    unit: 'mmol/L',
    label: 'Blood Glucose',
  },
  pulse: {
    absoluteMin: 10,
    absoluteMax: 300,
    criticalMin: 40,
    criticalMax: 180,
    normalMin: 60,
    normalMax: 100,
    unit: 'bpm',
    label: 'Pulse Rate',
  },
};

// ── Pediatric physiologic ranges (age-adjusted) ──────

interface PediatricRangeSet {
  /** Max age in years for this set (exclusive) */
  maxAge: number;
  label: string;
  ranges: Record<string, VitalRangeSpec>;
}

const PEDIATRIC_RANGE_SETS: PediatricRangeSet[] = [
  {
    maxAge: 0.25, // 0–3 months
    label: 'Neonate (0–3 mo)',
    ranges: {
      heartRate: {
        absoluteMin: 50,  absoluteMax: 250,
        criticalMin: 80,  criticalMax: 220,
        normalMin: 100,   normalMax: 180,
        unit: 'bpm', label: 'Heart Rate',
      },
      respiratoryRate: {
        absoluteMin: 10,  absoluteMax: 100,
        criticalMin: 20,  criticalMax: 80,
        normalMin: 30,    normalMax: 60,
        unit: '/min', label: 'Respiratory Rate',
      },
      systolicBP: {
        absoluteMin: 30,  absoluteMax: 150,
        criticalMin: 50,  criticalMax: 110,
        normalMin: 60,    normalMax: 90,
        unit: 'mmHg', label: 'Systolic BP',
      },
      temperature: {
        absoluteMin: 25.0, absoluteMax: 42.5,
        criticalMin: 34.0, criticalMax: 40.5,
        normalMin: 36.5,   normalMax: 37.5,
        unit: '\u00b0C', label: 'Temperature',
      },
      spo2: {
        absoluteMin: 30, absoluteMax: 100,
        criticalMin: 80, criticalMax: 100,
        normalMin: 94,   normalMax: 100,
        unit: '%', label: 'SpO\u2082',
      },
      glucose: {
        absoluteMin: 0.3,  absoluteMax: 40.0,
        criticalMin: 1.7,  criticalMax: 20.0,
        normalMin: 2.6,    normalMax: 6.0,
        unit: 'mmol/L', label: 'Blood Glucose',
      },
    },
  },
  {
    maxAge: 1, // 3–12 months
    label: 'Infant (3–12 mo)',
    ranges: {
      heartRate: {
        absoluteMin: 40,  absoluteMax: 250,
        criticalMin: 70,  criticalMax: 200,
        normalMin: 80,    normalMax: 160,
        unit: 'bpm', label: 'Heart Rate',
      },
      respiratoryRate: {
        absoluteMin: 8,   absoluteMax: 80,
        criticalMin: 15,  criticalMax: 70,
        normalMin: 25,    normalMax: 50,
        unit: '/min', label: 'Respiratory Rate',
      },
      systolicBP: {
        absoluteMin: 35,  absoluteMax: 160,
        criticalMin: 55,  criticalMax: 120,
        normalMin: 70,    normalMax: 100,
        unit: 'mmHg', label: 'Systolic BP',
      },
      temperature: {
        absoluteMin: 25.0, absoluteMax: 42.5,
        criticalMin: 34.0, criticalMax: 40.5,
        normalMin: 36.5,   normalMax: 37.5,
        unit: '\u00b0C', label: 'Temperature',
      },
      spo2: {
        absoluteMin: 30, absoluteMax: 100,
        criticalMin: 80, criticalMax: 100,
        normalMin: 94,   normalMax: 100,
        unit: '%', label: 'SpO\u2082',
      },
      glucose: {
        absoluteMin: 0.3,  absoluteMax: 40.0,
        criticalMin: 1.7,  criticalMax: 20.0,
        normalMin: 2.6,    normalMax: 6.0,
        unit: 'mmol/L', label: 'Blood Glucose',
      },
    },
  },
  {
    maxAge: 5, // 1–5 years
    label: 'Toddler (1–5 yr)',
    ranges: {
      heartRate: {
        absoluteMin: 30,  absoluteMax: 240,
        criticalMin: 60,  criticalMax: 180,
        normalMin: 80,    normalMax: 140,
        unit: 'bpm', label: 'Heart Rate',
      },
      respiratoryRate: {
        absoluteMin: 5,   absoluteMax: 70,
        criticalMin: 12,  criticalMax: 50,
        normalMin: 20,    normalMax: 40,
        unit: '/min', label: 'Respiratory Rate',
      },
      systolicBP: {
        absoluteMin: 40,  absoluteMax: 180,
        criticalMin: 60,  criticalMax: 130,
        normalMin: 75,    normalMax: 110,
        unit: 'mmHg', label: 'Systolic BP',
      },
      temperature: {
        absoluteMin: 25.0, absoluteMax: 42.5,
        criticalMin: 34.0, criticalMax: 40.5,
        normalMin: 36.5,   normalMax: 37.5,
        unit: '\u00b0C', label: 'Temperature',
      },
      spo2: {
        absoluteMin: 30, absoluteMax: 100,
        criticalMin: 80, criticalMax: 100,
        normalMin: 94,   normalMax: 100,
        unit: '%', label: 'SpO\u2082',
      },
      glucose: {
        absoluteMin: 0.3,  absoluteMax: 40.0,
        criticalMin: 2.0,  criticalMax: 25.0,
        normalMin: 3.3,    normalMax: 6.7,
        unit: 'mmol/L', label: 'Blood Glucose',
      },
    },
  },
  {
    maxAge: 12, // 5–12 years
    label: 'Child (5–12 yr)',
    ranges: {
      heartRate: {
        absoluteMin: 20,  absoluteMax: 220,
        criticalMin: 50,  criticalMax: 160,
        normalMin: 70,    normalMax: 120,
        unit: 'bpm', label: 'Heart Rate',
      },
      respiratoryRate: {
        absoluteMin: 4,   absoluteMax: 60,
        criticalMin: 10,  criticalMax: 40,
        normalMin: 18,    normalMax: 30,
        unit: '/min', label: 'Respiratory Rate',
      },
      systolicBP: {
        absoluteMin: 45,  absoluteMax: 200,
        criticalMin: 70,  criticalMax: 150,
        normalMin: 80,    normalMax: 120,
        unit: 'mmHg', label: 'Systolic BP',
      },
      temperature: {
        absoluteMin: 25.0, absoluteMax: 43.0,
        criticalMin: 33.0, criticalMax: 41.0,
        normalMin: 36.5,   normalMax: 37.5,
        unit: '\u00b0C', label: 'Temperature',
      },
      spo2: {
        absoluteMin: 30, absoluteMax: 100,
        criticalMin: 75, criticalMax: 100,
        normalMin: 94,   normalMax: 100,
        unit: '%', label: 'SpO\u2082',
      },
      glucose: {
        absoluteMin: 0.5,  absoluteMax: 45.0,
        criticalMin: 2.5,  criticalMax: 25.0,
        normalMin: 3.9,    normalMax: 7.0,
        unit: 'mmol/L', label: 'Blood Glucose',
      },
    },
  },
];

// ── Core validation functions ──────────────────────────

/**
 * Get the appropriate range spec for a vital, accounting for age
 */
export function getVitalRange(
  field: string,
  isPediatric: boolean,
  age?: number,
): VitalRangeSpec | undefined {
  if (isPediatric && age !== undefined) {
    // Find the pediatric range set for this age
    const rangeSet = PEDIATRIC_RANGE_SETS.find((rs) => age < rs.maxAge)
      ?? PEDIATRIC_RANGE_SETS[PEDIATRIC_RANGE_SETS.length - 1];
    return rangeSet.ranges[field] ?? ADULT_RANGES[field];
  }
  return ADULT_RANGES[field];
}

/**
 * Validate a single vital sign value
 */
export function validateVital(
  field: string,
  value: number | null,
  isPediatric: boolean = false,
  age?: number,
): ValidationResult {
  const range = getVitalRange(field, isPediatric, age);

  if (!range) {
    return {
      field,
      label: field,
      value,
      severity: 'normal',
      message: '',
    };
  }

  if (value === null || value === undefined) {
    return {
      field,
      label: range.label,
      value: null,
      severity: 'normal',
      message: 'Not recorded',
    };
  }

  // Check impossible values
  if (value < range.absoluteMin || value > range.absoluteMax) {
    return {
      field,
      label: range.label,
      value,
      severity: 'impossible',
      message: `${range.label} ${value} ${range.unit} is outside physiologic limits (${range.absoluteMin}\u2013${range.absoluteMax} ${range.unit}). Please verify input.`,
      suggestedRange: { min: range.absoluteMin, max: range.absoluteMax },
    };
  }

  // Check critical values
  if (value < range.criticalMin || value > range.criticalMax) {
    return {
      field,
      label: range.label,
      value,
      severity: 'critical',
      message: `${range.label} ${value} ${range.unit} is critically abnormal`,
    };
  }

  // Check warning (outside normal)
  if (value < range.normalMin || value > range.normalMax) {
    return {
      field,
      label: range.label,
      value,
      severity: 'warning',
      message: `${range.label} ${value} ${range.unit} is outside normal range (${range.normalMin}\u2013${range.normalMax} ${range.unit})`,
    };
  }

  // Normal
  return {
    field,
    label: range.label,
    value,
    severity: 'normal',
    message: `${range.label} within normal range`,
  };
}

/**
 * Validate all TEWS input vitals at once
 */
export interface TEWSValidationInput {
  temperature?: number | null;
  respiratoryRate?: number | null;
  heartRate?: number | null;
  pulse?: number | null;
  systolicBP?: number | null;
  spo2?: number | null;
  glucose?: number | null;
  diastolicBP?: number | null;
}

export function validateTEWSInputs(
  inputs: TEWSValidationInput,
  isPediatric: boolean = false,
  age?: number,
): ValidationResult[] {
  const results: ValidationResult[] = [];

  const fields: Array<{ key: keyof TEWSValidationInput; field: string }> = [
    { key: 'temperature', field: 'temperature' },
    { key: 'respiratoryRate', field: 'respiratoryRate' },
    { key: 'heartRate', field: 'heartRate' },
    { key: 'pulse', field: 'pulse' },
    { key: 'systolicBP', field: 'systolicBP' },
    { key: 'diastolicBP', field: 'diastolicBP' },
    { key: 'spo2', field: 'spo2' },
    { key: 'glucose', field: 'glucose' },
  ];

  for (const { key, field } of fields) {
    const val = inputs[key];
    if (val !== undefined) {
      results.push(validateVital(field, val ?? null, isPediatric, age));
    }
  }

  return results;
}

/**
 * Check if any validation results contain impossible values
 */
export function hasImpossibleValues(results: ValidationResult[]): boolean {
  return results.some((r) => r.severity === 'impossible');
}

/**
 * Check if any validation results contain critical values
 */
export function hasCriticalValues(results: ValidationResult[]): boolean {
  return results.some((r) => r.severity === 'critical');
}

/**
 * Get only the validation results that need attention (warning, critical, impossible)
 */
export function getAbnormalValidations(results: ValidationResult[]): ValidationResult[] {
  return results.filter((r) => r.severity !== 'normal');
}

/**
 * Get severity color for UI display
 */
export function getValidationColor(severity: ValidationSeverity): string {
  switch (severity) {
    case 'impossible': return '#dc2626'; // red-600
    case 'critical': return '#ea580c';   // orange-600
    case 'warning': return '#ca8a04';    // yellow-600
    case 'normal': return '#16a34a';     // green-600
  }
}

/**
 * Get severity background color for UI badges
 */
export function getValidationBgColor(severity: ValidationSeverity): string {
  switch (severity) {
    case 'impossible': return 'bg-red-100 text-red-800 border-red-300';
    case 'critical': return 'bg-orange-100 text-orange-800 border-orange-300';
    case 'warning': return 'bg-yellow-100 text-yellow-800 border-yellow-300';
    case 'normal': return 'bg-green-100 text-green-800 border-green-300';
  }
}

/**
 * Get severity icon indicator
 */
export function getValidationIcon(severity: ValidationSeverity): string {
  switch (severity) {
    case 'impossible': return '\u26d4'; // no entry
    case 'critical': return '\u26a0\ufe0f'; // warning
    case 'warning': return '\u25b2';    // triangle
    case 'normal': return '\u2713';     // checkmark
  }
}

// ── Convenience: Get all adult ranges ──

export function getAdultRanges(): Record<string, VitalRangeSpec> {
  return { ...ADULT_RANGES };
}

// ── Convenience: Get pediatric ranges for a specific age ──

export function getPediatricRanges(age: number): Record<string, VitalRangeSpec> {
  const rangeSet = PEDIATRIC_RANGE_SETS.find((rs) => age < rs.maxAge)
    ?? PEDIATRIC_RANGE_SETS[PEDIATRIC_RANGE_SETS.length - 1];
  return { ...rangeSet.ranges };
}

/**
 * Get the age group label for display
 */
export function getPediatricAgeGroupLabel(age: number): string {
  const rangeSet = PEDIATRIC_RANGE_SETS.find((rs) => age < rs.maxAge)
    ?? PEDIATRIC_RANGE_SETS[PEDIATRIC_RANGE_SETS.length - 1];
  return rangeSet.label;
}
