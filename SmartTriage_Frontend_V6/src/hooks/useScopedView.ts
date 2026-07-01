/**
 * useScopedView — decides how a hospital-wide clinical dashboard should
 * present its data to the current user.
 *
 * Replaces the original strict "all-or-nothing" gate (see
 * {@link useCanSeeAllZones}). For *clinical decision* dashboards
 * (Sepsis, Fast-Track, ICU, Isolation, Hypoglycemia) we now want three modes:
 *
 *   • HOSPITAL_WIDE — admin / CN / shift-lead → backend returns
 *     every active case at the hospital.
 *   • ZONE_SCOPED   — on-shift clinician → the list is fetched once per
 *     zone the clinician covers (primary ∪ additional) and merged. The
 *     restriction card is NOT shown; the data IS shown, filtered to their
 *     covered zones.
 *   • RESTRICTED    — off-shift, no zone → the restriction card is
 *     shown with a clear "you have no active shift" explanation.
 *
 * Multi-zone note: a clinician on a shift that covers a primary zone PLUS
 * {@code additionalZones} is authorised by the backend (canReceiveZoneAlerts)
 * for every covered zone. The list endpoints accept a single ?zone, so a
 * multi-zone clinician must fan out one call per covered zone — see
 * {@link fetchForScope}, which every clinical dashboard uses so the behaviour
 * is uniform and a covered zone is never silently dropped.
 *
 * Oversight surfaces (Handover, Safety Incidents) keep the original
 * strict gate via {@link useCanSeeAllZones}; they don't use this hook.
 */

import { useMyShift } from './useMyShift';
import { useAuthStore } from '@/store/authStore';
import type { EdZone } from '@/api/types';

export type ScopedViewMode = 'HOSPITAL_WIDE' | 'ZONE_SCOPED' | 'RESTRICTED';

export interface ScopedView {
  mode: ScopedViewMode;
  /** Primary zone when mode === 'ZONE_SCOPED'. Null otherwise. Kept for labels. */
  zone: EdZone | null;
  /**
   * Every zone the caller may see when mode === 'ZONE_SCOPED' — the shift's
   * primary zone ∪ additionalZones. Empty for HOSPITAL_WIDE (the fetch omits
   * the zone param) and for RESTRICTED.
   */
  coveredZones: EdZone[];
  /** Stable, order-independent key of coveredZones for use in effect deps. */
  coveredKey: string;
  /** Loading flag — true while the shift fetch is still in flight. */
  isLoading: boolean;
}

export function useScopedView(): ScopedView {
  const user = useAuthStore((s) => s.user);
  const { zone, isShiftLead, isOnShift, isLoading, assignment } = useMyShift();

  const wide: ScopedView = {
    mode: 'HOSPITAL_WIDE', zone: null, coveredZones: [], coveredKey: '', isLoading,
  };

  // Admin roles → full hospital view.
  if (user?.role === 'SUPER_ADMIN' || user?.role === 'HOSPITAL_ADMIN') {
    return wide;
  }

  // Shift-lead badge → full hospital view (acting CN authority).
  if (isShiftLead) {
    return wide;
  }

  // Charge Nurse (NURSE role + CHARGE_NURSE designation) → full hospital
  // view regardless of badge. Mirrors backend ClinicalAuthz, which requires
  // BOTH role==NURSE AND designation==CHARGE_NURSE.
  if (user?.role === 'NURSE' && user?.designation === 'CHARGE_NURSE') {
    return wide;
  }

  // On-shift clinician → filtered to every zone they cover (primary ∪ additional).
  if (isOnShift && zone) {
    const covered = Array.from(new Set<EdZone>([zone, ...(assignment?.additionalZones ?? [])]));
    return {
      mode: 'ZONE_SCOPED',
      zone,
      coveredZones: covered,
      coveredKey: covered.slice().sort().join(','),
      isLoading,
    };
  }

  // Off-shift clinician → no zone → restriction card.
  return { mode: 'RESTRICTED', zone: null, coveredZones: [], coveredKey: '', isLoading };
}

/**
 * Load a zone-scoped clinical list the right way for the caller's scope, so a
 * multi-zone clinician never silently loses a covered zone:
 *   • RESTRICTED    → [] (the caller shows the restriction card instead).
 *   • HOSPITAL_WIDE → one call with no zone (backend returns every case).
 *   • ZONE_SCOPED   → one call per covered zone, merged + de-duplicated by id.
 *
 * `fetchZone(zone?)` is the module's list API (e.g. sepsisApi.getActive). It is
 * called with `undefined` for the hospital-wide case and with each covered zone
 * otherwise. Non-array responses are treated as empty (defensive).
 */
export async function fetchForScope<T extends { id: string }>(
  scope: ScopedView,
  fetchZone: (zone?: EdZone) => Promise<T[]>,
): Promise<T[]> {
  if (scope.mode === 'RESTRICTED') return [];
  if (scope.mode === 'HOSPITAL_WIDE') {
    const data = await fetchZone(undefined);
    return Array.isArray(data) ? data : [];
  }
  const zones = scope.coveredZones.length
    ? scope.coveredZones
    : (scope.zone ? [scope.zone] : []);
  if (zones.length === 0) return [];
  const batches = await Promise.all(zones.map((z) => fetchZone(z)));
  const merged = new Map<string, T>();
  batches.forEach((b) => (Array.isArray(b) ? b : []).forEach((item) => merged.set(item.id, item)));
  return Array.from(merged.values());
}
