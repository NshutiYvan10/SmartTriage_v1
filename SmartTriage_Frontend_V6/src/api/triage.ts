/* ── Triage API ── */
import { get, post } from './client';
import type { PerformTriageRequest, TriageRecordResponse, Page, VisitResponse } from './types';

export const triageApi = {
  perform: (data: PerformTriageRequest) =>
    post<TriageRecordResponse>('/triage', data),

  getHistory: (visitId: string, page = 0, size = 20) =>
    get<Page<TriageRecordResponse>>(`/triage/visit/${visitId}/history?page=${page}&size=${size}`),

  getLatest: (visitId: string) =>
    get<TriageRecordResponse>(`/triage/visit/${visitId}/latest`),

  /** "Placed — awaiting ED triage" worklist: acuity-split RED/ORANGE ambulance arrivals (and
   *  Direct Resus) that bypass the pre-triage desk queue but still owe a formal ED triage.
   *  Gated server-side to triage authorities (triage nurse / charge nurse / shift-lead). */
  awaitingEdTriage: (hospitalId: string, page = 0, size = 50) =>
    get<Page<VisitResponse>>(`/triage/hospital/${hospitalId}/awaiting-ed-triage?page=${page}&size=${size}`),
};
