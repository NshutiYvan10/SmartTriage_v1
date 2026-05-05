/* ── Clinical Notes API ── */
import { get, post, del } from './client';
import type { CreateClinicalNoteRequest, ClinicalNoteResponse, NoteType, Page } from './types';

export const clinicalNoteApi = {
  create: (data: CreateClinicalNoteRequest) =>
    post<ClinicalNoteResponse>('/clinical-notes', data),

  /**
   * Correct an existing clinical note by appending a new row that supersedes
   * the original. The original is never modified — both rows remain visible
   * in the timeline so the correction trail is auditable. Returns the new
   * (correction) row.
   */
  supersede: (originalId: string, data: CreateClinicalNoteRequest) =>
    post<ClinicalNoteResponse>(`/clinical-notes/${originalId}/supersede`, data),

  /**
   * @deprecated Use {@link supersede} instead. Notes are append-only — calling
   * this just appends a correction row, it does not mutate the original.
   * Retained as an alias only for backward source compatibility.
   */
  update: (id: string, data: CreateClinicalNoteRequest) =>
    post<ClinicalNoteResponse>(`/clinical-notes/${id}/supersede`, data),

  /**
   * Soft-delete a note. Restricted server-side to admin roles. Routine
   * clinical corrections must use {@link supersede} so the original record
   * is preserved.
   */
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
