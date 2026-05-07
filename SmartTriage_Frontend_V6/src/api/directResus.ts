/* ── Direct Resus Admission API ──────────────────────────────────────
 *
 * Phase H (V28) — Red-patient bypass admission.
 *
 * In a real ED, when a patient arrives in obvious extremis (cardiac
 * arrest, severe trauma, obstructed airway), the nurse does not stop
 * to fill a triage form. They take the patient straight to the
 * resuscitation bay and clinical intervention starts immediately.
 * These endpoints reflect that reality.
 *
 * - admit():         the one-click admission
 * - confirmArrival(): mark an ambulance pre-arrival as physically in
 * - resolveIdentity(): replace a "Unknown Alpha" placeholder with the
 *                     real identity (in-place rename or MPI merge)
 */
import { post } from './client';
import type {
  DirectResusAdmissionRequest,
  DirectResusAdmissionResponse,
  PatientResponse,
  ResolveIdentityRequest,
} from './types';

export const directResusApi = {
  /**
   * Admit a Red patient straight to RESUS, bypassing the standard triage
   * form. Always succeeds — if no RESUS bed is available the response
   * carries `overflow=true` and `transferCandidates` so the charge
   * nurse can free a bed by moving someone out.
   */
  admit: (data: DirectResusAdmissionRequest) =>
    post<DirectResusAdmissionResponse>('/admissions/direct-resus', data),

  /**
   * For ambulance pre-arrivals: marks the patient as physically arrived
   * and starts the door clock (sets arrivalConfirmedAt).
   */
  confirmArrival: (visitId: string) =>
    post<DirectResusAdmissionResponse>(`/admissions/${visitId}/confirm-arrival`),

  /**
   * Resolve a placeholder patient's identity. The placeholder UUID is
   * preserved so visit/triage/bed/alert references remain valid;
   * unless `mergeIntoPatientId` is set, in which case the placeholder
   * is soft-deleted and visits are re-pointed at the existing patient.
   */
  resolveIdentity: (patientId: string, data: ResolveIdentityRequest) =>
    post<PatientResponse>(`/patients/${patientId}/resolve-identity`, data),
};
