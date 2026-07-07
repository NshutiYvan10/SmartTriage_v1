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
  /** Unit the repeat glucose was entered in (MMOL_L / MG_DL); level above is always mmol/L. */
  repeatGlucoseUnit: string | null;
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

/* ── Shared clinical helpers (single source for the dashboard + chart panel) ── */

/** Mirrors HypoglycemiaEnforcementEngine: recovered = ≥3.9 mmol/L adult/child, ≥2.6 neonate. */
export function isRecoveredGlucose(mmol: number, neonatal: boolean): boolean {
  return neonatal ? mmol >= 2.6 : mmol >= 3.9;
}

/**
 * The recheck protocol is complete only when a post-treatment repeat glucose is on
 * record AND it recovered. Anything else makes "Resolve" a protocol bypass that the
 * backend will 422 without a documented reason.
 */
export function protocolIncomplete(evt: HypoglycemiaEvent): boolean {
  return evt.repeatGlucoseLevel == null || !isRecoveredGlucose(evt.repeatGlucoseLevel, evt.neonatal);
}

/**
 * Age-appropriate treatment options. Neonates must NEVER be offered 50% dextrose —
 * the neonatal protocol is 2 mL/kg of 10% dextrose IV/IO then a D10 infusion
 * (mirrors HypoglycemiaEnforcementEngine.treatmentProtocol).
 */
export function treatmentOptionsFor(neonatal: boolean): string[] {
  if (neonatal) {
    return [
      'IV 10% dextrose 2 mL/kg bolus',
      'IV D10W infusion',
      'Buccal glucose gel',
      'Feed (breast/formula)',
    ];
  }
  return [
    'IV Dextrose 50% 50ml',
    'IV D10W infusion',
    'Pediatric 10% dextrose 5ml/kg',
    'Oral glucose (15–20g)',
  ];
}

/** Protocol guidance line shown above the treatment options (engine-aligned). */
export function protocolHintFor(neonatal: boolean): string {
  return neonatal
    ? 'NEONATAL: 2 mL/kg of 10% dextrose IV/IO bolus, then a 10% dextrose infusion. Recheck glucose in 15–30 minutes; involve pediatrics/neonatology. Do NOT use 50% dextrose.'
    : 'ADULT: 50 mL of 50% dextrose IV (or 200 mL of 10%). Conscious and able to swallow → 15–20 g oral fast-acting carbohydrate. PEDIATRIC: 5 mL/kg of 10% dextrose IV. Recheck glucose in 15 minutes.';
}

/** Countdown label for the mandatory post-treatment recheck clock. */
export function recheckCountdown(dueIso: string | null | undefined): { text: string; overdue: boolean } | null {
  if (!dueIso) return null;
  const mins = Math.round((new Date(dueIso).getTime() - Date.now()) / 60000);
  if (mins <= 0) return { text: `recheck overdue by ${Math.abs(mins)}m`, overdue: true };
  return { text: `recheck due in ${mins}m`, overdue: false };
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
  // Resolving is FREE only when the recheck protocol completed (repeat glucose on
  // record AND recovered). Otherwise the backend requires a documented reason (422
  // without one) — it is a protocol bypass that silences the recheck monitor.
  resolve: (id: string, reason?: string) =>
    put<HypoglycemiaEvent>(`/hypoglycemia/${id}/resolve`, reason ? { reason } : undefined),
  getForVisit: (visitId: string) => get<HypoglycemiaEvent[]>(`/hypoglycemia/visit/${visitId}`),
  // Backend path is /active (was wrongly /unresolved → the dashboard 404'd, always empty).
  // Optional zone → an on-shift clinician passes their covered zone and the
  // backend returns only that zone's events; oversight omits it for the full
  // hospital view. Zone scope is enforced server-side by the controller gate.
  getActive: (hospitalId: string, zone?: string) =>
    get<HypoglycemiaEvent[]>(
      `/hypoglycemia/hospital/${hospitalId}/active${zone ? `?zone=${zone}` : ''}`,
    ),
  // Back-compat alias — full hospital-wide list (used only where cross-zone
  // read authority is guaranteed).
  getUnresolved: (hospitalId: string) =>
    get<HypoglycemiaEvent[]>(`/hypoglycemia/hospital/${hospitalId}/active`),
};
