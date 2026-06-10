import { get, post, put } from './client';

export interface HandoverReport {
  id: string;
  visitId: string;
  hospitalId: string;
  reportType: string;
  generatedAt: string;
  generatedByName: string;
  patientSummary: string | null;
  presentingComplaint: string | null;
  triageSummary: string | null;
  vitalSignsTrend: string | null;
  investigationsResults: string | null;
  diagnosisSummary: string | null;
  treatmentSummary: string | null;
  activeClinicalAlerts: string | null;
  outstandingTasks: string | null;
  planOfCare: string | null;
  edTimeline: string | null;
  receivedByName: string | null;
  isAcknowledged: boolean;
  notes: string | null;
  createdAt: string;
}

export const handoverApi = {
  // B6 — backend is POST /handover/generate/{visitId} with body
  // { reportType, generatedByName?, notes? }. The old POST /handover/generate
  // with { visitId, reportType } was a 404.
  generate: (visitId: string, reportType: string) =>
    post<HandoverReport>(`/handover/generate/${visitId}`, { reportType }),
  // B6 — backend DTO field is `receiverName`; sending `receivedByName` failed
  // its @NotBlank validation (400).
  acknowledge: (id: string, receiverName: string) =>
    put<HandoverReport>(`/handover/${id}/acknowledge`, { receiverName }),
  getForVisit: (visitId: string) => get<HandoverReport[]>(`/handover/visit/${visitId}`),
  // B6 — backend is GET /handover/hospital/{id}/shift, returning an ARRAY of
  // this shift's reports (last 12h), not a paginated Page. The old
  // /handover/hospital/{id}?page= was a 404 + shape mismatch.
  getForHospital: (hospitalId: string) =>
    get<HandoverReport[]>(`/handover/hospital/${hospitalId}/shift`),
  get: (id: string) => get<HandoverReport>(`/handover/${id}`),
};
