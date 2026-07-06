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
  /** Request origin (V108 forensics). */
  sourceIp: string | null;
  userAgent: string | null;
}

interface PageResp<T> {
  content: T[];
  totalElements: number;
}

/** Server-side audit filters (V107/V108) — shared by the list and the CSV export. */
export interface AuditFilterOpts {
  from?: string;
  to?: string;
  outcome?: 'SUCCESS' | 'FAILED';
  actorUserId?: string;
  q?: string;
  /** false hides /auth/* session housekeeping (login/refresh) server-side. */
  includeAuth?: boolean;
}

function filterParams(opts: AuditFilterOpts): URLSearchParams {
  const qs = new URLSearchParams();
  if (opts.from) qs.set('from', opts.from);
  if (opts.to) qs.set('to', opts.to);
  if (opts.outcome) qs.set('outcome', opts.outcome);
  if (opts.actorUserId) qs.set('actorUserId', opts.actorUserId);
  if (opts.q) qs.set('q', opts.q);
  if (opts.includeAuth !== undefined) qs.set('includeAuth', String(opts.includeAuth));
  return qs;
}

export const auditApi = {
  list: (
    hospitalId: string,
    opts: AuditFilterOpts & { page?: number; size?: number } = {},
  ) => {
    const qs = filterParams(opts);
    qs.set('page', String(opts.page ?? 0));
    qs.set('size', String(opts.size ?? 50));
    return get<PageResp<AuditLogEntry>>(`/audit/hospital/${hospitalId}?${qs.toString()}`);
  },
  /**
   * V107 — the incident timeline for one visit: every audited action that touched
   * this patient's encounter, oldest first, including FAILED/denied attempts.
   */
  visitTrail: (visitId: string) => get<AuditLogEntry[]>(`/audit/visit/${visitId}`),
  /** CSV export — honours the SAME filters as the list (WYSIWYG export). */
  exportCsv: (hospitalId: string, opts: AuditFilterOpts = {}) => {
    const qs = filterParams(opts);
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    return downloadBlob(`/audit/hospital/${hospitalId}/export${suffix}`, 'audit-log.csv');
  },
};
