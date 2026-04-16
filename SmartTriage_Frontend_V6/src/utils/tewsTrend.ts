/**
 * TEWS Trend Analysis Utility
 * King Faisal Hospital, Kigali — Module 3: TEWS Calculator Enhancement
 *
 * Provides analytics functions for TEWS score history:
 *   - Score decomposition (which parameters changed)
 *   - Trend visualization data
 *   - Parameter-level change detection
 *   - Clinical escalation rule evaluation
 */

import { TEWSHistoryEntry, TEWSScoring, TriageCategory } from '@/types';

// ── Types ──────────────────────────────────────────────

/** Change in a single TEWS parameter between two calculations */
export interface ParameterDelta {
  parameter: string;
  label: string;
  previousScore: number;
  currentScore: number;
  delta: number;
  /** Direction: improved (↓), worsened (↑), or unchanged */
  direction: 'improved' | 'worsened' | 'unchanged';
}

/** Chart-ready data point for trend graphs */
export interface TrendDataPoint {
  timestamp: Date;
  /** ISO string for chart axis labels */
  timeLabel: string;
  totalScore: number;
  category: TriageCategory;
  /** Individual parameter scores */
  mobility: number;
  temperature: number;
  respiratory: number;
  avpu: number;
  pulse: number;
  trauma: number;
  systolicBP: number;
}

/** Result of escalation rule evaluation */
export interface EscalationCheck {
  shouldEscalate: boolean;
  currentCategory: TriageCategory;
  recommendedCategory: TriageCategory | null;
  reasons: string[];
  severity: 'none' | 'advisory' | 'warning' | 'critical';
}

// ── Score decomposition ────────────────────────────────

const PARAMETER_LABELS: Record<keyof TEWSScoring, string> = {
  mobilityScore: 'Mobility',
  temperatureScore: 'Temperature',
  respiratoryRateScore: 'Respiratory Rate',
  avpuScore: 'AVPU',
  pulseScore: 'Pulse / Heart Rate',
  traumaScore: 'Trauma',
  systolicBPScore: 'Systolic BP',
  totalScore: 'Total',
};

/**
 * Compare two TEWS scorings and return per-parameter deltas.
 * Excludes totalScore.
 */
export function getParameterDeltas(
  previous: TEWSScoring,
  current: TEWSScoring,
): ParameterDelta[] {
  const params: Array<keyof TEWSScoring> = [
    'mobilityScore',
    'temperatureScore',
    'respiratoryRateScore',
    'avpuScore',
    'pulseScore',
    'traumaScore',
    'systolicBPScore',
  ];

  return params.map((param) => {
    const prev = previous[param];
    const curr = current[param];
    const delta = curr - prev;
    let direction: ParameterDelta['direction'];
    if (delta > 0) direction = 'worsened';
    else if (delta < 0) direction = 'improved';
    else direction = 'unchanged';

    return {
      parameter: param,
      label: PARAMETER_LABELS[param],
      previousScore: prev,
      currentScore: curr,
      delta,
      direction,
    };
  });
}

/**
 * Get the parameters that changed between two calculations
 */
export function getChangedParameters(
  previous: TEWSScoring,
  current: TEWSScoring,
): ParameterDelta[] {
  return getParameterDeltas(previous, current).filter((d) => d.delta !== 0);
}

/**
 * Identify which parameter contributed most to score change
 */
export function getTopContributor(
  previous: TEWSScoring,
  current: TEWSScoring,
): ParameterDelta | null {
  const changed = getChangedParameters(previous, current);
  if (changed.length === 0) return null;

  return changed.reduce((max, d) =>
    Math.abs(d.delta) > Math.abs(max.delta) ? d : max
  );
}

// ── Trend data for charts ──────────────────────────────

/**
 * Convert TEWS history entries to chart-ready data points
 */
export function toTrendDataPoints(history: TEWSHistoryEntry[]): TrendDataPoint[] {
  return history.map((entry) => ({
    timestamp: entry.timestamp,
    timeLabel: formatTimeLabel(entry.timestamp),
    totalScore: entry.scoring.totalScore,
    category: entry.category,
    mobility: entry.scoring.mobilityScore,
    temperature: entry.scoring.temperatureScore,
    respiratory: entry.scoring.respiratoryRateScore,
    avpu: entry.scoring.avpuScore,
    pulse: entry.scoring.pulseScore,
    trauma: entry.scoring.traumaScore,
    systolicBP: entry.scoring.systolicBPScore,
  }));
}

function formatTimeLabel(date: Date): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/**
 * Get the score range (min/max) across all history for chart Y-axis scaling
 */
export function getScoreRange(history: TEWSHistoryEntry[]): { min: number; max: number } {
  if (history.length === 0) return { min: 0, max: 14 };
  const scores = history.map((e) => e.scoring.totalScore);
  return {
    min: Math.max(0, Math.min(...scores) - 1),
    max: Math.min(14, Math.max(...scores) + 2),
  };
}

// ── Escalation rule evaluation ─────────────────────────

const CATEGORY_ORDER: TriageCategory[] = ['GREEN', 'BLUE', 'YELLOW', 'ORANGE', 'RED'];

function nextCategory(cat: TriageCategory): TriageCategory | null {
  const idx = CATEGORY_ORDER.indexOf(cat);
  return idx < CATEGORY_ORDER.length - 1 ? CATEGORY_ORDER[idx + 1] : null;
}

