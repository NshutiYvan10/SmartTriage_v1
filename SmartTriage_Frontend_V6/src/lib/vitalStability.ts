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

/**
 * Stability classifications.
 *
 * - `stable`       — ≥ MIN_AGREEING_SAMPLES readings agree within ±tolerance of the
 *                    rolling-window median. Value is captured as median of the
 *                    agreeing subset (robust against single-reading spikes).
 * - `stabilizing`  — readings are arriving but haven't agreed enough yet.
 * - `unstable`     — signal quality reported as poor by the device (probe motion,
 *                    bad lead contact, etc.). Distinct from no_signal because the
 *                    probe IS connected, but its data isn't trustworthy yet.
 * - `no_signal`    — the device has never reported this vital in the current
 *                    window. Probe not attached / measurement not started.
 *                    Treated as "skipped" by the "all-stable" gate so an
 *                    un-cuffed BP doesn't block the nurse forever.
 */
export type StabilityState = 'stable' | 'stabilizing' | 'unstable' | 'no_signal';

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
 *
 * ### Why median-anchored agreement (instead of newest-first greedy cluster)
 *
 * The previous algorithm walked newest→older and stopped at the first
 * out-of-tolerance reading. A single noisy reading at the head of the
 * window would discard the 3-4 agreeing readings behind it and flip the
 * vital from "stable" → "stabilizing" instantly. That's the flicker the
 * Triage Nurse experiences.
 *
 * This algorithm uses the **rolling-window median** as the anchor:
 *   1. Take the last STABILITY_WINDOW_SIZE readings.
 *   2. Compute their median (robust against outliers — a single spike
 *      doesn't move it).
 *   3. Count how many readings are within ±tolerance of the median.
 *   4. If ≥ MIN_AGREEING_SAMPLES agree → stable, value = median of those.
 *
 * Hysteresis falls out naturally: a single spike enters the window, the
 * median barely moves, count-in-tolerance stays ≥ MIN, vital stays
 * stable. As the patient's true value drifts, the median follows the
 * *majority* of recent readings, so the vital re-stabilizes at the new
 * value without flickering through "stabilizing".
 */
export function evaluateStability(
  readings: ReadonlyArray<VitalStreamResponse>,
  key: VitalKey,
): VitalStabilityResult {
  const cfg = VITAL_TOLERANCES[key];

  // Trim to the rolling window (parent already does this, but defensive).
  const window = readings.length > STABILITY_WINDOW_SIZE
    ? readings.slice(-STABILITY_WINDOW_SIZE)
    : readings;

  // Partition into (a) "vital was reported", (b) "vital reported but
  // device flagged poor signal quality", (c) "no entry at all".
  const withVital = window.filter(r => r[key] != null);
  const validAndAcceptable = withVital.filter(r => ACCEPTABLE_QUALITY.has(r.signalQuality));

  const latestRaw = withVital.length > 0
    ? (withVital[withVital.length - 1][key] as number)
    : null;

  // No_signal — the device hasn't surfaced this vital at all in the
  // window. Probe disconnected / measurement not started. NOT the same
  // as "signal quality poor" (which means probe IS connected). The
  // distinction matters: no_signal is "skipped" by the all-stable gate
  // so an un-cuffed BP doesn't block the nurse forever.
  if (withVital.length === 0) {
    return {
      key,
      state: 'no_signal',
      value: null,
      latest: null,
      agreeingCount: 0,
      reason: 'Probe not attached / no reading yet',
    };
  }

  // Vital is reported but device says signal is poor — probe is on the
  // patient but its data shouldn't be trusted. Show why and keep waiting.
  if (validAndAcceptable.length === 0) {
    return {
      key,
      state: 'unstable',
      value: null,
      latest: latestRaw,
      agreeingCount: 0,
      reason: 'Signal quality poor — check probe / lead placement',
    };
  }

  // Median-anchored agreement (the heart of the new algorithm).
  const values = validAndAcceptable.map(r => r[key] as number);
  const anchor = median(values);
  const agreeing = values.filter(v => Math.abs(v - anchor) <= cfg.tolerance);

  if (agreeing.length >= MIN_AGREEING_SAMPLES) {
    return {
      key,
      state: 'stable',
      value: median(agreeing),
      latest: latestRaw,
      agreeingCount: agreeing.length,
      reason: null,
    };
  }

  return {
    key,
    state: 'stabilizing',
    value: null,
    latest: latestRaw,
    agreeingCount: agreeing.length,
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
