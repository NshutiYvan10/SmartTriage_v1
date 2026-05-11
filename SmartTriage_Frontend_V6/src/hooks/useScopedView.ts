/**
 * useScopedView — decides how a hospital-wide clinical dashboard should
 * present its data to the current user.
 *
 * Replaces the original strict "all-or-nothing" gate (see
 * {@link useCanSeeAllZones}). For *clinical decision* dashboards
 * (Sepsis, Fast-Track, ICU, Isolation) we now want three modes:
 *
 *   • HOSPITAL_WIDE — admin / CN / shift-lead → backend returns
 *     every active case at the hospital.
 *   • ZONE_SCOPED   — on-shift clinician → backend is called with
 *     ?zone=<their zone> and returns only their zone's cases. The
 *     restriction card is NOT shown; the data IS shown, filtered.
 *   • RESTRICTED    — off-shift, no zone → the restriction card is
 *     shown with a clear "you have no active shift" explanation.
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
  /** Zone to filter by when mode === 'ZONE_SCOPED'. Null otherwise. */
  zone: EdZone | null;
  /** Loading flag — true while the shift fetch is still in flight. */
  isLoading: boolean;
}

export function useScopedView(): ScopedView {
  const user = useAuthStore((s) => s.user);
  const { zone, isShiftLead, isOnShift, isLoading } = useMyShift();

  // Admin roles → full hospital view.
  if (user?.role === 'SUPER_ADMIN' || user?.role === 'HOSPITAL_ADMIN') {
    return { mode: 'HOSPITAL_WIDE', zone: null, isLoading };
  }

  // Shift-lead badge → full hospital view (acting CN authority).
  if (isShiftLead) {
    return { mode: 'HOSPITAL_WIDE', zone: null, isLoading };
  }

  // Charge Nurse designation → full hospital view regardless of badge.
  if (user?.designation === 'CHARGE_NURSE') {
    return { mode: 'HOSPITAL_WIDE', zone: null, isLoading };
  }

  // On-shift clinician → filtered to their zone.
  if (isOnShift && zone) {
    return { mode: 'ZONE_SCOPED', zone, isLoading };
  }

  // Off-shift clinician → no zone → restriction card.
  return { mode: 'RESTRICTED', zone: null, isLoading };
}
