/**
 * Client-side mirror of the backend's
 * {@code ClinicalAuthz.callerCanConfirmFieldTriage} predicate.
 *
 * Who may CONFIRM a paramedic's RED/ORANGE field triage on arrival — accepting the field category
 * to flip the visit to TRIAGED WITHOUT waiting for the triage-desk nurse. Deliberately BROADER
 * than {@link useCanPerformTriage}: it also empowers the clinician actually RECEIVING the patient
 * in the zone, so a RED ambulance arrival never stalls in "Awaiting Triage" because the triage /
 * charge nurse is occupied elsewhere.
 *
 * Allowed:
 *   - the triage authorities (triage nurse / charge nurse / shift-lead) — {@link useCanPerformTriage}; OR
 *   - a DOCTOR or NURSE whose CURRENT shift covers the patient's zone (currentZone or an
 *     additionalZone) — the receiving Resus/Acute team.
 *
 * Denied: admins, read-only accounts, registrars, paramedics, and clinicians whose shift does not
 * cover the patient's zone.
 *
 * The backend re-checks on every confirm write, so this hook is purely UX — graying the button
 * the user can't successfully click — not a security boundary. A clinician who DISAGREES with the
 * field category does not confirm; re-triage (the full form) stays gated to the triage authorities.
 */
import { useAuthStore } from '@/store/authStore';
import { useCanPerformTriage } from './useCanPerformTriage';
import type { EdZone } from '@/api/types';

export function useCanConfirmFieldTriage(visitZone: EdZone | null | undefined): boolean {
  const user = useAuthStore((s) => s.user);
  const isTriageAuthority = useCanPerformTriage();

  if (isTriageAuthority) return true;
  if (!user) return false;
  if (user.role !== 'DOCTOR' && user.role !== 'NURSE') return false;
  if (!visitZone) return false;

  const covered: (EdZone | null | undefined)[] = [user.currentZone, ...(user.additionalZones ?? [])];
  return covered.includes(visitZone);
}
