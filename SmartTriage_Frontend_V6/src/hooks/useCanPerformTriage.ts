/**
 * Client-side mirror of the backend's
 * {@code ClinicalAuthz.callerCanPerformTriage} predicate.
 *
 * Authority follows TODAY'S shift assignment, not permanent designation.
 * Returns true only when the user has triage write-authority *right now*.
 *
 * Allowed:
 *   - today's shift function == TRIAGE_NURSE  (the canonical authority)
 *   - today's shift function == CHARGE_NURSE  (actual CN on duty)
 *   - today's shift-lead badge holder         (daily, transferable —
 *                                              the emergency override path)
 *
 * Denied:
 *   - SUPER_ADMIN / HOSPITAL_ADMIN            (never clinical)
 *   - DOCTOR                                  (triage is a nurse function)
 *   - NURSE with Designation.CHARGE_NURSE
 *     but today's shift function is something
 *     else (e.g. ZONE_NURSE in Acute)         (your shift today wins
 *                                              over your permanent title)
 *
 * The backend re-checks on every triage write, so this hook is purely
 * UX — graying buttons the user can't successfully click — not a
 * security boundary.
 */
import { useAuthStore } from '@/store/authStore';

export function useCanPerformTriage(): boolean {
  const user = useAuthStore((s) => s.user);
  if (!user) return false;
  if (user.role === 'SUPER_ADMIN' || user.role === 'HOSPITAL_ADMIN') return false;

  const fn = user.currentShiftFunction;
  if (fn === 'TRIAGE_NURSE' || fn === 'CHARGE_NURSE') return true;
  if (user.isShiftLead === true) return true;

  return false;
}
