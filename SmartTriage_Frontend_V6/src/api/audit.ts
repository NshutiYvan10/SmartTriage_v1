import { get, downloadBlob } from './client';

/** A persisted, server-backed audit-log entry (see backend module/audit). */
export interface AuditLogEntry {
  id: string;
  timestamp: string;
  actorUserId: string | null;
  actorName: string;
  actorRole: string | null;
  hospitalId: string | null;
  /** V107 patient linkage — which visit/patient the action touched (null for non-clinical actions). */
  visitId: string | null;
  patientId: string | null;
  visitNumber: string | null;
  patientName: string | null;
  httpMethod: string;
  path: string;
  action: string;
  statusCode: number | null;
  outcome: string; // SUCCESS | FAILED
}

interface PageResp<T> {
  content: T[];
  totalElements: number;
}

export const auditApi = {
  list: (
    hospitalId: string,
    opts: {
      page?: number; size?: number; from?: string; to?: string;
      /** Server-side filters (V107). */
      outcome?: 'SUCCESS' | 'FAILED'; actorUserId?: string; q?: string;
    } = {},
  ) => {
    const qs = new URLSearchParams({
      page: String(opts.page ?? 0),
      size: String(opts.size ?? 100),
    });
    if (opts.from) qs.set('from', opts.from);
    if (opts.to) qs.set('to', opts.to);
    if (opts.outcome) qs.set('outcome', opts.outcome);
    if (opts.actorUserId) qs.set('actorUserId', opts.actorUserId);
    if (opts.q) qs.set('q', opts.q);
    return get<PageResp<AuditLogEntry>>(`/audit/hospital/${hospitalId}?${qs.toString()}`);
  },
  /**
   * V107 — the incident timeline for one visit: every audited action that touched
   * this patient's encounter, oldest first, including FAILED/denied attempts.
   */
  visitTrail: (visitId: string) => get<AuditLogEntry[]>(`/audit/visit/${visitId}`),
  exportCsv: (hospitalId: string, from?: string, to?: string) => {
    const qs = new URLSearchParams();
    if (from) qs.set('from', from);
    if (to) qs.set('to', to);
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    return downloadBlob(`/audit/hospital/${hospitalId}/export${suffix}`, 'audit-log.csv');
  },
};
