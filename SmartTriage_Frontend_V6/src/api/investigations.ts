/* ── Investigations API ── */
import { get, post, patch, del, downloadBlob, saveBlob, uploadFile } from './client';
import type {
  OrderInvestigationRequest,
  RecordInvestigationResultRequest,
  InvestigationResponse,
  InvestigationType,
  Page,
} from './types';
import type { LabReportDocument } from './lab';

export const investigationApi = {
  order: (data: OrderInvestigationRequest) =>
    post<InvestigationResponse>('/investigations', data),

  specimenCollected: (id: string) =>
    patch<InvestigationResponse>(`/investigations/${id}/specimen-collected`),

  markInProgress: (id: string) =>
    patch<InvestigationResponse>(`/investigations/${id}/in-progress`),

  recordResult: (id: string, data: RecordInvestigationResultRequest) =>
    patch<InvestigationResponse>(`/investigations/${id}/result`, data),

  cancel: (id: string, reason: string) =>
    patch<InvestigationResponse>(`/investigations/${id}/cancel?reason=${encodeURIComponent(reason)}`),

  getById: (id: string) =>
    get<InvestigationResponse>(`/investigations/${id}`),

  getByVisit: (visitId: string, page = 0, size = 50) =>
    get<Page<InvestigationResponse>>(`/investigations/visit/${visitId}?page=${page}&size=${size}`),

  getAllByVisit: (visitId: string) =>
    get<InvestigationResponse[]>(`/investigations/visit/${visitId}/all`),

  getByType: (visitId: string, type: InvestigationType) =>
    get<InvestigationResponse[]>(`/investigations/visit/${visitId}/type/${type}`),

  getPending: (visitId: string) =>
    get<InvestigationResponse[]>(`/investigations/visit/${visitId}/pending`),

  /**
   * Workflow 2 refinement — every investigation the authenticated
   * doctor has ordered, across every visit, newest first. Drives
   * the standalone Doctor Investigations view. Backend filters by
   * ordered_by_id FK (post-V62) with a case-insensitive name
   * fallback for legacy rows.
   */
  getMyOrders: () =>
    get<InvestigationResponse[]>(`/investigations/doctor/me`),

  /**
   * Imaging & Diagnostics worklist — every active imaging/ECG order at the
   * hospital that still needs a technician (ORDERED / IN_PROGRESS), across all
   * patients. The technician surface for orders the lab pipeline does NOT own,
   * so an ordered X-ray/CT/US/ECG can't silently vanish. Zone-scoped server-side.
   */
  imagingWorklist: (hospitalId: string) =>
    get<InvestigationResponse[]>(`/investigations/hospital/${hospitalId}/imaging-worklist`),

  // ── Imaging/ECG report document attachments (interim standard) ──
  listDocuments: (investigationId: string) =>
    get<LabReportDocument[]>(`/investigations/${investigationId}/documents`),

  uploadDocument: (investigationId: string, file: File, description?: string) => {
    const fd = new FormData();
    fd.append('file', file);
    if (description && description.trim()) fd.append('description', description.trim());
    return uploadFile<LabReportDocument>(`/investigations/${investigationId}/documents`, fd);
  },

  downloadDocument: async (investigationId: string, documentId: string, fallbackName = 'imaging-report') => {
    const { blob, filename } = await downloadBlob(`/investigations/${investigationId}/documents/${documentId}/download`, fallbackName);
    saveBlob(blob, filename);
  },

  deleteDocument: (investigationId: string, documentId: string) =>
    del<void>(`/investigations/${investigationId}/documents/${documentId}`),
};
