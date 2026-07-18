/* ── Diagnoses API ── */
import { get, post, put, del } from './client';
import type { AmendDiagnosisRequest, CreateDiagnosisRequest, DiagnosisResponse, DiagnosisType, Page } from './types';

export const diagnosisApi = {
  create: (data: CreateDiagnosisRequest) =>
    post<DiagnosisResponse>('/diagnoses', data),

  update: (id: string, data: CreateDiagnosisRequest) =>
    put<DiagnosisResponse>(`/diagnoses/${id}`, data),

  /** Non-destructive edit — creates a new linked version, preserves the original. */
  amend: (id: string, data: AmendDiagnosisRequest) =>
    post<DiagnosisResponse>(`/diagnoses/${id}/amend`, data),

  /** Full version history (root original + every amendment), oldest first. */
  getHistory: (id: string) =>
    get<DiagnosisResponse[]>(`/diagnoses/${id}/history`),

  delete: (id: string) =>
    del<void>(`/diagnoses/${id}`),

  getById: (id: string) =>
    get<DiagnosisResponse>(`/diagnoses/${id}`),

  getByVisit: (visitId: string, page = 0, size = 20) =>
    get<Page<DiagnosisResponse>>(`/diagnoses/visit/${visitId}?page=${page}&size=${size}`),

  getAllByVisit: (visitId: string) =>
    get<DiagnosisResponse[]>(`/diagnoses/visit/${visitId}/all`),

  getByType: (visitId: string, type: DiagnosisType) =>
    get<DiagnosisResponse[]>(`/diagnoses/visit/${visitId}/type/${type}`),
};
