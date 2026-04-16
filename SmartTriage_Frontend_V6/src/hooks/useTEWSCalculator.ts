import { useMemo } from 'react';
import { TEWSInput, TEWSScoring, TriageCategory, TEWSTrend } from '@/types';
import { calculateTEWS, determineCategory, getRiskLevel } from '@/utils/tewsCalculator';
import {
  validateTEWSInputs,
  ValidationResult,
  hasImpossibleValues,
  hasCriticalValues,
  getAbnormalValidations,
} from '@/utils/vitalValidation';
import { useTEWSHistoryStore } from '@/store/tewsHistoryStore';

interface UseTEWSCalculatorProps {
  input: TEWSInput;
  isPediatric?: boolean;
  age?: number;
  /** Patient ID for trend tracking (optional — if omitted, no history is tracked) */
  patientId?: string;
}

interface UseTEWSCalculatorReturn {
  scoring: TEWSScoring;
  category: TriageCategory;
  riskLevel: 'Low' | 'Moderate' | 'High' | 'Critical';
  isValid: boolean;
  /** Per-field validation results */
  validationResults: ValidationResult[];
  /** Only the abnormal (warning/critical/impossible) validations */
  abnormalValidations: ValidationResult[];
  /** True if any value is physiologically impossible (likely data entry error) */
  hasImpossible: boolean;
  /** True if any value is in the critical range */
  hasCritical: boolean;
  /** Trend data from TEWS history (null if no patientId or < 2 entries) */
  trend: TEWSTrend | null;
  /** Number of previous TEWS calculations for this patient */
  historyCount: number;
}

/**
 * Hook for real-time TEWS calculation with validation and trend tracking.
 * Automatically recalculates when inputs change.
 *
 * Enhanced in Module 3 with:
 *   - Physiologic range validation per vital sign
 *   - Age-aware pediatric range checking
 *   - TEWS score trend from history store
 */
export function useTEWSCalculator({
  input,
  isPediatric = false,
  age,
  patientId,
}: UseTEWSCalculatorProps): UseTEWSCalculatorReturn {
  // Access trend from store (reactive)
  const trend = useTEWSHistoryStore((s) =>
    patientId ? s.getTrend(patientId) : null,
  );
  const historyCount = useTEWSHistoryStore((s) =>
    patientId ? s.getHistory(patientId).length : 0,
  );

  const result = useMemo(() => {
    // ── 1. Validate inputs against physiologic ranges ──────────
    const validationResults = validateTEWSInputs(
      {
        temperature: input.temperature || null,
        respiratoryRate: input.respiratoryRate || null,
        pulse: input.pulse || null,
        systolicBP: input.systolicBP || null,
        spo2: input.spo2 || null,
      },
      isPediatric,
      age,
    );

    const abnormalValidations = getAbnormalValidations(validationResults);
    const hasImpossible = hasImpossibleValues(validationResults);
    const hasCritical = hasCriticalValues(validationResults);

    // ── 2. Check basic validity (all required fields > 0) ──────
    const isValid =
      input.temperature > 0 &&
      input.respiratoryRate > 0 &&
      input.pulse > 0 &&
      input.systolicBP > 0 &&
      input.spo2 > 0;

    if (!isValid) {
      return {
        scoring: {
          mobilityScore: 0,
          temperatureScore: 0,
          respiratoryRateScore: 0,
          avpuScore: 0,
          pulseScore: 0,
          traumaScore: 0,
          systolicBPScore: 0,
          totalScore: 0,
        },
        category: 'GREEN' as TriageCategory,
        riskLevel: 'Low' as const,
        isValid: false,
        validationResults,
        abnormalValidations,
        hasImpossible,
        hasCritical,
      };
    }

    // ── 3. Calculate TEWS score ────────────────────────────────
    const scoring = calculateTEWS(input, isPediatric, age);

    // ── 4. Determine category ──────────────────────────────────
    const category = determineCategory(scoring.totalScore, input.spo2, isPediatric);

    // ── 5. Get risk level ──────────────────────────────────────
    const riskLevel = getRiskLevel(scoring.totalScore);

    return {
      scoring,
      category,
      riskLevel,
      isValid: true,
      validationResults,
      abnormalValidations,
      hasImpossible,
      hasCritical,
    };
  }, [input, isPediatric, age]);

  return {
    ...result,
    trend,
    historyCount,
  };
}
