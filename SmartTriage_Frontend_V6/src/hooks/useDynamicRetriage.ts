import { useEffect, useRef, useCallback } from 'react';
import { useVitalStore } from '@/store/vitalStore';
import { useAlertStore } from '@/store/alertStore';
import { usePatientStore } from '@/store/patientStore';
import { useAuditStore } from '@/store/auditStore';
import { TriageCategory, VitalReading } from '@/types';

// ── Threshold Definitions ──────────────────────────────
// Adult normal ranges (used for threshold breach detection)
const ADULT_THRESHOLDS = {
  heartRate:       { critLow: 40,  low: 50,  high: 120, critHigh: 150 },
  respiratoryRate: { critLow: 6,   low: 10,  high: 25,  critHigh: 35  },
  spo2:            { critLow: 85,  low: 90,  high: 100, critHigh: 100 },
  systolicBP:      { critLow: 70,  low: 90,  high: 160, critHigh: 200 },
  temperature:     { critLow: 34,  low: 35.5, high: 38.5, critHigh: 40 },
  glucose:         { critLow: 40,  low: 70,  high: 180, critHigh: 300 },
  ecg:             { critLow: -3,  low: -1,  high: 1,   critHigh: 3   },
};

// Pediatric normal ranges (age-adjusted thresholds)
const PEDIATRIC_THRESHOLDS = {
  heartRate:       { critLow: 60,  low: 80,  high: 160, critHigh: 200 },
  respiratoryRate: { critLow: 12,  low: 20,  high: 40,  critHigh: 60  },
  spo2:            { critLow: 88,  low: 92,  high: 100, critHigh: 100 },
  systolicBP:      { critLow: 60,  low: 75,  high: 130, critHigh: 160 },
  temperature:     { critLow: 35,  low: 36,  high: 38,  critHigh: 39.5 },
  glucose:         { critLow: 45,  low: 60,  high: 200, critHigh: 350 },
  ecg:             { critLow: -3,  low: -1,  high: 1,   critHigh: 3   },
};

// Trend detection thresholds (how much change triggers alert)
const TREND_THRESHOLDS = {
  heartRate:       { deterioration: 20, improvement: -15, unit: 'bpm' },
  respiratoryRate: { deterioration: 5,  improvement: -4,  unit: 'breaths/min' },
  spo2:            { deterioration: -2, improvement: 3,   unit: '%' },
  systolicBP:      { deterioration: -20, improvement: 15, unit: 'mmHg' },
  temperature:     { deterioration: 1,  improvement: -0.8, unit: '°C' },
  glucose:         { deterioration: 50, improvement: -40, unit: 'mg/dL' },
  ecg:             { deterioration: 1.5, improvement: -1, unit: 'mV' },
};

type VitalKey = keyof typeof ADULT_THRESHOLDS;

interface RetriageResult {
  /** Factors recommending escalation (worser category) */
  deteriorationFactors: string[];
  /** Factors recommending de-escalation (better category) */
  improvementFactors: string[];
  /** Threshold breaches (immediate critical values) */
  thresholdBreaches: string[];
  /** Confidence 0-1 in the recommendation */
  confidence: number;
  /** Recommended category change direction */
  direction: 'ESCALATE' | 'DE_ESCALATE' | 'STABLE';
  /** Recommended new category */
  recommendedCategory: TriageCategory | null;
  /** Composite risk score 0-100 */
  compositeRiskScore: number;
}

/**
 * Analyze a single vital's trend for deterioration/improvement
 */
function analyzeTrend(
  history: VitalReading[],
  vitalKey: VitalKey,
  label: string,
): { deterioration: string | null; improvement: string | null } {
  if (history.length < 3) return { deterioration: null, improvement: null };

  const recent = history.slice(-3);
  const change = recent[recent.length - 1].value - recent[0].value;
  const thresholds = TREND_THRESHOLDS[vitalKey];

  // For SpO2 and systolicBP, deterioration is DECREASE (negative change)
  const isInverted = vitalKey === 'spo2' || vitalKey === 'systolicBP';

  if (isInverted) {
    // Deterioration = value dropping
    if (change < 0 && Math.abs(change) >= Math.abs(thresholds.deterioration)) {
      return {
        deterioration: `${label} decreased by ${Math.abs(change).toFixed(1)} ${thresholds.unit}`,
        improvement: null,
      };
    }
    // Improvement = value rising
    if (change > 0 && change >= Math.abs(thresholds.improvement)) {
      return {
        deterioration: null,
        improvement: `${label} improved by +${change.toFixed(1)} ${thresholds.unit}`,
      };
    }
  } else {
    // Deterioration = value rising (HR, RR, temp, glucose, ECG)
    if (change > 0 && change >= thresholds.deterioration) {
      return {
        deterioration: `${label} increased by +${change.toFixed(1)} ${thresholds.unit}`,
        improvement: null,
      };
    }
    // Improvement = value dropping
    if (change < 0 && Math.abs(change) >= Math.abs(thresholds.improvement)) {
      return {
        deterioration: null,
        improvement: `${label} decreased by ${Math.abs(change).toFixed(1)} ${thresholds.unit}`,
      };
    }
  }

  return { deterioration: null, improvement: null };
}

