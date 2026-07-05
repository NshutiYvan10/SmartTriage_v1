/* ── Vitals API ── */
import { get, post } from './client';
import type { RecordVitalsRequest, VitalSignsResponse, Page } from './types';

export const vitalApi = {
  record: (data: RecordVitalsRequest) =>
    post<VitalSignsResponse>('/vitals', data),

  getByVisit: (visitId: string, page = 0, size = 50) =>
    get<Page<VitalSignsResponse>>(`/vitals/visit/${visitId}?page=${page}&size=${size}`),

  getLatest: (visitId: string) =>
    get<VitalSignsResponse>(`/vitals/visit/${visitId}/latest`),
};
