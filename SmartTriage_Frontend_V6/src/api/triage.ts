/* ── Triage API ── */
import { get, post } from './client';
import type { PerformTriageRequest, TriageRecordResponse, Page } from './types';

export const triageApi = {
  perform: (data: PerformTriageRequest) =>
    post<TriageRecordResponse>('/triage', data),

  getHistory: (visitId: string, page = 0, size = 20) =>
    get<Page<TriageRecordResponse>>(`/triage/visit/${visitId}/history?page=${page}&size=${size}`),

  getLatest: (visitId: string) =>
    get<TriageRecordResponse>(`/triage/visit/${visitId}/latest`),
};