/**
 * Check if current vital value breaches critical/warning thresholds
 */
function checkThresholdBreach(
  history: VitalReading[],
  vitalKey: VitalKey,
  label: string,
  isPediatric: boolean,
): { breach: string | null; severity: 'CRITICAL' | 'HIGH' } {
  if (history.length === 0) return { breach: null, severity: 'HIGH' };

  const current = history[history.length - 1].value;
  const thresholds = isPediatric ? PEDIATRIC_THRESHOLDS[vitalKey] : ADULT_THRESHOLDS[vitalKey];

  if (current <= thresholds.critLow) {
    return { breach: `${label} critically low: ${current.toFixed(1)} ${TREND_THRESHOLDS[vitalKey].unit}`, severity: 'CRITICAL' };
  }
  if (current >= thresholds.critHigh && vitalKey !== 'spo2') {
    return { breach: `${label} critically high: ${current.toFixed(1)} ${TREND_THRESHOLDS[vitalKey].unit}`, severity: 'CRITICAL' };
  }
  if (current < thresholds.low) {
    return { breach: `${label} below normal: ${current.toFixed(1)} ${TREND_THRESHOLDS[vitalKey].unit}`, severity: 'HIGH' };
  }
  if (current > thresholds.high && vitalKey !== 'spo2') {
    return { breach: `${label} above normal: ${current.toFixed(1)} ${TREND_THRESHOLDS[vitalKey].unit}`, severity: 'HIGH' };
  }

  return { breach: null, severity: 'HIGH' };
}

/**
 * Compute a composite risk score (0-100) based on all vital deviations
 */
function computeCompositeRisk(
  vitalHistories: Record<string, VitalReading[]>,
  isPediatric: boolean,
): number {
  const weights: Record<VitalKey, number> = {
    heartRate: 0.15,
    respiratoryRate: 0.18,
    spo2: 0.22,
    systolicBP: 0.15,
    temperature: 0.1,
    glucose: 0.1,
    ecg: 0.1,
  };

  let totalScore = 0;
  let totalWeight = 0;

  for (const [key, weight] of Object.entries(weights)) {
    const vitalKey = key as VitalKey;
    const history = vitalHistories[vitalKey];
    if (!history || history.length === 0) continue;

    const current = history[history.length - 1].value;
    const thresholds = isPediatric ? PEDIATRIC_THRESHOLDS[vitalKey] : ADULT_THRESHOLDS[vitalKey];

    // Calculate how far from normal range (0 = normal, 1 = critical)
    const midLow = (thresholds.low + thresholds.critLow) / 2;
    const midHigh = (thresholds.high + thresholds.critHigh) / 2;
    const normalMid = (thresholds.low + thresholds.high) / 2;
    const normalRange = thresholds.high - thresholds.low;

    let deviation = 0;
    if (current < thresholds.critLow) {
      deviation = 1;
    } else if (current < thresholds.low) {
      deviation = 0.3 + 0.7 * ((thresholds.low - current) / (thresholds.low - thresholds.critLow));
    } else if (current > thresholds.critHigh && vitalKey !== 'spo2') {
      deviation = 1;
    } else if (current > thresholds.high && vitalKey !== 'spo2') {
      deviation = 0.3 + 0.7 * ((current - thresholds.high) / (thresholds.critHigh - thresholds.high));
    } else {
      // Within normal range
      deviation = 0;
    }

    totalScore += deviation * weight;
    totalWeight += weight;
  }

  return totalWeight > 0 ? Math.min(100, Math.round((totalScore / totalWeight) * 100)) : 0;
}

/**
 * Compute confidence based on data availability and consistency
 */
function computeConfidence(
  vitalHistories: Record<string, VitalReading[]>,
  deteriorationCount: number,
  improvementCount: number,
): number {
  // Base: how many vitals have sufficient history (≥3 readings)
  const vitalsWithData = Object.values(vitalHistories).filter(h => h.length >= 3).length;
  const dataConfidence = Math.min(1, vitalsWithData / 5); // max at 5+ vitals

  // Consistency: more factors pointing same direction = higher confidence
  const total = deteriorationCount + improvementCount;
  if (total === 0) return dataConfidence * 0.3; // no trend = low confidence

  const dominant = Math.max(deteriorationCount, improvementCount);
  const consistency = dominant / total;

  // Multi-vital correlation: if 3+ vitals agree, high confidence
  const multiVitalBonus = dominant >= 3 ? 0.2 : dominant >= 2 ? 0.1 : 0;

  return Math.min(1, dataConfidence * 0.4 + consistency * 0.4 + multiVitalBonus + 0.1);
}

