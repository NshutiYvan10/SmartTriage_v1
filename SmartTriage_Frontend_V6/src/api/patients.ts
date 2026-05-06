/* ── Patients API ── */
import { get, post, patch } from './client';
import type { CreatePatientRequest, PatientResponse, RegisterPatientRequest, RegisterPatientResponse, Page, PregnancyStatus } from './types';

export const patientApi = {
  create: (data: CreatePatientRequest) =>
    post<PatientResponse>('/patients', data),

  /** Combined registration — creates Patient + Visit in one atomic backend transaction */
  register: (data: RegisterPatientRequest) =>
    post<RegisterPatientResponse>('/patients/register', data),

  getById: (id: string) =>
    get<PatientResponse>(`/patients/${id}`),

  listByHospital: (hospitalId: string, page = 0, size = 20) =>
    get<Page<PatientResponse>>(`/patients/hospital/${hospitalId}?page=${page}&size=${size}`),

  search: (hospitalId: string, query: string, page = 0, size = 20) =>
    get<Page<PatientResponse>>(`/patients/hospital/${hospitalId}/search?query=${encodeURIComponent(query)}&page=${page}&size=${size}`),

  updatePregnancyStatus: (id: string, pregnancyStatus: PregnancyStatus) =>
    patch<PatientResponse>(`/patients/${id}/pregnancy-status`, { pregnancyStatus }),

  /**
   * Replace the patient's free-text known allergies. Drives the medication
   * safety engine's cross-reactivity check on every prescribe; mid-visit
   * edit matters when a clinician learns of a new allergy or wants to
   * correct a wrongly-recorded one. Pass null to clear.
   */
  updateAllergies: (id: string, knownAllergies: string | null) =>
    patch<PatientResponse>(`/patients/${id}/allergies`, { knownAllergies }),

  /** Replace the patient's free-text chronic conditions. Pass null to clear. */
  updateChronicConditions: (id: string, chronicConditions: string | null) =>
    patch<PatientResponse>(`/patients/${id}/chronic-conditions`, { chronicConditions }),
};
