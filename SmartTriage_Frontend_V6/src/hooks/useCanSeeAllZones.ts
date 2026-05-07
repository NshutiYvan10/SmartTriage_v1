/**
 * useCanSeeAllZones — frontend mirror of the backend's
 * `ClinicalAuthz.canSeeAllZonesAtHospital` predicate.
 *
 * Returns `true` when the authenticated user is allowed to see clinical
 * data across every zone of their hospital (i.e. when calling the
 * hospital-wide list endpoints will succeed instead of returning 403).
 * The actors that satisfy the predicate:
 *
 *   - SUPER_ADMIN       — system role, always.
 *   - HOSPITAL_ADMIN    — own hospital.
 *   - shift-lead badge  — current acting shift-lead at their hospital.
 *   - CHARGE_NURSE      — designation grants cross-zone read regardless
 *                         of badge state, matching the backend.
 *
 * Used by clinical dashboards that load hospital-wide patient lists
 * (Sepsis, Fast-Track, Isolation, ICU, Referrals, Handover, Safety
 * Incidents). Without this branch, a regular doctor opening such a
 * page would issue a guaranteed-fail request to a now-gated endpoint
 * and see a generic 403; the predicate lets the page render a clear
 * "lead/admin only" panel instead.
 *
 * The result mirrors the server's policy exactly so the UI never says
 * "you can see this" when the API will refuse, and never withholds
 * what the API will allow.
 */

import { useMyShift } from './useMyShift';
import { useAuthStore } from '@/store/authStore';

export interface CanSeeAllZonesInfo {
  /** True when the user is allowed cross-zone hospital-wide reads. */
  canSeeAllZones: boolean;
  /**
   * If false, this is the reason — drives the message shown on the
   * "lead/admin only" panel. "OFF_SHIFT" applies when the user has no
   * active shift assignment AT ALL (off-duty); "ZONE_SCOPED" applies
   * when they are on shift but only on one zone.
   */
  reason: 'ALLOWED' | 'OFF_SHIFT' | 'ZONE_SCOPED';
  /**
   * The user's current zone, or null if off-shift. Surfaced so the
   * "lead/admin only" panel can include "you're currently on RESUS"
   * for context.
   */
  zone: ReturnType<typeof useMyShift>['zone'];
}

export function useCanSeeAllZones(): CanSeeAllZonesInfo {
  const user = useAuthStore((s) => s.user);
  const { zone, isShiftLead, isOnShift } = useMyShift();

  // Admin roles — always allowed.
  if (user?.role === 'SUPER_ADMIN' || user?.role === 'HOSPITAL_ADMIN') {
    return { canSeeAllZones: true, reason: 'ALLOWED', zone };
  }

  // Active shift-lead badge — current acting CN. Mirrors backend.
  if (isShiftLead) {
    return { canSeeAllZones: true, reason: 'ALLOWED', zone };
  }

  // Charge Nurse designation — cross-zone read regardless of badge.
  if (user?.designation === 'CHARGE_NURSE') {
    return { canSeeAllZones: true, reason: 'ALLOWED', zone };
  }

  return {
    canSeeAllZones: false,
    reason: isOnShift ? 'ZONE_SCOPED' : 'OFF_SHIFT',
    zone,
  };
}