/**
 * Escalate category by one level (GREEN→YELLOW→ORANGE→RED)
 */
export function escalateCategory(current: TriageCategory): TriageCategory {
  switch (current) {
    case 'GREEN': return 'YELLOW';
    case 'YELLOW': return 'ORANGE';
    case 'ORANGE': return 'RED';
    default: return current;
  }
}

/**
 * De-escalate category by one level (RED→ORANGE→YELLOW→GREEN)
 */
export function deEscalateCategory(current: TriageCategory): TriageCategory {
  switch (current) {
    case 'RED': return 'ORANGE';
    case 'ORANGE': return 'YELLOW';
    case 'YELLOW': return 'GREEN';
    default: return current;
  }
}

/**
 * Full re-triage analysis for a single patient
 */
export function analyzePatientRetriage(
  patientId: string,
  currentCategory: TriageCategory,
  isPediatric: boolean,
  getVitalHistory: (pid: string, vitalType: string) => VitalReading[],
): RetriageResult {
  const vitals: { key: VitalKey; label: string }[] = [
    { key: 'heartRate', label: 'Heart Rate' },
    { key: 'respiratoryRate', label: 'Respiratory Rate' },
    { key: 'spo2', label: 'SpO₂' },
    { key: 'systolicBP', label: 'Systolic BP' },
    { key: 'temperature', label: 'Temperature' },
    { key: 'glucose', label: 'Blood Glucose' },
    { key: 'ecg', label: 'ECG ST-Deviation' },
  ];

  const deteriorationFactors: string[] = [];
  const improvementFactors: string[] = [];
  const thresholdBreaches: string[] = [];
  let maxSeverity: 'CRITICAL' | 'HIGH' = 'HIGH';

  const vitalHistories: Record<string, VitalReading[]> = {};

  for (const { key, label } of vitals) {
    const history = getVitalHistory(patientId, key);
    vitalHistories[key] = history;

    // Trend analysis
    const trend = analyzeTrend(history, key, label);
    if (trend.deterioration) deteriorationFactors.push(trend.deterioration);
    if (trend.improvement) improvementFactors.push(trend.improvement);

    // Threshold breach
    const breach = checkThresholdBreach(history, key, label, isPediatric);
    if (breach.breach) {
      thresholdBreaches.push(breach.breach);
      if (breach.severity === 'CRITICAL') maxSeverity = 'CRITICAL';
    }
  }

  const compositeRiskScore = computeCompositeRisk(vitalHistories, isPediatric);
  const confidence = computeConfidence(
    vitalHistories,
    deteriorationFactors.length,
    improvementFactors.length,
  );

  // Determine direction
  let direction: 'ESCALATE' | 'DE_ESCALATE' | 'STABLE' = 'STABLE';
  let recommendedCategory: TriageCategory | null = null;

  // Escalation conditions:
  // - Any threshold breach OR 2+ deterioration factors OR composite risk > 60
  if (
    thresholdBreaches.length > 0 ||
    deteriorationFactors.length >= 2 ||
    compositeRiskScore > 60
  ) {
    const newCat = escalateCategory(currentCategory);
    if (newCat !== currentCategory) {
      direction = 'ESCALATE';
      recommendedCategory = newCat;
    }
  }
  // De-escalation conditions:
  // - 3+ improvement factors, 0 deterioration, 0 breaches, composite risk < 20
  else if (
    improvementFactors.length >= 3 &&
    deteriorationFactors.length === 0 &&
    thresholdBreaches.length === 0 &&
    compositeRiskScore < 20
  ) {
    const newCat = deEscalateCategory(currentCategory);
    if (newCat !== currentCategory) {
      direction = 'DE_ESCALATE';
      recommendedCategory = newCat;
    }
  }

  return {
    deteriorationFactors,
    improvementFactors,
    thresholdBreaches,
    confidence,
    direction,
    recommendedCategory,
    compositeRiskScore,
  };
}

/**
 * Hook for AI-powered dynamic re-triage
 * Monitors ALL vital trends (7 vitals) and triggers escalation/de-escalation alerts
 * Includes confidence scoring, composite risk, and pediatric-aware thresholds
 */
