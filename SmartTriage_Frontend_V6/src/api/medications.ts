/* ── Medications API ── */
import { get, post, patch } from './client';
import type {
  PrescribeMedicationRequest,
  AdministerMedicationRequest,
  CountersignMedicationRequest,
  MedicationResponse,
  MedicationDoseResponse,
  MedicationOrderAudit,
  ZoneMedicationBoard,
  EdZone,
  Page,
} from './types';

/**
 * A pre-computed, EDITABLE prescription suggestion (server-side, from the
 * same formulary the safety engine polices with). `rationale` carries the
 * arithmetic ("15 mg/kg × 14 kg = 210 mg"); `weightSource` flags
 * estimated weights loudly.
 */
export interface DoseSuggestionResponse {
  suggested: boolean;
  drugName: string;
  doseValue: number | null;
  doseUnit: string | null;
  route: string | null;
  intervalHours: number | null;
  prescriptionType: 'SCHEDULED' | 'ONE_TIME' | 'CONTINUOUS' | null;
  fluidName: string | null;
  volumeMl: number | null;
  rateMlPerHour: number | null;
  durationHours: number | null;
  weightUsedKg: number | null;
  weightSource: 'MEASURED' | 'ESTIMATED_BY_AGE' | 'NONE';
  rationale: string[];
  warnings: string[];
  note: string | null;
}

export const medicationApi = {
  /**
   * Editable dose suggestion for a formulary drug + this patient
   * (weight/age-aware, with the arithmetic in `rationale`). Pre-fill
   * only — the doctor confirms or edits; the safety engine still
   * validates the final submission.
   */
  suggest: (visitId: string, formularyId: string) =>
    get<DoseSuggestionResponse>(`/medications/suggest?visitId=${visitId}&formularyId=${formularyId}`),

  /** IV-fluid calculator: Holliday–Segar maintenance or weight-based bolus. */
  suggestFluid: (visitId: string, purpose: 'MAINTENANCE' | 'BOLUS') =>
    get<DoseSuggestionResponse>(`/medications/suggest-fluid?visitId=${visitId}&purpose=${purpose}`),

  prescribe: (data: PrescribeMedicationRequest) =>
    post<MedicationResponse>('/medications', data),

  administer: (id: string, data: AdministerMedicationRequest) =>
    patch<MedicationResponse>(`/medications/${id}/administer`, data),

  countersign: (id: string, data: CountersignMedicationRequest) =>
    patch<MedicationResponse>(`/medications/${id}/countersign`, data),

  hold: (id: string, reason: string) =>
    patch<MedicationResponse>(`/medications/${id}/hold?reason=${encodeURIComponent(reason)}`),

  cancel: (id: string, reason: string) =>
    patch<MedicationResponse>(`/medications/${id}/cancel?reason=${encodeURIComponent(reason)}`),

  refuse: (id: string, reason: string) =>
    patch<MedicationResponse>(`/medications/${id}/refuse?reason=${encodeURIComponent(reason)}`),

  getById: (id: string) =>
    get<MedicationResponse>(`/medications/${id}`),

  getByVisit: (visitId: string, page = 0, size = 50) =>
    get<Page<MedicationResponse>>(`/medications/visit/${visitId}?page=${page}&size=${size}`),

  getAllByVisit: (visitId: string) =>
    get<MedicationResponse[]>(`/medications/visit/${visitId}/all`),

  /**
   * Patient-level medication history across all visits, newest first.
   * Drives the prescribing UI's "Reorder" feature so the doctor can copy
   * a past prescription into a new order with one tap.
   */
  getPatientHistory: (patientId: string) =>
    get<MedicationResponse[]>(`/medications/patient/${patientId}/history`),

  /**
   * Nurse medication queue (Workflow 3) — every PRESCRIBED med
   * across the hospital not yet administered. Sorted STAT first.
   */
  getQueue: (hospitalId: string) =>
    get<MedicationResponse[]>(`/medications/queue/${hospitalId}`),

  // ── Medication Management (V67) — dose-level workflow ──

  /** Charge-nurse approval of a high-alert order. */
  approve: (id: string, data?: { approvedByName?: string; note?: string }) =>
    post<MedicationResponse>(`/medications/${id}/approve`, data ?? {}),

  /** Un-hold a HELD order (fresh due dose is created). */
  resume: (id: string) => post<MedicationResponse>(`/medications/${id}/resume`, {}),

  /** Doctor stops the order — reason mandatory. */
  discontinue: (id: string, data: { reason: string; discontinuedByName?: string }) =>
    post<MedicationResponse>(`/medications/${id}/discontinue`, data),

  /** Discontinue-and-replace; returns the replacement order. */
  modify: (id: string, data: { reason: string; newOrder: PrescribeMedicationRequest }) =>
    post<MedicationResponse>(`/medications/${id}/modify`, data),

  /** Give a DUE dose (verification / witness / gates run server-side). */
  administerDose: (doseId: string, data: {
    administeredByName?: string; doseValue?: number; doseUnit?: string;
    witnessName?: string; notes?: string; override?: boolean; overrideJustification?: string;
  }) => post<MedicationDoseResponse>(`/medications/doses/${doseId}/administer`, data),

  /** Push a DUE dose forward (15 min – 12 h), reason mandatory. */
  delayDose: (doseId: string, data: { delayMinutes: number; reason: string }) =>
    post<MedicationDoseResponse>(`/medications/doses/${doseId}/delay`, data),

  /** Patient refused this dose — order stays live. */
  refuseDose: (doseId: string, data: { reason: string; recordedByName?: string }) =>
    post<MedicationDoseResponse>(`/medications/doses/${doseId}/refuse`, data),

  /** Record a PRN administration (interval / cap / vitals gate enforced). */
  recordPrnDose: (orderId: string, data: {
    prnReason: string; administeredByName?: string; doseValue?: number; doseUnit?: string;
    witnessName?: string; notes?: string; override?: boolean; overrideJustification?: string;
  }) => post<MedicationDoseResponse>(`/medications/${orderId}/prn-dose`, data),

  /** Continuous infusion lifecycle events. */
  startInfusion: (orderId: string, data?: {
    rateValue?: number; rateUnit?: string; recordedByName?: string; witnessName?: string;
  }) => post<MedicationDoseResponse>(`/medications/${orderId}/infusion/start`, data ?? {}),
  changeInfusionRate: (orderId: string, data: {
    rateValue: number; rateUnit?: string; recordedByName?: string; reason?: string;
  }) => post<MedicationDoseResponse>(`/medications/${orderId}/infusion/rate`, data),
  stopInfusion: (orderId: string, data: { reason: string; recordedByName?: string }) =>
    post<MedicationDoseResponse>(`/medications/${orderId}/infusion/stop`, data),

  /** Zone medication board — due/overdue, recent, PRN, infusions, approvals. */
  getBoard: (hospitalId: string, zone?: EdZone | null) =>
    get<ZoneMedicationBoard>(
      `/medications/board/${hospitalId}${zone ? `?zone=${zone}` : ''}`),

  /** Structured per-visit medication audit trail. */
  getVisitAudit: (visitId: string) =>
    get<MedicationOrderAudit[]>(`/medications/visit/${visitId}/audit`),
};
