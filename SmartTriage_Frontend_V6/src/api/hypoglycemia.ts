import { get, post, put } from './client';

export type HypoglycemiaSeverityValue =
  | 'NONE' | 'PENDING_CHECK' | 'NORMAL' | 'MILD' | 'MODERATE' | 'SEVERE';

/** Unit a glucose reading was entered in (backend converts mg/dL → mmol/L). */
export type GlucoseUnitValue = 'MMOL_L' | 'MG_DL';

/** Mirrors the backend HypoglycemiaEventResponse DTO. */
export interface HypoglycemiaEvent {
  id: string;
  visitId: string;
  visitNumber: string | null;
  patientName: string | null;
  currentZone: string | null;
  currentBedLabel: string | null;
  detectedAt: string;
  glucoseLevel: number | null;
  triggerReason: string;
  severity: HypoglycemiaSeverityValue | string;
  glucoseSource: string | null;
  neonatal: boolean;
  detectedByName: string | null;
  recheckDueAt: string | null;
  treatmentGiven: string | null;
  treatmentGivenAt: string | null;
  treatmentGivenByName: string | null;
  repeatGlucoseLevel: number | null;
  repeatGlucoseAt: string | null;
  resolved: boolean;
  resolvedAt: string | null;
  resolvedByName: string | null;
  notes: string | null;
  createdAt: string;
}

/** Mirrors the backend HypoglycemiaCheckResponse (returned by the enforce/check call). */
export interface HypoglycemiaCheckResponse {
  visitId: string;
  requiresCheck: boolean;
  checkMandatory: boolean;
  glucoseValue: number | null;
  isHypoglycemic: boolean;
  severity: string;
  treatmentProtocol: string | null;
  triggerReasons: string[];
  eventId?: string | null;
}

export const hypoglycemiaApi = {
  // Trigger a glucose-check enforcement for a visit (backend path is /check, not /enforce).
  enforce: (visitId: string) =>
    post<HypoglycemiaCheckResponse>(`/hypoglycemia/check/${visitId}`),
  // Body field is `treatment` (was wrongly `treatmentGiven`); path is /treatment (was /treat).
  recordTreatment: (id: string, data: { treatment: string; treatedByName?: string }) =>
    put<HypoglycemiaEvent>(`/hypoglycemia/${id}/treatment`, data),
  // Body field is `glucoseLevel` (was wrongly `repeatGlucoseLevel`); `unit`
  // lets a mg/dL glucometer reading be converted server-side (default mmol/L).
  recordRepeatGlucose: (id: string, data: { glucoseLevel: number; unit?: GlucoseUnitValue }) =>
    put<HypoglycemiaEvent>(`/hypoglycemia/${id}/repeat-glucose`, data),
  resolve: (id: string) => put<HypoglycemiaEvent>(`/hypoglycemia/${id}/resolve`),
  getForVisit: (visitId: string) => get<HypoglycemiaEvent[]>(`/hypoglycemia/visit/${visitId}`),
  // Backend path is /active (was wrongly /unresolved → the dashboard 404'd, always empty).
  getUnresolved: (hospitalId: string) =>
    get<HypoglycemiaEvent[]>(`/hypoglycemia/hospital/${hospitalId}/active`),
};
