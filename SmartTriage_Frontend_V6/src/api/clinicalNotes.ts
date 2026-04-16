/* ── Clinical Notes API ── */
import { get, post, put, del } from './client';
import type { CreateClinicalNoteRequest, ClinicalNoteResponse, NoteType, Page } from './types';

export const clinicalNoteApi = {
  create: (data: CreateClinicalNoteRequest) =>
    post<ClinicalNoteResponse>('/clinical-notes', data),

  update: (id: string, data: CreateClinicalNoteRequest) =>
    put<ClinicalNoteResponse>(`/clinical-notes/${id}`, data),

  delete: (id: string) =>
    del<void>(`/clinical-notes/${id}`),

  getById: (id: string) =>
    get<ClinicalNoteResponse>(`/clinical-notes/${id}`),

  getByVisit: (visitId: string, page = 0, size = 50) =>
    get<Page<ClinicalNoteResponse>>(`/clinical-notes/visit/${visitId}?page=${page}&size=${size}`),

  getAllByVisit: (visitId: string) =>
    get<ClinicalNoteResponse[]>(`/clinical-notes/visit/${visitId}/all`),

  getByType: (visitId: string, type: NoteType) =>
    get<ClinicalNoteResponse[]>(`/clinical-notes/visit/${visitId}/type/${type}`),

  getLatestByType: (visitId: string, type: NoteType) =>
    get<ClinicalNoteResponse>(`/clinical-notes/visit/${visitId}/type/${type}/latest`),
};
