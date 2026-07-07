import { get, post, put, downloadBlob } from './client';

/* ── Backend enum mirrors — MUST match common/enums/Incident*.java 1:1.
   The old client spoke a vocabulary the backend never had (severity MODERATE,
   type DEVICE_FAILURE, status UNDER_INVESTIGATION…), so 4 of 5 severity choices
   400'd and the workflow buttons 500'd — reporting was effectively impossible. ── */

export const INCIDENT_TYPES = [
  'MEDICATION_ERROR', 'DIAGNOSTIC_ERROR', 'DELAYED_TREATMENT', 'WRONG_PATIENT',
  'FALL', 'EQUIPMENT_FAILURE', 'COMMUNICATION_FAILURE', 'DOCUMENTATION_ERROR',
  'TRIAGE_ERROR', 'MISSED_DIAGNOSIS', 'ALLERGIC_REACTION', 'PROCEDURAL_COMPLICATION',
  'BLOOD_TRANSFUSION_ERROR', 'INFECTION_RELATED', 'PATIENT_IDENTIFICATION_ERROR', 'OTHER',
] as const;
export type IncidentTypeValue = typeof INCIDENT_TYPES[number];

/** WHO-style harm scale, mildest → worst. */
export const INCIDENT_SEVERITIES = [
  'NEAR_MISS', 'NO_HARM', 'MILD_HARM', 'MODERATE_HARM', 'SEVERE_HARM', 'DEATH',
] as const;
export type IncidentSeverityValue = typeof INCIDENT_SEVERITIES[number];

/** Register lifecycle: report → investigate → root cause → corrective action → implemented → closed. */
export const INCIDENT_STATUSES = [
  'REPORTED', 'INVESTIGATION_STARTED', 'ROOT_CAUSE_IDENTIFIED',
  'CORRECTIVE_ACTION_PLANNED', 'CORRECTIVE_ACTION_IMPLEMENTED', 'CLOSED',
] as const;
export type IncidentStatusValue = typeof INCIDENT_STATUSES[number];

export interface SafetyIncident {
  id: string;
  hospitalId: string;
  hospitalName: string | null;
  visitId: string | null;
  visitNumber: string | null;
  incidentNumber: string;
  incidentType: IncidentTypeValue | string;
  severity: IncidentSeverityValue | string;
  status: IncidentStatusValue | string;
  incidentDateTime: string;
  locationInHospital: string | null;
  description: string;
  contributingFactors: string | null;
  immediateActions: string | null;
  reportedByName: string;
  reportedByRole: string | null;
  reportedAt: string | null;
  involvedStaffNames: string | null;
  patientHarmed: boolean | null;
  investigatorName: string | null;
  investigationStartedAt: string | null;
  rootCauseAnalysis: string | null;
  rootCauseCategory: string | null;
  investigationCompletedAt: string | null;
  correctiveAction: string | null;
  correctiveActionOwner: string | null;
  correctiveActionDeadline: string | null;
  correctiveActionCompletedAt: string | null;
  preventiveMeasures: string | null;
  closedAt: string | null;
  closedByName: string | null;
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
  involvedStaffNames?: string;
  patientHarmed?: boolean;
  /** Fallback only — the server stamps the reporter from the authenticated principal. */
  reportedByName?: string;
  reportedByRole?: string;
  isAnonymous?: boolean;
}

export const safetyApi = {
  report: (data: ReportIncidentRequest) => post<SafetyIncident>('/safety/incidents', data),
  startInvestigation: (id: string, data: { investigatorName: string }) =>
    put<SafetyIncident>(`/safety/incidents/${id}/investigate`, data),
  recordRootCause: (id: string, data: { rootCauseAnalysis: string; rootCauseCategory?: string }) =>
    put<SafetyIncident>(`/safety/incidents/${id}/root-cause`, data),
  planCorrectiveAction: (id: string, data: {
    correctiveAction: string; correctiveActionOwner?: string;
    correctiveActionDeadline?: string; preventiveMeasures?: string;
  }) => put<SafetyIncident>(`/safety/incidents/${id}/corrective-action`, data),
  completeCorrectiveAction: (id: string) =>
    put<SafetyIncident>(`/safety/incidents/${id}/complete-action`),
  close: (id: string, data: { lessonsLearned: string }) =>
    put<SafetyIncident>(`/safety/incidents/${id}/close`, data),
  getForHospital: (hospitalId: string, page = 0) =>
    get<{ content: SafetyIncident[]; totalElements: number }>(`/safety/incidents/hospital/${hospitalId}?page=${page}&size=20`),
  get: (id: string) => get<SafetyIncident>(`/safety/incidents/${id}`),
  /** Server-side CSV of the incident register in a window (ISO instants). Returns blob + filename. */
  exportCsv: (hospitalId: string, from: string, to: string) =>
    downloadBlob(`/safety/incidents/hospital/${hospitalId}/export/csv?from=${from}&to=${to}`, 'safety-incidents.csv'),
  /** Printable single-incident report PDF. Returns blob + filename. */
  downloadPdf: (id: string) =>
    downloadBlob(`/safety/incidents/${id}/pdf`, `safety-incident-${id}.pdf`),
};
