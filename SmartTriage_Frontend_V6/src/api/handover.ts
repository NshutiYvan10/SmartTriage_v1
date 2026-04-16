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
  generate: (visitId: string, reportType: string) => post<HandoverReport>('/handover/generate', { visitId, reportType }),
  acknowledge: (id: string, receivedByName: string) => put<HandoverReport>(`/handover/${id}/acknowledge`, { receivedByName }),
  getForVisit: (visitId: string) => get<HandoverReport[]>(`/handover/visit/${visitId}`),
  getForHospital: (hospitalId: string, page = 0) => get<{ content: HandoverReport[]; totalElements: number }>(`/handover/hospital/${hospitalId}?page=${page}&size=20`),
  get: (id: string) => get<HandoverReport>(`/handover/${id}`),
};
