/* ── Medications API ── */
import { get, post, patch } from './client';
import type {
  PrescribeMedicationRequest,
  AdministerMedicationRequest,
  CountersignMedicationRequest,
  MedicationResponse,
  Page,
} from './types';

export const medicationApi = {
  prescribe: (data: PrescribeMedicationRequest) =>
    post<MedicationResponse>('/medications', data),

  administer: (id: string, data: AdministerMedicationRequest) =>
    patch<MedicationResponse>(`/medications/${id}/administer`, data),

  countersign: (id: string, data: CountersignMedicationRequest) =>
    patch<MedicationResponse>(`/medications/${id}/countersign`, data),

  hold: (id: string, reason: string) =>
    patch<MedicationResponse>(`/medications/${id}/hold?reason=${encodeURIComponent(reason)}`),

  cancel: (id: string, reason: string) =>
    patch<MedicationResponse>(`/medications/${id}/cancel?reason=${encodeURIComponent(reason)}`),

  refuse: (id: string, reason: string) =>
    patch<MedicationResponse>(`/medications/${id}/refuse?reason=${encodeURIComponent(reason)}`),

  getById: (id: string) =>
    get<MedicationResponse>(`/medications/${id}`),

  getByVisit: (visitId: string, page = 0, size = 50) =>
    get<Page<MedicationResponse>>(`/medications/visit/${visitId}?page=${page}&size=${size}`),

  getAllByVisit: (visitId: string) =>
    get<MedicationResponse[]>(`/medications/visit/${visitId}/all`),

  /**
   * Patient-level medication history across all visits, newest first.
   * Drives the prescribing UI's "Reorder" feature so the doctor can copy a
   * past prescription into a new order with one tap.
   */
  getPatientHistory: (patientId: string) =>
    get<MedicationResponse[]>(`/medications/patient/${patientId}/history`),
};
