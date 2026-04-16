import { TEWSInput, TEWSScoring, TriageCategory, AVPU, Mobility } from '@/types';

/**
 * Calculate TEWS score based on Rwanda mSAT protocol
 * Returns individual scores and total score
 */
export function calculateTEWS(input: TEWSInput, isPediatric: boolean = false, age?: number): TEWSScoring {
  const scoring: TEWSScoring = {
    mobilityScore: getMobilityScore(input.mobility),
    temperatureScore: getTemperatureScore(input.temperature),
    respiratoryRateScore: getRespiratoryRateScore(input.respiratoryRate, isPediatric, age),
    avpuScore: getAVPUScore(input.avpu),
    pulseScore: getPulseScore(input.pulse, isPediatric, age),
    traumaScore: input.trauma ? 1 : 0,
    systolicBPScore: getSystolicBPScore(input.systolicBP, isPediatric, age),
    totalScore: 0,
  };

  scoring.totalScore = 
    scoring.mobilityScore +
    scoring.temperatureScore +
    scoring.respiratoryRateScore +
    scoring.avpuScore +
    scoring.pulseScore +
    scoring.traumaScore +
    scoring.systolicBPScore;

  return scoring;
}

/**
 * Determine triage category based on TEWS score and SpO2
 * Follows mSAT protocol rules
 */
export function determineCategory(tewsScore: number, spo2: number, isPediatric: boolean = false): TriageCategory {
  // Critical rule: SpO2 < 92% → RED (immediate)
  if (spo2 < 92) {
    return 'RED';
  }

  // Pediatric adjustment: SpO2 < 94% in infants → ORANGE minimum
  if (isPediatric && spo2 < 94) {
    return tewsScore >= 7 ? 'RED' : 'ORANGE';
  }

  // TEWS-based categorization
  if (tewsScore >= 7) {
    return 'RED'; // Immediate
  } else if (tewsScore >= 5) {
    return 'ORANGE'; // Very urgent
  } else if (tewsScore >= 3) {
    return 'YELLOW'; // Urgent
  } else {
    return 'GREEN'; // Standard
  }
}

// Individual scoring functions

function getMobilityScore(mobility: Mobility): number {
  switch (mobility) {
    case 'AMBULATORY':
      return 0;
    case 'WHEELCHAIR':
      return 1;
    case 'STRETCHER':
      return 2;
    default:
      return 0;
  }
}

function getTemperatureScore(temp: number): number {
  if (temp < 35) return 2;
  if (temp >= 35 && temp <= 38.4) return 0;
  if (temp >= 38.5) return 1;
  return 0;
}

function getRespiratoryRateScore(rr: number, isPediatric: boolean, age?: number): number {
  if (isPediatric && age !== undefined) {
    // Pediatric RR thresholds
    if (age < 1) {
      // Infant
      if (rr < 30 || rr > 60) return 2;
      if (rr >= 50 || rr <= 35) return 1;
      return 0;
    } else if (age < 5) {
      // Toddler
      if (rr < 20 || rr > 40) return 2;
      if (rr >= 35 || rr <= 22) return 1;
      return 0;
    } else if (age < 12) {
      // Child
      if (rr < 18 || rr > 30) return 2;
      if (rr >= 26 || rr <= 20) return 1;
      return 0;
    }
  }

  // Adult thresholds
  if (rr < 10 || rr > 30) return 2;
  if (rr >= 25 || rr <= 12) return 1;
  return 0;
}

function getAVPUScore(avpu: AVPU): number {
  switch (avpu) {
    case 'A':
      return 0;
    case 'V':
      return 1;
    case 'P':
      return 2;
    case 'U':
      return 2;
    default:
      return 0;
  }
}

function getPulseScore(pulse: number, isPediatric: boolean, age?: number): number {
  if (isPediatric && age !== undefined) {
    // Pediatric HR thresholds
    if (age < 1) {
      if (pulse < 100 || pulse > 160) return 2;
      if (pulse >= 150 || pulse <= 110) return 1;
      return 0;
    } else if (age < 5) {
      if (pulse < 90 || pulse > 140) return 2;
      if (pulse >= 130 || pulse <= 95) return 1;
      return 0;
    } else if (age < 12) {
      if (pulse < 70 || pulse > 120) return 2;
      if (pulse >= 110 || pulse <= 75) return 1;
      return 0;
    }
  }

  // Adult thresholds
  if (pulse < 50 || pulse > 120) return 2;
  if (pulse >= 110 || pulse <= 55) return 1;
  return 0;
}

function getSystolicBPScore(sbp: number, isPediatric: boolean, age?: number): number {
  if (isPediatric && age !== undefined) {
    // Pediatric BP thresholds (simplified)
    const minBP = 70 + (age * 2);
    if (sbp < minBP) return 2;
    if (sbp < minBP + 10) return 1;
    return 0;
  }

  // Adult thresholds
  if (sbp < 90) return 2;
  if (sbp >= 90 && sbp <= 100) return 1;
  return 0;
}

/**
 * Get category time limits in minutes
 */
export function getCategoryTimeLimit(category: TriageCategory): number {
  switch (category) {
    case 'RED':
      return 0; // Immediate
    case 'ORANGE':
      return 15; // 15 minutes
    case 'YELLOW':
      return 60; // 1 hour
    case 'GREEN':
      return 120; // 2 hours
    case 'BLUE':
      return 240; // 4 hours
    default:
      return 120;
  }
}

/**
 * Get category color for UI
 */
export function getCategoryColor(category: TriageCategory): string {
  switch (category) {
    case 'RED':
      return '#ef4444';
    case 'ORANGE':
      return '#f97316';
    case 'YELLOW':
      return '#eab308';
    case 'GREEN':
      return '#22c55e';
    case 'BLUE':
      return '#3b82f6';
    default:
      return '#6b7280';
  }
}

/**
 * Get risk level based on TEWS score
 */
export function getRiskLevel(tewsScore: number): 'Low' | 'Moderate' | 'High' | 'Critical' {
  if (tewsScore >= 7) return 'Critical';
  if (tewsScore >= 5) return 'High';
  if (tewsScore >= 3) return 'Moderate';
  return 'Low';
}
