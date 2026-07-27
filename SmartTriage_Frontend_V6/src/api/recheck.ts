/* ── Vitals recheck (obs-round) API ──
 *
 * The reassessment clock for patients NOT on a continuous monitor —
 * chair-based GENERAL/AMBULATORY patients above all. The clock basis is
 * vitals-aware: latest of last triage and last recorded vitals (a
 * completed roaming spot-check produces a VitalSigns snapshot, so
 * finishing the check resets the clock server-side).
 *
 * Ratified intervals (2026-07-27): RED 0 (belongs on a monitor) ·
 * ORANGE 30 · YELLOW 60 · GREEN 120 minutes.
 */
import { get } from './client';
import type { EdZone, TriageCategory } from './types';

export interface RecheckWorklistItem {
  visitId: string;
  visitNumber: string;
  patientName: string;
  isPediatric: boolean;
  category: TriageCategory;
  tewsScore: number | null;
  zone: EdZone | null;
  /** Bed/space label when placed; null for chair-based zones. */
  bedCode: string | null;
  /** Basis of the clock: latest of last triage and last recorded vitals. */
  lastAssessedAt: string;
  nextDueAt: string;
  intervalMinutes: number;
  /** Minutes until due; negative = overdue by that many minutes. */
  minutesUntilDue: number;
  overdue: boolean;
  /** An active spot-check session already exists for this visit. */
  checkInProgress: boolean;
}

export const recheckApi = {
  /**
   * Vitals-round worklist, soonest-due first. Zone-filtered requests
   * need only zone-scoped access; the unfiltered hospital-wide list
   * requires all-zones visibility (charge nurse / admin).
   */
  worklist: (hospitalId: string, zone?: EdZone) =>
    get<RecheckWorklistItem[]>(
      `/retriage/recheck-worklist/${hospitalId}${zone ? `?zone=${zone}` : ''}`),
};
