/* ── Alerts API ── */
import { get, patch } from './client';
import type { ClinicalAlertResponse, EdZone, Page } from './types';

export const alertApi = {
  getByVisit: (visitId: string, page = 0, size = 20) =>
    get<Page<ClinicalAlertResponse>>(`/alerts/visit/${visitId}?page=${page}&size=${size}`),

  getAll: (hospitalId: string, page = 0, size = 100) =>
    get<Page<ClinicalAlertResponse>>(`/alerts/hospital/${hospitalId}/all?page=${page}&size=${size}`),

  getUnacknowledged: (hospitalId: string, page = 0, size = 50) =>
    get<Page<ClinicalAlertResponse>>(`/alerts/hospital/${hospitalId}/unacknowledged?page=${page}&size=${size}`),

  getCritical: (hospitalId: string, page = 0, size = 50) =>
    get<Page<ClinicalAlertResponse>>(`/alerts/hospital/${hospitalId}/critical?page=${page}&size=${size}`),

  /** Get alerts for a specific ED zone */
  getByZone: (hospitalId: string, zone: EdZone) =>
    get<ClinicalAlertResponse[]>(`/alerts/hospital/${hospitalId}/zone/${zone}`),

  /** Get alerts targeted at a specific doctor */
  getByDoctor: (doctorId: string) =>
    get<ClinicalAlertResponse[]>(`/alerts/doctor/${doctorId}`),

  /**
   * Phase 14 — server-side filter for the Override Audit dashboard.
   * `range` accepts "24h" | "7d" | "30d" | "all". Default page size is
   * 200 — large enough that a typical hospital's recent overrides come
   * in one round-trip but small enough that a malformed query can't
   * blow up the response.
   */
  getSafetyOverrides: (
    hospitalId: string,
    range: '24h' | '7d' | '30d' | 'all' = 'all',
    page = 0,
    size = 200,
  ) =>
    get<Page<ClinicalAlertResponse>>(
      `/alerts/hospital/${hospitalId}/safety-overrides?range=${range}&page=${page}&size=${size}`,
    ),

  acknowledge: (alertId: string) =>
    patch<ClinicalAlertResponse>(`/alerts/${alertId}/acknowledge`),
};
