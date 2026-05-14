import { get, post } from './client';
import type {
  PatientChronicConditionResponse,
  RecordChronicConditionRequest,
  ResolveChronicConditionRequest,
} from './types';

/**
 * Structured patient chronic-conditions API (Workflow 2 refinement /
 * V61). Mirrors patientAllergyApi. The legacy free-text
 * `PATCH /patients/:id/chronic-conditions` endpoint still exists for
 * un-migrated callers; new entries should go through these.
 */
export const patientChronicConditionApi = {
  /** Active (non-RESOLVED) conditions, newest first. */
  list: (patientId: string) =>
    get<PatientChronicConditionResponse[]>(
      `/patients/${patientId}/structured-conditions`,
    ),

  /** Full history including RESOLVED rows — drives the audit view. */
  history: (patientId: string) =>
    get<PatientChronicConditionResponse[]>(
      `/patients/${patientId}/structured-conditions/history`,
    ),

  /** Record a new condition. Idempotent against duplicate names. */
  record: (patientId: string, data: RecordChronicConditionRequest) =>
    post<PatientChronicConditionResponse>(
      `/patients/${patientId}/structured-conditions`,
      data,
    ),

  /** Mark a condition as RESOLVED. DOCTOR-only on the backend. */
  resolve: (conditionId: string, data: ResolveChronicConditionRequest) =>
    post<PatientChronicConditionResponse>(
      `/patient-chronic-conditions/${conditionId}/resolve`,
      data,
    ),
};
