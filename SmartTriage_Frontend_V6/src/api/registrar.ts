import { get, downloadBlob } from './client';

/**
 * Registrar reporting (R11) — operational front-desk reports. The intake log is an admissions
 * log keyed on arrival time (NOT a per-registrar attribution — Visit stores no registrar-actor
 * FK), the unidentified queue is the identity-reconciliation safety follow-up surface, and the
 * census is a live point-in-time snapshot. All hospital-scoped.
 */

export interface IntakeLogRow {
  visitNumber: string;
  arrivalTime: string;
  arrivalMode: string | null;
  status: string | null;
  patientName: string;
  ageYears: number | null;
  sex: string | null;
  zone: string | null;
  unidentified: boolean;
}

export interface UnidentifiedPatientRow {
  patientId: string;
  placeholderLabel: string | null;
  placeholderAssignedAt: string | null;
  hoursWaiting: number | null;
}

export interface CensusResponse {
  totalActive: number;
  byStatus: Record<string, number>;
  byZone: Record<string, number>;
  generatedAt: string;
}

export const registrarApi = {
  /** Intake log for a date window (ISO yyyy-MM-dd, inclusive of `to`). */
  getIntakeLog: (hospitalId: string, from: string, to: string) =>
    get<IntakeLogRow[]>(`/registrar-reports/hospital/${hospitalId}/intake-log?from=${from}&to=${to}`),
  /** Server-side CSV of the intake log. Returns blob + filename. */
  exportIntakeLogCsv: (hospitalId: string, from: string, to: string) =>
    downloadBlob(`/registrar-reports/hospital/${hospitalId}/intake-log/csv?from=${from}&to=${to}`, `intake-log_${from}_${to}.csv`),

  /** The unidentified-patient reconciliation queue (oldest placeholder first). */
  getUnidentified: (hospitalId: string) =>
    get<UnidentifiedPatientRow[]>(`/registrar-reports/hospital/${hospitalId}/unidentified`),
  /** Server-side CSV of the unidentified queue. Returns blob + filename. */
  exportUnidentifiedCsv: (hospitalId: string) =>
    downloadBlob(`/registrar-reports/hospital/${hospitalId}/unidentified/csv`, 'unidentified-patients.csv'),

  /** Point-in-time census: active visits grouped by status and by zone. */
  getCensus: (hospitalId: string) =>
    get<CensusResponse>(`/registrar-reports/hospital/${hospitalId}/census`),
};
