/* ── Cross-hospital patient identity API (Phase 1/2/3) ──
 *
 * Surfaces the shared-identity backend to the UI: an always-available cross-hospital SAFETY
 * SUMMARY, the consent-gated DEEP record (with an emergency break-the-glass override), and
 * data-sharing CONSENT capture. All keyed on the patient's national ID — the endpoints span
 * hospitals deliberately and are role-gated + audited server-side.
 */
import { get, patch, post, put } from './client';
import type { Page } from './types';

// ── Safety summary (Phase 1) — always available ──
export interface CrossHospitalSafetyItem {
  detail: string;
  sourceHospital: string;
}

export interface CrossHospitalSafetySummary {
  found: boolean;
  nationalId: string | null;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  bloodType: string | null;
  emergencyContactName: string | null;
  emergencyContactPhone: string | null;
  linkedHospitalCount: number;
  sourceHospitals: string[] | null;
  allergies: CrossHospitalSafetyItem[] | null;
  chronicConditions: CrossHospitalSafetyItem[] | null;
  activeMedications: CrossHospitalSafetyItem[] | null;
}

// ── Deep record (Phase 2) — consent- or break-glass-gated ──
export type AccessBasis = 'CONSENT' | 'BREAK_THE_GLASS' | 'DENIED';

export interface CrossHospitalVisitSummary {
  visitNumber: string | null;
  arrivalTime: string | null;
  status: string | null;
  diagnoses: string[] | null;
  dischargeSummaries: string[] | null;
  criticalLabs: string[] | null;
  keyNotes: string[] | null;
}

export interface CrossHospitalHospitalSection {
  sourceHospital: string;
  truncated: boolean;
  visits: CrossHospitalVisitSummary[] | null;
}

export interface CrossHospitalDeepRecord {
  found: boolean;
  accessGranted: boolean;
  accessBasis: AccessBasis;
  consentRequired: boolean;
  nationalId: string | null;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  linkedHospitalCount: number;
  hospitals: CrossHospitalHospitalSection[] | null;
  medicationHistory: string[] | null;
}

// ── Data-sharing consent (Phase 2) ──
export type DataSharingConsentStatus = 'GRANTED' | 'DENIED' | 'WITHDRAWN';
export type DataSharingScope = 'FULL_RECORD';
export type ConsentGrantor =
  | 'PATIENT'
  | 'PARENT_OR_GUARDIAN'
  | 'NEXT_OF_KIN'
  | 'LEGAL_SURROGATE'
  | 'COURT_ORDER'
  | 'EMERGENCY_NO_CONSENT_REQUIRED';

export interface DataSharingConsent {
  id: string;
  personIdentityId: string;
  status: DataSharingConsentStatus;
  scope: DataSharingScope;
  consentGrantor: ConsentGrantor | null;
  grantorName: string | null;
  grantorRelationship: string | null;
  obtainedByName: string | null;
  obtainedByRole: string | null;
  obtainedAt: string | null;
  withdrawnByName: string | null;
  withdrawnAt: string | null;
  withdrawalReason: string | null;
  notes: string | null;
  createdAt: string | null;
}

export interface RecordDataSharingConsentRequest {
  status: DataSharingConsentStatus; // GRANTED | DENIED (WITHDRAWN is rejected; use withdraw)
  scope?: DataSharingScope;
  consentGrantor: ConsentGrantor;
  grantorName?: string;
  grantorRelationship?: string;
  notes?: string;
}

const enc = encodeURIComponent;

export const crossHospitalApi = {
  /** Always-available minimal safety summary across SmartTriage hospitals for a national ID. */
  getSafetySummary: (nationalId: string) =>
    get<CrossHospitalSafetySummary>(`/patient-identity/safety-summary?nationalId=${enc(nationalId)}`),

  /** Same safety summary resolved by RFID card UID (V95) — for the tap-to-identify flow; works
   *  for card-anchored patients with no national ID. */
  getSafetySummaryByCard: (cardId: string) =>
    get<CrossHospitalSafetySummary>(`/patient-identity/safety-summary-by-card?cardId=${enc(cardId)}`),

  /**
   * Consent-gated deep clinical-history summary. Pass a non-blank `breakTheGlassReason` to perform
   * an emergency override when no consent is on file — it is recorded forensically and audited.
   */
  getDeepRecord: (nationalId: string, breakTheGlassReason?: string) => {
    const reason = breakTheGlassReason && breakTheGlassReason.trim()
      ? `&breakTheGlassReason=${enc(breakTheGlassReason.trim())}` : '';
    return get<CrossHospitalDeepRecord>(
      `/patient-identity/deep-record?nationalId=${enc(nationalId)}${reason}`);
  },
};

export const dataSharingConsentApi = {
  record: (nationalId: string, body: RecordDataSharingConsentRequest) =>
    post<DataSharingConsent>(`/data-sharing-consents/national-id/${enc(nationalId)}`, body),

  withdraw: (id: string, reason: string) =>
    put<DataSharingConsent>(`/data-sharing-consents/${id}/withdraw`, { reason }),

  history: (nationalId: string) =>
    get<DataSharingConsent[]>(`/data-sharing-consents/national-id/${enc(nationalId)}`),
};

// ── Break-the-glass governance feed (Phase 3) ──
export type GovernanceRange = '24h' | '7d' | '30d' | 'all';

export interface BreakTheGlassEvent {
  id: string;
  personIdentityId: string | null;
  maskedNationalId: string | null;
  actorUserId: string | null;
  actorName: string | null;
  actorRole: string | null;
  actorHospitalId: string | null;
  reason: string | null;
  priorConsentState: string | null; // NONE | DENIED | WITHDRAWN
  accessedAt: string | null;
  acknowledged: boolean;
  acknowledgedByName: string | null;
  acknowledgedAt: string | null;
  acknowledgmentNote: string | null;
}

export const governanceApi = {
  getBreakTheGlassEvents: (hospitalId: string, range: GovernanceRange = 'all', page = 0, size = 200) =>
    get<Page<BreakTheGlassEvent>>(
      `/break-the-glass-events/hospital/${enc(hospitalId)}?range=${range}&page=${page}&size=${size}`),

  acknowledgeBreakTheGlassEvent: (hospitalId: string, eventId: string, note?: string) => {
    const q = note && note.trim() ? `?note=${enc(note.trim())}` : '';
    return patch<BreakTheGlassEvent>(
      `/break-the-glass-events/hospital/${enc(hospitalId)}/${enc(eventId)}/acknowledge${q}`);
  },
};
