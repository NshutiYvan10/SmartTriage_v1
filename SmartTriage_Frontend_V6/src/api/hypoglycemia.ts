import { get, post, put } from './client';

export interface HypoglycemiaEvent {
  id: string;
  visitId: string;
  detectedAt: string;
  glucoseLevel: number | null;
  triggerReason: string;
  severity: string;
  treatmentGiven: string | null;
  treatmentGivenAt: string | null;
  treatmentGivenByName: string | null;
  repeatGlucoseLevel: number | null;
  repeatGlucoseAt: string | null;
  resolved: boolean;
  resolvedAt: string | null;
  notes: string | null;
  createdAt: string;
}

export const hypoglycemiaApi = {
  enforce: (visitId: string) => post<HypoglycemiaEvent>(`/hypoglycemia/enforce/${visitId}`),
  recordTreatment: (id: string, data: { treatmentGiven: string }) => put<HypoglycemiaEvent>(`/hypoglycemia/${id}/treat`, data),
  recordRepeatGlucose: (id: string, data: { repeatGlucoseLevel: number }) => put<HypoglycemiaEvent>(`/hypoglycemia/${id}/repeat-glucose`, data),
  resolve: (id: string) => put<HypoglycemiaEvent>(`/hypoglycemia/${id}/resolve`),
  getForVisit: (visitId: string) => get<HypoglycemiaEvent[]>(`/hypoglycemia/visit/${visitId}`),
  getUnresolved: (hospitalId: string) => get<HypoglycemiaEvent[]>(`/hypoglycemia/hospital/${hospitalId}/unresolved`),
};
