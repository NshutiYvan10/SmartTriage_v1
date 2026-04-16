import { get, post, put } from './client';

export interface SafetyIncident {
  id: string;
  hospitalId: string;
  visitId: string | null;
  incidentNumber: string;
  incidentType: string;
  severity: string;
  status: string;
  incidentDateTime: string;
  locationInHospital: string | null;
  description: string;
  contributingFactors: string | null;
  immediateActions: string | null;
  reportedByName: string;
  reportedByRole: string | null;
  patientHarmed: boolean | null;
  rootCauseAnalysis: string | null;
  correctiveAction: string | null;
  lessonsLearned: string | null;
  isAnonymous: boolean;
  notes: string | null;
  createdAt: string;
}

export interface ReportIncidentRequest {
  hospitalId: string;
  visitId?: string;
  incidentType: string;
  severity: string;
  incidentDateTime: string;
  locationInHospital?: string;
  description: string;
  contributingFactors?: string;
  immediateActions?: string;
  reportedByName: string;
  reportedByRole?: string;
  isAnonymous?: boolean;
}

export const safetyApi = {
  report: (data: ReportIncidentRequest) => post<SafetyIncident>('/safety/incidents', data),
  startInvestigation: (id: string, data: { investigatorName: string }) => put<SafetyIncident>(`/safety/incidents/${id}/investigate`, data),
  completeInvestigation: (id: string, data: { rootCauseAnalysis: string; rootCauseCategory: string; correctiveAction: string }) => put<SafetyIncident>(`/safety/incidents/${id}/complete-investigation`, data),
  close: (id: string, data: { lessonsLearned: string }) => put<SafetyIncident>(`/safety/incidents/${id}/close`, data),
  getForHospital: (hospitalId: string, page = 0) => get<{ content: SafetyIncident[]; totalElements: number }>(`/safety/incidents/hospital/${hospitalId}?page=${page}&size=20`),
  get: (id: string) => get<SafetyIncident>(`/safety/incidents/${id}`),
};
