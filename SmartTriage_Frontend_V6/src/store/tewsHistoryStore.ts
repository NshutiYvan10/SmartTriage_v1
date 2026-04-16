import { create } from 'zustand';
import {
  TEWSHistoryEntry,
  TEWSTrend,
  TEWSHistorySummary,
  TEWSScoring,
  TriageCategory,
} from '@/types';

// ── Helpers ──────────────────────────────────────────

const CATEGORY_SEVERITY: Record<TriageCategory, number> = {
  GREEN: 0,
  BLUE: 1,
  YELLOW: 2,
  ORANGE: 3,
  RED: 4,
};

function worstCategory(a: TriageCategory, b: TriageCategory): TriageCategory {
  return CATEGORY_SEVERITY[a] >= CATEGORY_SEVERITY[b] ? a : b;
}

function generateId(): string {
  return `TH${Date.now()}${Math.random().toString(36).substr(2, 6).toUpperCase()}`;
}

// ── Store ──────────────────────────────────────────────

interface TEWSHistoryState {
  /** patientId → ordered list of TEWS history entries */
  historyByPatient: Map<string, TEWSHistoryEntry[]>;

  /** Record a new TEWS calculation for a patient */
  addEntry: (
    patientId: string,
    scoring: TEWSScoring,
    category: TriageCategory,
    categoryReason: string,
    options?: {
      spo2?: number;
      hadEmergencySigns?: boolean;
      discriminatorApplied?: boolean;
      performedBy?: string;
    },
  ) => TEWSHistoryEntry;

  /** Get full history for a patient (oldest first) */
  getHistory: (patientId: string) => TEWSHistoryEntry[];

  /** Get the last N entries */
  getRecentHistory: (patientId: string, count: number) => TEWSHistoryEntry[];

  /** Get the latest entry */
  getLatestEntry: (patientId: string) => TEWSHistoryEntry | undefined;

  /** Compute trend between last two entries */
  getTrend: (patientId: string) => TEWSTrend | null;

  /** Compute summary statistics */
  getSummary: (patientId: string) => TEWSHistorySummary;

  /** Clear history for a patient */
  clearHistory: (patientId: string) => void;
}