export function useDynamicRetriage(patientId: string) {
  const getVitalHistory = useVitalStore((state) => state.getVitalHistory);
  const addAlert = useAlertStore((state) => state.addAlert);
  const patient = usePatientStore((state) => state.getPatient(patientId));
  const addAuditEntry = useAuditStore((state) => state.addEntry);
  const lastCheckRef = useRef<number>(Date.now());

  useEffect(() => {
    const interval = setInterval(() => {
      if (!patient?.category) return;

      const now = Date.now();
      const timeSinceLastCheck = now - lastCheckRef.current;
      if (timeSinceLastCheck < 30000) return;
      lastCheckRef.current = now;

      const result = analyzePatientRetriage(
        patientId,
        patient.category,
        patient.isPediatric,
        getVitalHistory,
      );

      // Fire threshold breach alerts (immediate)
      if (result.thresholdBreaches.length > 0) {
        addAlert({
          patientId,
          type: 'THRESHOLD_BREACH',
          severity: result.compositeRiskScore > 70 ? 'CRITICAL' : 'HIGH',
          message: `${result.thresholdBreaches.length} vital(s) outside safe range — immediate review needed`,
          previousCategory: patient.category,
          recommendedCategory: result.recommendedCategory ?? patient.category,
          contributingFactors: result.thresholdBreaches,
        });
      }

      // Fire escalation alert
      if (result.direction === 'ESCALATE' && result.recommendedCategory) {
        addAlert({
          patientId,
          type: 'DETERIORATION',
          severity: result.compositeRiskScore > 70 ? 'CRITICAL' : 'HIGH',
          message: `AI recommends escalation to ${result.recommendedCategory} (confidence: ${Math.round(result.confidence * 100)}%, risk score: ${result.compositeRiskScore}/100)`,
          previousCategory: patient.category,
          recommendedCategory: result.recommendedCategory,
          contributingFactors: [
            ...result.deteriorationFactors,
            `Composite risk score: ${result.compositeRiskScore}/100`,
            `AI confidence: ${Math.round(result.confidence * 100)}%`,
          ],
        });
      }

      // Fire de-escalation alert (trend warning)
      if (result.direction === 'DE_ESCALATE' && result.recommendedCategory) {
        addAlert({
          patientId,
          type: 'TREND_WARNING',
          severity: 'MEDIUM',
          message: `Patient improving — AI suggests de-escalation to ${result.recommendedCategory} (confidence: ${Math.round(result.confidence * 100)}%)`,
          previousCategory: patient.category,
          recommendedCategory: result.recommendedCategory,
          contributingFactors: [
            ...result.improvementFactors,
            `Composite risk score: ${result.compositeRiskScore}/100`,
            `AI confidence: ${Math.round(result.confidence * 100)}%`,
          ],
        });
      }
    }, 30000);

    return () => clearInterval(interval);
  }, [patientId, patient, getVitalHistory, addAlert, addAuditEntry]);
}

/**
 * Hook for global batch monitoring — runs re-triage analysis
 * on ALL triaged patients simultaneously (for Dashboard / ConstantMonitoring)
 */
export function useGlobalRetriage() {
  const patients = usePatientStore((state) => state.patients);
  const getVitalHistory = useVitalStore((state) => state.getVitalHistory);
  const addAlert = useAlertStore((state) => state.addAlert);
  const lastCheckRef = useRef<number>(Date.now());

  const runGlobalCheck = useCallback(() => {
    const now = Date.now();
    const timeSinceLastCheck = now - lastCheckRef.current;
    if (timeSinceLastCheck < 30000) return;
    lastCheckRef.current = now;

    const triagedPatients = patients.filter(p => p.category && p.triageStatus === 'TRIAGED');

    for (const patient of triagedPatients) {
      const result = analyzePatientRetriage(
        patient.id,
        patient.category!,
        patient.isPediatric,
        getVitalHistory,
      );

      if (result.direction === 'ESCALATE' && result.recommendedCategory) {
        addAlert({
          patientId: patient.id,
          type: 'DETERIORATION',
          severity: result.compositeRiskScore > 70 ? 'CRITICAL' : 'HIGH',
          message: `[Batch Monitor] ${patient.fullName} — escalation to ${result.recommendedCategory} recommended (risk: ${result.compositeRiskScore}/100)`,
          previousCategory: patient.category,
          recommendedCategory: result.recommendedCategory,
          contributingFactors: result.deteriorationFactors,
        });
      }

      if (result.direction === 'DE_ESCALATE' && result.recommendedCategory) {
        addAlert({
          patientId: patient.id,
          type: 'TREND_WARNING',
          severity: 'LOW',
          message: `[Batch Monitor] ${patient.fullName} — improving, consider de-escalation to ${result.recommendedCategory}`,
          previousCategory: patient.category,
          recommendedCategory: result.recommendedCategory,
          contributingFactors: result.improvementFactors,
        });
      }
    }
  }, [patients, getVitalHistory, addAlert]);

  useEffect(() => {
    const interval = setInterval(runGlobalCheck, 30000);
    return () => clearInterval(interval);
  }, [runGlobalCheck]);

  return { runGlobalCheck };
}
