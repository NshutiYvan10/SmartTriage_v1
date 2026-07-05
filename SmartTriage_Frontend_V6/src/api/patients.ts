/* ── Patients API ── */
import { get, patch, post, put } from './client';
import type {
  CreatePatientRequest,
  PatientResponse,
  PregnancyStatus,
  RegisterPatientRequest,
  RegisterPatientResponse,
  PatientLookupParams,
  PatientLookupCandidate,
  GlobalPatientRow,
  OpenVisitHereRequest,
  UpdatePatientRequest,
  Page,
} from './types';

/**
 * Build a `?key=value&...` querystring from a partial object, dropping any
 * blank/undefined entries. Used by `lookup` so we can call it like
 * `patientApi.lookup(hospitalId, { nationalId, phone, dob })` without
 * caring which fields are populated.
 */
function buildQueryString(params: Record<string, string | undefined | null>): string {
  const entries = Object.entries(params).filter(
    ([, v]) => v !== undefined && v !== null && String(v).trim() !== '',
  );
  if (entries.length === 0) return '';
  const qs = entries
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v).trim())}`)
    .join('&');
  return `?${qs}`;
}

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

  /**
   * Federated patient lookup — pass any combination of identifiers and
   * receive a ranked list of candidates (highest confidence first). Returns
   * an empty array if no identifiers are supplied. The hospital is path-
   * scoped server-side; this client never derives it from input.
   */
  lookup: (hospitalId: string, params: PatientLookupParams) =>
    get<PatientLookupCandidate[]>(
      `/patients/hospital/${hospitalId}/lookup${buildQueryString(params as Record<string, string | undefined>)}`,
    ),

  /**
   * Phase 13b — set or clear the structured pregnancy status. Pass
   * `UNKNOWN` to clear a previously-set value (rather than null) so
   * the column signals "we asked, we don't know" instead of
   * "we never asked". Returns the updated patient.
   */
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

  /**
   * Registrar demographic correction — fix data-entry errors (name/DOB/gender/national
   * ID/passport/birth-cert/phone/address/emergency contact/blood type). PARTIAL update:
   * only fields present in `data` are applied. A duplicate national ID at this hospital
   * is rejected (409). RFID card reassignment is a separate endpoint (rfidApi.replaceCard).
   */
  updateDemographics: (id: string, data: UpdatePatientRequest) =>
    put<PatientResponse>(`/patients/${id}`, data),

  /**
   * GLOBAL registry search — REGISTRAR-only, system-wide across ALL hospitals.
   * Finds a patient first registered at any hospital so a returning patient is
   * reused, not re-registered. `myHospitalId` (the registrar's hospital) is used
   * only to flag which rows are local / already have an open visit here.
   */
  registrySearch: (query: string, myHospitalId: string | undefined, page = 0, size = 20) =>
    get<Page<GlobalPatientRow>>(
      `/patients/registry/search?query=${encodeURIComponent(query)}`
        + (myHospitalId ? `&myHospitalId=${myHospitalId}` : '')
        + `&page=${page}&size=${size}`),

  /**
   * Open a fresh visit HERE for a patient found in the global registry — the
   * manual-search twin of the RFID open-visit flow. Reuses / registers-a-local-copy
   * of a cross-hospital patient and starts the visit; a second open visit for the
   * same patient at this hospital is rejected by the backend guard.
   */
  openVisitHere: (patientId: string, data: OpenVisitHereRequest) =>
    post<RegisterPatientResponse>(`/patients/${patientId}/open-visit-here`, data),
};