export const useTEWSHistoryStore = create<TEWSHistoryState>((set, get) => ({
  historyByPatient: new Map(),

  addEntry: (patientId, scoring, category, categoryReason, options = {}) => {
    const entry: TEWSHistoryEntry = {
      id: generateId(),
      timestamp: new Date(),
      scoring,
      category,
      categoryReason,
      spo2: options.spo2,
      hadEmergencySigns: options.hadEmergencySigns ?? false,
      discriminatorApplied: options.discriminatorApplied ?? false,
      performedBy: options.performedBy,
    };

    const { historyByPatient } = get();
    const newMap = new Map(historyByPatient);
    const existing = newMap.get(patientId) ?? [];
    newMap.set(patientId, [...existing, entry]);
    set({ historyByPatient: newMap });

    return entry;
  },

  getHistory: (patientId) => {
    return get().historyByPatient.get(patientId) ?? [];
  },

  getRecentHistory: (patientId, count) => {
    const history = get().historyByPatient.get(patientId) ?? [];
    return history.slice(-count);
  },

  getLatestEntry: (patientId) => {
    const history = get().historyByPatient.get(patientId) ?? [];
    return history.length > 0 ? history[history.length - 1] : undefined;
  },

  getTrend: (patientId) => {
    const history = get().historyByPatient.get(patientId) ?? [];
    if (history.length === 0) return null;

    const current = history[history.length - 1];
    const currentScore = current.scoring.totalScore;

    if (history.length === 1) {
      return {
        currentScore,
        previousScore: null,
        delta: 0,
        direction: 'STABLE',
        consecutiveCount: 1,
        ratePerHour: null,
        alertRequired: false,
      };
    }

    const previous = history[history.length - 2];
    const previousScore = previous.scoring.totalScore;
    const delta = currentScore - previousScore;

    // Determine direction
    let direction: TEWSTrend['direction'];
    if (delta > 0) direction = 'WORSENING';
    else if (delta < 0) direction = 'IMPROVING';
    else direction = 'STABLE';

    // Count consecutive same-direction changes
    let consecutiveCount = 1;
    for (let i = history.length - 2; i >= 1; i--) {
      const thisDelta = history[i].scoring.totalScore - history[i - 1].scoring.totalScore;
      const thisDir = thisDelta > 0 ? 'WORSENING' : thisDelta < 0 ? 'IMPROVING' : 'STABLE';
      if (thisDir === direction) {
        consecutiveCount++;
      } else {
        break;
      }
    }

    // Rate of change per hour
    const timeDiffMs = current.timestamp.getTime() - previous.timestamp.getTime();
    const timeDiffHours = timeDiffMs / (1000 * 60 * 60);
    const ratePerHour = timeDiffHours > 0 ? delta / timeDiffHours : null;

    // Determine if alert is needed
    let alertRequired = false;
    let alertMessage: string | undefined;
    let recommendation: string | undefined;

    if (direction === 'WORSENING') {
      if (delta >= 3) {
        alertRequired = true;
        alertMessage = `TEWS score increased by ${delta} points (${previousScore} \u2192 ${currentScore}). Rapid deterioration detected.`;
        recommendation = 'Immediate re-assessment and consider category escalation.';
      } else if (consecutiveCount >= 3) {
        alertRequired = true;
        alertMessage = `TEWS score trending upward for ${consecutiveCount} consecutive calculations. Persistent deterioration.`;
        recommendation = 'Review patient condition and consider upgrading triage category.';
      } else if (currentScore >= 7 && previousScore < 7) {
        alertRequired = true;
        alertMessage = `TEWS score crossed critical threshold (${previousScore} \u2192 ${currentScore}). Now in RED zone.`;
        recommendation = 'Immediate medical attention required. Escalate to RED category.';
      } else if (currentScore >= 5 && previousScore < 5) {
        alertRequired = true;
        alertMessage = `TEWS score crossed ORANGE threshold (${previousScore} \u2192 ${currentScore}).`;
        recommendation = 'Consider upgrading to ORANGE category. Review within 10 minutes.';
      }
    } else if (direction === 'IMPROVING' && consecutiveCount >= 2 && delta <= -2) {
      // Positive trend — inform but no alert
      recommendation = `Patient improving: TEWS decreased by ${Math.abs(delta)} points. Consider de-escalation.`;
    }

    return {
      currentScore,
      previousScore,
      delta,
      direction,
      consecutiveCount,
      ratePerHour,
      alertRequired,
      alertMessage,
      recommendation,
    };
  },

  getSummary: (patientId) => {
    const history = get().historyByPatient.get(patientId) ?? [];

    if (history.length === 0) {
      return {
        entryCount: 0,
        highestScore: 0,
        lowestScore: 0,
        averageScore: 0,
        currentCategory: 'GREEN' as TriageCategory,
        worstCategory: 'GREEN' as TriageCategory,
        firstCalculation: null,
        lastCalculation: null,
        totalDurationMinutes: 0,
        categoryChanges: 0,
      };
    }

    const scores = history.map((e) => e.scoring.totalScore);
    const categories = history.map((e) => e.category);

    let categoryChanges = 0;
    let worst: TriageCategory = categories[0];
    for (let i = 1; i < categories.length; i++) {
      if (categories[i] !== categories[i - 1]) categoryChanges++;
      worst = worstCategory(worst, categories[i]);
    }

    const first = history[0].timestamp;
    const last = history[history.length - 1].timestamp;
    const durationMs = last.getTime() - first.getTime();

    return {
      entryCount: history.length,
      highestScore: Math.max(...scores),
      lowestScore: Math.min(...scores),
      averageScore: Math.round((scores.reduce((a, b) => a + b, 0) / scores.length) * 10) / 10,
      currentCategory: categories[categories.length - 1],
      worstCategory: worst,
      firstCalculation: first,
      lastCalculation: last,
      totalDurationMinutes: Math.round(durationMs / 60000),
      categoryChanges,
    };
  },

  clearHistory: (patientId) => {
    const { historyByPatient } = get();
    const newMap = new Map(historyByPatient);
    newMap.delete(patientId);
    set({ historyByPatient: newMap });
  },
}));
