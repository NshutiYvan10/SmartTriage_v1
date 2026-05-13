import { get, post } from './client';
import type {
  PatientAllergyResponse,
  RecordAllergyRequest,
  RefuteAllergyRequest,
} from './types';

/**
 * Structured patient-allergy API (Workflow 2 / V58).
 *
 * Replaces the legacy free-text PATCH /api/v1/patients/:id/allergies
 * endpoint. The legacy endpoint still works — read-only fallback for
 * un-migrated records — but every new allergy entry should go
 * through these routes so the safety engine can read severity +
 * reaction.
 */
export const patientAllergyApi = {
  /** Active (non-refuted) allergies for a patient. Drives the
   *  profile panel + the prescribe-time safety dialog. */
  list: (patientId: string) =>
    get<PatientAllergyResponse[]>(`/patients/${patientId}/structured-allergies`),

  /** Full audit history including refuted rows. */
  history: (patientId: string) =>
    get<PatientAllergyResponse[]>(`/patients/${patientId}/structured-allergies/history`),

  /** Record a new structured allergy. Idempotent: re-sending an
   *  identical (patient + allergen) row returns the existing entry
   *  instead of creating a duplicate. */
  record: (patientId: string, data: RecordAllergyRequest) =>
    post<PatientAllergyResponse>(
      `/patients/${patientId}/structured-allergies`,
      data,
    ),

  /** Mark an allergy as REFUTED. The row is not hard-deleted — refute
   *  is itself an audit event. DOCTOR role only on the backend. */
  refute: (allergyId: string, data: RefuteAllergyRequest) =>
    post<PatientAllergyResponse>(`/patient-allergies/${allergyId}/refute`, data),
};
