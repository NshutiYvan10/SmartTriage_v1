import { PediatricThresholds } from '@/types';

/**
 * Determine pediatric age group
 */
export function getPediatricAgeGroup(age: number): 'INFANT' | 'TODDLER' | 'CHILD' | 'ADOLESCENT' {
  if (age < 1) return 'INFANT';
  if (age < 5) return 'TODDLER';
  if (age < 12) return 'CHILD';
  return 'ADOLESCENT';
}

/**
 * Get age-adjusted vital sign thresholds
 */
export function getPediatricThresholds(age: number): PediatricThresholds {
  const ageGroup = getPediatricAgeGroup(age);

  switch (ageGroup) {
    case 'INFANT':
      return {
        ageGroup: 'INFANT',
        heartRate: { min: 100, max: 160 },
        respiratoryRate: { min: 30, max: 60 },
        systolicBP: { min: 70 },
        spo2Threshold: 94,
      };
    case 'TODDLER':
      return {
        ageGroup: 'TODDLER',
        heartRate: { min: 90, max: 140 },
        respiratoryRate: { min: 20, max: 40 },
        systolicBP: { min: 75 },
        spo2Threshold: 94,
      };
    case 'CHILD':
      return {
        ageGroup: 'CHILD',
        heartRate: { min: 70, max: 120 },
        respiratoryRate: { min: 18, max: 30 },
        systolicBP: { min: 80 },
        spo2Threshold: 93,
      };
    case 'ADOLESCENT':
      return {
        ageGroup: 'ADOLESCENT',
        heartRate: { min: 60, max: 100 },
        respiratoryRate: { min: 12, max: 24 },
        systolicBP: { min: 90 },
        spo2Threshold: 92,
      };
  }
}

/**
 * Check if vital is within normal range for age
 */
export function isVitalNormal(
  vitalType: 'heartRate' | 'respiratoryRate' | 'systolicBP' | 'spo2',
  value: number,
  age: number
): boolean {
  const thresholds = getPediatricThresholds(age);

  switch (vitalType) {
    case 'heartRate':
      return value >= thresholds.heartRate.min && value <= thresholds.heartRate.max;
    case 'respiratoryRate':
      return value >= thresholds.respiratoryRate.min && value <= thresholds.respiratoryRate.max;
    case 'systolicBP':
      return value >= thresholds.systolicBP.min;
    case 'spo2':
      return value >= thresholds.spo2Threshold;
    default:
      return true;
  }
}

/**
 * Calculate ideal weight for pediatric patient (simplified formula)
 */
export function calculateIdealWeight(age: number): number {
  if (age < 1) {
    // Infants: approximate 6-10 kg
    return 3 + (age * 7);
  } else if (age < 10) {
    // Children: (age × 2) + 8
    return (age * 2) + 8;
  } else {
    // Adolescents: (age × 3) + 10
    return (age * 3) + 10;
  }
}

/**
 * Get pediatric-specific warning message
 */
export function getPediatricWarning(age: number, vitalType: string, value: number): string | null {
  const thresholds = getPediatricThresholds(age);
  const ageGroup = thresholds.ageGroup;

  switch (vitalType) {
    case 'heartRate':
      if (value < thresholds.heartRate.min || value > thresholds.heartRate.max) {
        return `Heart rate abnormal for ${ageGroup.toLowerCase()} (normal: ${thresholds.heartRate.min}-${thresholds.heartRate.max} bpm)`;
      }
      break;
    case 'respiratoryRate':
      if (value < thresholds.respiratoryRate.min || value > thresholds.respiratoryRate.max) {
        return `Respiratory rate abnormal for ${ageGroup.toLowerCase()} (normal: ${thresholds.respiratoryRate.min}-${thresholds.respiratoryRate.max} breaths/min)`;
      }
      break;
    case 'spo2':
      if (value < thresholds.spo2Threshold) {
        return `SpO₂ below threshold for ${ageGroup.toLowerCase()} (minimum: ${thresholds.spo2Threshold}%)`;
      }
      break;
  }

  return null;
}
