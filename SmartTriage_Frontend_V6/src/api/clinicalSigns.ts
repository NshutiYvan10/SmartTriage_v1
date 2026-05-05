/* ── Clinical sign event log API ──
 *
 * Event-log model: every change to a clinical sign is a row, current state
 * is the latest event per (visit, sign_code). Drives the Clinical Signs tab
 * on the doctor's chart. Each visit auto-bootstraps a baseline timeline
 * from the triage record's positive flags.
 */
import { get, post } from './client';

export type ClinicalSignStatus =
  | 'PRESENT'
  | 'ABSENT'
  | 'IMPROVING'
  | 'WORSENING'
  | 'UNKNOWN';

export type ClinicalSignCategory =
  | 'EMERGENCY'
  | 'PEDIATRIC_EMERGENCY'
  | 'MSAT_VU'
  | 'MSAT_URG'
  | 'SPECIAL';

export interface ClinicalSignEventResponse {
  id: string;
  visitId: string;
  patientId: string;
  signCode: string;
  signCategory: ClinicalSignCategory;
  status: ClinicalSignStatus;
  /** Carries glucose for convulsions/coma/DKA discriminators; null for others. */
  numericValue: number | null;
  notes: string | null;
  recordedAt: string;
  recordedById: string | null;
  recordedByName: string | null;
  /** True for the auto-recorded triage baseline events. */
  isBaseline: boolean;
}

export interface RecordClinicalSignEntry {
  signCode: string;
  status: ClinicalSignStatus;
  /** Optional, only meaningful for glucose-carrying signs. */
  numericValue?: number | null;
  notes?: string | null;
}

export interface RecordClinicalSignsBatchRequest {
  visitId: string;
  events: RecordClinicalSignEntry[];
  /** Optional explicit observation timestamp; server defaults to NOW(). */
  recordedAt?: string;
  /** Display-name fallback if the authenticated principal can't be resolved. */
  recordedByName?: string;
}

export const clinicalSignsApi = {
  /** Full chronological event history for a visit. */
  getHistory: (visitId: string) =>
    get<ClinicalSignEventResponse[]>(`/clinical-signs/visit/${visitId}`),

  /** Latest event per sign code — drives the "Current State" panel. */
  getCurrentState: (visitId: string) =>
    get<ClinicalSignEventResponse[]>(`/clinical-signs/visit/${visitId}/current`),

  /** Per-sign mini-timeline. */
  getSignHistory: (visitId: string, signCode: string) =>
    get<ClinicalSignEventResponse[]>(`/clinical-signs/visit/${visitId}/sign/${signCode}`),

  /**
   * Record one or more sign updates as a batch. All entries land on the
   * same recorded_at — useful when a doctor on a ward round updates several
   * signs at once and wants them grouped on the timeline.
   */
  recordBatch: (request: RecordClinicalSignsBatchRequest) =>
    post<ClinicalSignEventResponse[]>('/clinical-signs', request),
};
