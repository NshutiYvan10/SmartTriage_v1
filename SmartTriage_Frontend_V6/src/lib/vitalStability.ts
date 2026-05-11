/**
 * V54 — Vital-sign stability evaluator for the triage "Pull from Monitor"
 * flow.
 *
 * A stable reading = at least MIN_AGREEING_SAMPLES consecutive readings
 * from the rolling window agree with each other within the tolerance for
 * that vital. We use the **median** of those agreeing readings as the
 * value to surface (robust against single-reading spikes that mean/
 * average would let through).
 *
 * This module is **pure** — no React, no DOM, no side effects. It exists
 * as its own file so it can be unit-tested in isolation and so the
 * clinical tolerance values live in one obvious place that a reviewer
 * can audit without reading any UI code.
 *
 * Tuning note (Decision 5 — approved): we ship adult-tuned tolerances
 * for both adult and pediatric triage. Real-world feedback may surface
 * an edge case in pediatric RR (kids' breathing is naturally more
 * variable). Widening that single value is a one-line change here.
 */
import type { SignalQuality, VitalStreamResponse } from '@/api/types';

/** The six vitals we capture into the triage form from a monitor. */
export type VitalKey =
  | 'heartRate'
  | 'respiratoryRate'
  | 'spo2'
  | 'systolicBp'
  | 'diastolicBp'
  | 'temperature';

/** Tolerance config — one source of truth for clinical reviewers. */
export interface VitalTolerance {
  /** How close two readings must be to "agree". */
  tolerance: number;
  /** Pretty label for the modal UI. */
  label: string;
  /** Unit suffix for the modal UI. */
  unit: string;
  /** Decimal places to display. */
  decimals: number;
}

export const VITAL_TOLERANCES: Record<VitalKey, VitalTolerance> = {
  heartRate:        { tolerance: 5,   label: 'Heart Rate',     unit: 'bpm',  decimals: 0 },
  respiratoryRate:  { tolerance: 3,   label: 'Resp Rate',      unit: '/min', decimals: 0 },
  spo2:             { tolerance: 2,   label: 'SpO₂',           unit: '%',    decimals: 0 },
  systolicBp:       { tolerance: 5,   label: 'BP Systolic',    unit: 'mmHg', decimals: 0 },
  diastolicBp:      { tolerance: 5,   label: 'BP Diastolic',   unit: 'mmHg', decimals: 0 },
  temperature:      { tolerance: 0.2, label: 'Temperature',    unit: '°C',   decimals: 1 },
};

/**
 * How many consecutive in-tolerance readings make a vital "stable."
 * Three is a clinically-defensible minimum without making the nurse wait
 * forever. At the 5-second device cadence, this is a ~15-second window.
 */
export const MIN_AGREEING_SAMPLES = 3;

/**
 * Maximum window we look at, in number of readings. With 5s cadence this
 * is a 25-second rolling window — long enough to capture stability,
 * short enough to discard ancient data when the patient changes state
 * (e.g. someone just removed the SpO₂ probe).
 */
export const STABILITY_WINDOW_SIZE = 5;

/** Signal-quality values we treat as "good enough to consider." */
const ACCEPTABLE_QUALITY: ReadonlySet<SignalQuality> = new Set(['GOOD', 'ACCEPTABLE']);

export type StabilityState = 'stable' | 'stabilizing' | 'unstable';

export interface VitalStabilityResult {
  /** The vital this result is for. */
  key: VitalKey;
  /** Current stability classification. */
  state: StabilityState;
  /** The value to use when state === 'stable' (median of agreeing window). */
  value: number | null;
  /** Latest raw reading (for the "currently showing…" preview). */
  latest: number | null;
  /** How many samples currently agree (0…MIN_AGREEING_SAMPLES). */
  agreeingCount: number;
  /** Human-readable reason when state === 'unstable'. */
  reason: string | null;
}

/**
 * Take a rolling window of stream readings (newest last) and classify
 * each vital. Pure — same inputs always produce the same outputs.
 */
export function evaluateStability(
  readings: ReadonlyArray<VitalStreamResponse>,
  key: VitalKey,
): VitalStabilityResult {
  const cfg = VITAL_TOLERANCES[key];

  // Most recent valid (signal-quality-acceptable) readings, newest first
  const validNewestFirst = [...readings]
    .reverse()
    .filter(r => ACCEPTABLE_QUALITY.has(r.signalQuality) && r[key] != null);

  const latestRaw = readings.length > 0 ? readings[readings.length - 1][key] : null;

  if (validNewestFirst.length === 0) {
    return {
      key,
      state: 'unstable',
      value: null,
      latest: latestRaw,
      agreeingCount: 0,
      reason: latestRaw == null
        ? 'No reading from monitor yet'
        : 'Signal quality too poor — check probe / lead placement',
    };
  }

  // Walk newest → older. Keep collecting while each next reading is within
  // tolerance of every reading already in the cluster (strict definition —
  // stops a slow drift from being called "stable").
  const cluster: number[] = [];
  for (const r of validNewestFirst) {
    const v = r[key] as number;
    if (cluster.length === 0) {
      cluster.push(v);
      continue;
    }
    const minC = Math.min(...cluster);
    const maxC = Math.max(...cluster);
    if (Math.abs(v - minC) <= cfg.tolerance && Math.abs(v - maxC) <= cfg.tolerance) {
      cluster.push(v);
    } else {
      break;
    }
  }

  const latest = validNewestFirst[0][key] as number;

  if (cluster.length >= MIN_AGREEING_SAMPLES) {
    return {
      key,
      state: 'stable',
      value: median(cluster),
      latest,
      agreeingCount: Math.min(cluster.length, MIN_AGREEING_SAMPLES),
      reason: null,
    };
  }

  return {
    key,
    state: 'stabilizing',
    value: null,
    latest,
    agreeingCount: cluster.length,
    reason: null,
  };
}

/** Median (robust to single-reading spikes — see module docstring). */
function median(nums: ReadonlyArray<number>): number {
  if (nums.length === 0) return 0;
  const sorted = [...nums].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[mid - 1] + sorted[mid]) / 2
    : sorted[mid];
}

/** Round a stable value to its display precision before writing to a form. */
export function roundForVital(value: number, key: VitalKey): number {
  const decimals = VITAL_TOLERANCES[key].decimals;
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
}

/** Format a value for display in the modal preview. */
export function formatVital(value: number | null, key: VitalKey): string {
  if (value == null) return '—';
  const cfg = VITAL_TOLERANCES[key];
  return `${value.toFixed(cfg.decimals)} ${cfg.unit}`;
}
