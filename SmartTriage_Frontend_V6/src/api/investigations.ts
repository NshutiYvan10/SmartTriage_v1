/* ── Investigations API ── */
import { get, post, patch } from './client';
import type {
  OrderInvestigationRequest,
  RecordInvestigationResultRequest,
  InvestigationResponse,
  InvestigationType,
  Page,
} from './types';

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
};