/**
 * Evaluate whether the TEWS trend history warrants a category escalation.
 * Rules:
 *   1. Rapid increase (≥3 points in single calc) → critical
 *   2. Crossed RED threshold (score ≥7 from <7) → critical
 *   3. Crossed ORANGE threshold (score ≥5 from <5) → warning
 *   4. Continuous worsening (≥3 consecutive increases) → warning
 *   5. Score increased but within same band → advisory
 */
export function evaluateEscalation(history: TEWSHistoryEntry[]): EscalationCheck {
  const noEscalation: EscalationCheck = {
    shouldEscalate: false,
    currentCategory: history.length > 0 ? history[history.length - 1].category : 'GREEN',
    recommendedCategory: null,
    reasons: [],
    severity: 'none',
  };

  if (history.length < 2) return noEscalation;

  const current = history[history.length - 1];
  const previous = history[history.length - 2];
  const currentScore = current.scoring.totalScore;
  const previousScore = previous.scoring.totalScore;
  const delta = currentScore - previousScore;

  const reasons: string[] = [];
  let severity: EscalationCheck['severity'] = 'none';
  let recommended: TriageCategory | null = null;

  // Rule 1: Rapid increase
  if (delta >= 3) {
    reasons.push(`TEWS score rapidly increased by ${delta} points (${previousScore} \u2192 ${currentScore})`);
    severity = 'critical';
    recommended = nextCategory(current.category) ?? current.category;
  }

  // Rule 2: Crossed RED threshold
  if (currentScore >= 7 && previousScore < 7) {
    reasons.push(`TEWS score crossed critical RED threshold (\u22657)`);
    severity = 'critical';
    recommended = 'RED';
  }

  // Rule 3: Crossed ORANGE threshold
  if (currentScore >= 5 && previousScore < 5 && severity !== 'critical') {
    reasons.push(`TEWS score crossed ORANGE threshold (\u22655)`);
    severity = 'warning';
    recommended = recommended ?? 'ORANGE';
  }

  // Rule 4: Consecutive worsening
  if (history.length >= 3) {
    let consecutiveWorsenings = 0;
    for (let i = history.length - 1; i >= 1; i--) {
      if (history[i].scoring.totalScore > history[i - 1].scoring.totalScore) {
        consecutiveWorsenings++;
      } else {
        break;
      }
    }
    if (consecutiveWorsenings >= 3) {
      reasons.push(`TEWS score has increased for ${consecutiveWorsenings} consecutive calculations`);
      if (severity === 'none') severity = 'warning';
      recommended = recommended ?? nextCategory(current.category);
    }
  }

  // Rule 5: Any increase
  if (delta > 0 && reasons.length === 0) {
    reasons.push(`TEWS score increased by ${delta} point${delta > 1 ? 's' : ''}`);
    severity = 'advisory';
  }

  return {
    shouldEscalate: severity === 'critical' || severity === 'warning',
    currentCategory: current.category,
    recommendedCategory: recommended,
    reasons,
    severity,
  };
}

// ── Time-based analysis ────────────────────────────────

/**
 * Calculate the average rate of score change per hour over the entire history
 */
export function getAverageRatePerHour(history: TEWSHistoryEntry[]): number | null {
  if (history.length < 2) return null;

  const first = history[0];
  const last = history[history.length - 1];
  const timeDiffHours =
    (last.timestamp.getTime() - first.timestamp.getTime()) / (1000 * 60 * 60);

  if (timeDiffHours === 0) return null;

  const scoreDiff = last.scoring.totalScore - first.scoring.totalScore;
  return Math.round((scoreDiff / timeDiffHours) * 100) / 100;
}

/**
 * Get the time since the last TEWS calculation in minutes
 */
export function getMinutesSinceLastCalculation(history: TEWSHistoryEntry[]): number | null {
  if (history.length === 0) return null;
  const last = history[history.length - 1];
  return Math.round((Date.now() - last.timestamp.getTime()) / 60000);
}

/**
 * Check if re-triage is overdue based on category time limits
 */
export function isRetriageOverdue(
  history: TEWSHistoryEntry[],
  categoryTimeLimitMinutes: number,
): boolean {
  const minutesSince = getMinutesSinceLastCalculation(history);
  if (minutesSince === null) return false;
  return minutesSince > categoryTimeLimitMinutes;
}

// ── Score breakdown text ───────────────────────────────

/**
 * Generate a human-readable breakdown of a TEWS score
 */
export function getScoreBreakdownText(scoring: TEWSScoring): string {
  const parts: string[] = [];
  if (scoring.mobilityScore > 0) parts.push(`Mobility: ${scoring.mobilityScore}`);
  if (scoring.temperatureScore > 0) parts.push(`Temp: ${scoring.temperatureScore}`);
  if (scoring.respiratoryRateScore > 0) parts.push(`RR: ${scoring.respiratoryRateScore}`);
  if (scoring.avpuScore > 0) parts.push(`AVPU: ${scoring.avpuScore}`);
  if (scoring.pulseScore > 0) parts.push(`Pulse: ${scoring.pulseScore}`);
  if (scoring.traumaScore > 0) parts.push(`Trauma: ${scoring.traumaScore}`);
  if (scoring.systolicBPScore > 0) parts.push(`SBP: ${scoring.systolicBPScore}`);

  if (parts.length === 0) return `Total: ${scoring.totalScore} (all parameters normal)`;
  return `Total: ${scoring.totalScore} = ${parts.join(' + ')}`;
}
