/* ── Visits API ── */
import { get, post, patch } from './client';
import type { CreateVisitRequest, VisitResponse, VisitStatus, EdZone, DispositionType, Page } from './types';

export interface DispositionRequest {
  dispositionType: DispositionType;
  notes?: string;
  destinationWard?: string;
  receivingFacility?: string;
}

export const visitApi = {
  create: (data: CreateVisitRequest) =>
    post<VisitResponse>('/visits', data),

  getById: (id: string) =>
    get<VisitResponse>(`/visits/${id}`),

  getActiveByHospital: (hospitalId: string, page = 0, size = 50) =>
    get<Page<VisitResponse>>(`/visits/hospital/${hospitalId}/active?page=${page}&size=${size}`),

  getByPatient: (patientId: string, page = 0, size = 20) =>
    get<Page<VisitResponse>>(`/visits/patient/${patientId}?page=${page}&size=${size}`),

  getByStatus: (hospitalId: string, status: VisitStatus, page = 0, size = 50) =>
    get<Page<VisitResponse>>(`/visits/hospital/${hospitalId}/status/${status}?page=${page}&size=${size}`),

  updateStatus: (visitId: string, status: VisitStatus) =>
    patch<VisitResponse>(`/visits/${visitId}/status?status=${status}`),

  /** Get active visits for a specific ED zone ("My Patients" for doctors) */
  getByZone: (hospitalId: string, zone: EdZone) =>
    get<VisitResponse[]>(`/visits/hospital/${hospitalId}/zone/${zone}`),

  /** Record patient disposition — final step of the ED visit */
  recordDisposition: (visitId: string, data: DispositionRequest) =>
    post<VisitResponse>(`/visits/${visitId}/disposition`, data),
};
