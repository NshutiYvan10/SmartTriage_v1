/* ════════════════════════════════════════════════════════════════════
   PatientChronicConditionsPanel — Workflow 2 refinement.

   Structured replacement for the free-text Chronic Conditions
   textarea on the patient profile. Mirrors PatientAllergiesPanel:

     • searchable, categorized dropdown of curated conditions
       (HTN / T2DM / CKD / SCD …) with free-text fallback,
     • status chips (ACTIVE / CONTROLLED / IN_REMISSION / RESOLVED),
     • free-text notes for stage / regimen / VL detail,
     • optional onset date,
     • per-row Resolve button (DOCTOR only — backend enforces),
     • idempotency: re-recording the same name returns the existing
       row instead of creating a duplicate.

   The legacy Patient.chronicConditions free-text stays as a
   fallback for un-migrated patients; the safety engine prefers
   the structured rows when present (see chronicConditionCatalog
   helpers + renal-risk / teratogen check rewiring).
   ════════════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  HeartPulse, Plus, Search, X, Loader2, Calendar,
  CheckCircle2, AlertTriangle, Trash2,
} from 'lucide-react';
import { patientChronicConditionApi } from '@/api/patientChronicConditions';
import {
  CHRONIC_CONDITION_STATUSES,
  type ChronicConditionStatus,
  type PatientChronicConditionResponse,
  type RecordChronicConditionRequest,
} from '@/api/types';
import {
  CHRONIC_CONDITION_CATALOG,
  searchCatalog,
  type ChronicConditionCatalogEntry,
} from '@/utils/chronicConditionCatalog';
import { useAuthStore } from '@/store/authStore';

interface Props {
  patientId: string;
  /** Show the inline "+ Add" form. Read-only surfaces pass false. */
  editable?: boolean;
  onCountChange?: (count: number) => void;
}

function fmtIsoDate(iso: string | null): string {
  if (!iso) return '';
  try { return new Date(iso).toLocaleDateString(); } catch { return iso; }
}

function statusMeta(s: ChronicConditionStatus) {
  return CHRONIC_CONDITION_STATUSES.find((row) => row.value === s)
    ?? CHRONIC_CONDITION_STATUSES[0];
}

export function PatientChronicConditionsPanel({
  patientId, editable = false, onCountChange,
}: Props) {
  const userName = useAuthStore((s) => s.user?.fullName || 'unknown');
  const userRole = useAuthStore((s) => s.user?.role);

  const [rows, setRows] = useState<PatientChronicConditionResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const refresh = useCallback(async () => {
    if (!patientId) return;
    setLoading(true);
    setErr(null);
    try {
      const data = await patientChronicConditionApi.list(patientId);
      setRows(data);
      onCountChange?.(data.length);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load conditions');
    } finally {
      setLoading(false);
    }
  }, [patientId, onCountChange]);

  useEffect(() => { void refresh(); }, [refresh]);

  const handleAdded = useCallback((created: PatientChronicConditionResponse) => {
    setRows((prev) => {
      const next = prev.filter((r) => r.id !== created.id);
      const out = [created, ...next];
      onCountChange?.(out.length);
      return out;
    });
    setAdding(false);
  }, [onCountChange]);

  // Resolve flow
  const [resolvingId, setResolvingId] = useState<string | null>(null);
  const [resolveReason, setResolveReason] = useState('');
  const [resolveError, setResolveError] = useState<string | null>(null);
  const [resolveSubmitting, setResolveSubmitting] = useState(false);

  const handleResolve = useCallback(async (conditionId: string) => {
    setResolveSubmitting(true);
    setResolveError(null);
    try {
      await patientChronicConditionApi.resolve(conditionId, {
        reason: resolveReason.trim(),
        resolvedByName: userName,
      });
      // RESOLVED rows fall out of the active list — drop locally.
      setRows((prev) => {
        const next = prev.filter((r) => r.id !== conditionId);
        onCountChange?.(next.length);
        return next;
      });
      setResolvingId(null);
      setResolveReason('');
    } catch (e) {
      setResolveError(e instanceof Error ? e.message : 'Resolve failed');
    } finally {
      setResolveSubmitting(false);
    }
  }, [resolveReason, userName, onCountChange]);

  const canResolve = userRole === 'DOCTOR';

  return (
    <div className="space-y-2">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="text-[10px] font-bold uppercase tracking-wider text-slate-500 inline-flex items-center gap-1.5">
          <HeartPulse className="w-3.5 h-3.5 text-amber-600" />
          Structured chronic conditions
          {rows.length > 0 && (
            <span
              className="ml-1 inline-flex items-center justify-center min-w-[18px] h-[18px] px-1.5 text-[10px] font-bold rounded-lg text-amber-600"
              style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
            >
              {rows.length}
            </span>
          )}
        </div>
        {editable && !adding && (
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="inline-flex items-center gap-1 px-2 py-1 rounded-xl bg-cyan-600 hover:bg-cyan-700 text-white text-[10px] font-bold transition-colors"
            aria-label="Add chronic condition"
          >
            <Plus className="w-3 h-3" /> Add
          </button>
        )}
      </div>

      {err && (
        <div className="rounded-md border border-red-200 bg-red-50 px-2 py-1.5 text-[11px] text-red-700 flex items-start gap-1.5">
          <AlertTriangle className="w-3 h-3 mt-0.5 flex-shrink-0" />
          <span>{err}</span>
        </div>
      )}
      {loading && rows.length === 0 && (
        <div className="text-[11px] text-slate-500 inline-flex items-center gap-1.5">
          <Loader2 className="w-3 h-3 animate-spin" /> Loading conditions…
        </div>
      )}

      {adding && (
        <AddConditionForm
          recordedByName={userName}
          onCancel={() => setAdding(false)}
          onCreated={handleAdded}
          onSubmit={(req) => patientChronicConditionApi.record(patientId, req)}
        />
      )}

      {rows.length === 0 && !loading && !adding && (
        <div className="text-[11px] text-slate-500 italic">
          No structured conditions on file.
        </div>
      )}

      <ul className="space-y-1.5">
        {rows.map((c) => {
          const meta = statusMeta(c.status);
          const isResolving = resolvingId === c.id;
          return (
            <li key={c.id} className={`rounded-md border p-2 ${meta.tint}`}>
              <div className="flex items-start gap-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-bold">{c.conditionName}</span>
                    {c.conditionCode && (
                      <span className="text-[9px] font-mono uppercase tracking-wider px-1.5 py-0.5 rounded bg-white/60 border border-current">
                        {c.conditionCode}
                      </span>
                    )}
                    <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-white/60 border border-current">
                      {meta.label}
                    </span>
                  </div>
                  {c.notes && (
                    <div className="text-[11px] mt-0.5 text-slate-700">
                      {c.notes}
                    </div>
                  )}
                  <div className="text-[10px] text-slate-500 mt-1 flex items-center gap-2 flex-wrap">
                    {c.onsetDate && (
                      <span className="inline-flex items-center gap-0.5">
                        <Calendar className="w-2.5 h-2.5" /> {fmtIsoDate(c.onsetDate)}
                      </span>
                    )}
                    {c.recordedByName && <span>by {c.recordedByName}</span>}
                  </div>
                </div>
                {editable && canResolve && !isResolving && (
                  <button
                    type="button"
                    onClick={() => { setResolvingId(c.id); setResolveReason(''); setResolveError(null); }}
                    className="text-[10px] text-slate-500 hover:text-red-700 inline-flex items-center gap-0.5"
                    title="Mark as resolved"
                  >
                    <Trash2 className="w-3 h-3" /> Resolve
                  </button>
                )}
              </div>

              {isResolving && (
                <div className="mt-2 rounded-md bg-white/70 border border-red-200 p-2 space-y-2">
                  <textarea
                    rows={2}
                    placeholder="Reason for resolving (e.g. confirmed not a true diagnosis, condition cured)"
                    value={resolveReason}
                    onChange={(e) => setResolveReason(e.target.value)}
                    className="w-full px-2 py-1 text-[11px] rounded border border-red-200 outline-none"
                  />
                  {resolveError && (
                    <div className="text-[10px] text-red-700">{resolveError}</div>
                  )}
                  <div className="flex items-center gap-2 justify-end">
                    <button
                      type="button"
                      onClick={() => { setResolvingId(null); setResolveReason(''); setResolveError(null); }}
                      className="px-2 py-1 text-[10px] font-bold text-slate-500 hover:text-slate-700"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      disabled={resolveReason.trim().length < 5 || resolveSubmitting}
                      onClick={() => handleResolve(c.id)}
                      className="inline-flex items-center gap-1 px-2 py-1 rounded-xl text-[10px] font-bold bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {resolveSubmitting && <Loader2 className="w-3 h-3 animate-spin" />}
                      Confirm resolve
                    </button>
                  </div>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/* ════════════════════════════════════════════════════════════════════
   AddConditionForm — inline add UI used by the panel above.
   ════════════════════════════════════════════════════════════════════ */

function AddConditionForm({
  recordedByName, onCancel, onCreated, onSubmit,
}: {
  recordedByName: string;
  onCancel: () => void;
  onCreated: (created: PatientChronicConditionResponse) => void;
  onSubmit: (req: RecordChronicConditionRequest) => Promise<PatientChronicConditionResponse>;
}) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<ChronicConditionCatalogEntry | null>(null);
  const [showPicker, setShowPicker] = useState(false);

  const [status, setStatus] = useState<ChronicConditionStatus>('ACTIVE');
  const [notes, setNotes] = useState('');
  const [onsetDate, setOnsetDate] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const results = useMemo(() => searchCatalog(query), [query]);

  // Group by category for the dropdown.
  const grouped = useMemo(() => {
    const m = new Map<string, ChronicConditionCatalogEntry[]>();
    for (const r of results) {
      const list = m.get(r.category) ?? [];
      list.push(r);
      m.set(r.category, list);
    }
    return Array.from(m.entries());
  }, [results]);

  const canSubmit =
    (selected != null || query.trim().length >= 2) && !submitting;

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const req: RecordChronicConditionRequest = {
        conditionName: selected?.label ?? query.trim(),
        conditionCode: selected?.code,
        status,
        notes: notes.trim() || undefined,
        onsetDate: onsetDate || undefined,
        recordedByName,
      };
      const created = await onSubmit(req);
      onCreated(created);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to record condition');
    } finally {
      setSubmitting(false);
    }
  }, [canSubmit, selected, query, status, notes, onsetDate, recordedByName, onSubmit, onCreated]);

  return (
    <div className="rounded-md border border-emerald-200 bg-emerald-50/50 p-2.5 space-y-2">
      <div className="flex items-center justify-between">
        <div className="text-[10px] font-bold uppercase tracking-wider text-emerald-700 inline-flex items-center gap-1">
          <Plus className="w-3 h-3" /> Add chronic condition
        </div>
        <button type="button" onClick={onCancel} className="text-slate-500 hover:text-slate-700" aria-label="Cancel">
          <X className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Condition picker — searchable catalog with free-text fallback */}
      <div>
        <label className="block text-[9px] font-bold uppercase tracking-wider mb-1 text-slate-600">
          Condition <span className="text-slate-400 normal-case font-normal">(pick from catalog or type free text)</span>
        </label>
        {selected ? (
          <div className="rounded-md border border-cyan-300 bg-cyan-50 p-2 flex items-start gap-2">
            <HeartPulse className="w-3.5 h-3.5 text-cyan-600 mt-0.5" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-1.5 flex-wrap">
                <span className="text-xs font-bold">{selected.label}</span>
                <span className="text-[9px] font-mono uppercase tracking-wider px-1 py-0.5 rounded bg-slate-200 text-slate-700">
                  {selected.code}
                </span>
                <span className="text-[9px] uppercase tracking-wider text-slate-500">
                  {selected.category}
                </span>
              </div>
              {selected.help && (
                <p className="text-[10px] text-slate-600 mt-0.5">{selected.help}</p>
              )}
            </div>
            <button
              type="button"
              onClick={() => { setSelected(null); setQuery(''); }}
              className="text-slate-400 hover:text-slate-600"
            >
              <X className="w-3 h-3" />
            </button>
          </div>
        ) : (
          <div className="relative">
            <Search className="w-3.5 h-3.5 absolute left-2 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              value={query}
              onChange={(e) => { setQuery(e.target.value); setShowPicker(true); }}
              onFocus={() => setShowPicker(true)}
              placeholder="e.g. hypertension, T2DM, CKD, sickle cell…"
              className="w-full pl-7 pr-2 py-1.5 text-xs rounded border border-slate-300 outline-none"
            />
            {showPicker && results.length > 0 && (
              <div className="absolute z-10 mt-1 w-full max-h-72 overflow-y-auto rounded border border-slate-200 bg-white shadow-md">
                {grouped.map(([cat, entries]) => (
                  <div key={cat} className="py-1">
                    <div className="px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider text-slate-400 bg-slate-50">
                      {cat}
                    </div>
                    {entries.map((e) => (
                      <button
                        key={e.code}
                        type="button"
                        onClick={() => { setSelected(e); setShowPicker(false); }}
                        className="w-full text-left px-2 py-1.5 hover:bg-cyan-50 border-b border-slate-100 last:border-0"
                      >
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-bold flex-1">{e.label}</span>
                          <span className="text-[9px] font-mono text-slate-500">{e.code}</span>
                        </div>
                        {e.help && (
                          <div className="text-[10px] text-slate-500">{e.help}</div>
                        )}
                      </button>
                    ))}
                  </div>
                ))}
              </div>
            )}
            {showPicker && results.length === 0 && query.trim().length >= 2 && (
              <p className="absolute z-10 mt-1 w-full px-2 py-2 rounded border border-slate-200 bg-white text-[10px] text-slate-500 text-center shadow-md">
                No catalog match — submit "{query.trim()}" as free text below.
              </p>
            )}
          </div>
        )}
      </div>

      {/* Status chips */}
      <div>
        <label className="block text-[9px] font-bold uppercase tracking-wider mb-1 text-slate-600">
          Status <span className="text-red-600">*</span>
        </label>
        <div className="grid grid-cols-4 gap-1">
          {CHRONIC_CONDITION_STATUSES.map((row) => {
            const active = status === row.value;
            return (
              <button
                key={row.value}
                type="button"
                onClick={() => setStatus(row.value)}
                title={row.description}
                className={`px-1.5 py-1 text-[10px] font-bold rounded border transition-all ${
                  active ? row.tint : 'bg-white border-slate-200 text-slate-500 hover:bg-slate-50'
                }`}
              >
                {row.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Notes */}
      <div>
        <label className="block text-[9px] font-bold uppercase tracking-wider mb-1 text-slate-600">
          Notes <span className="text-slate-400 normal-case font-normal">(stage / regimen / control)</span>
        </label>
        <input
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="e.g. Stage 3b CKD, on losartan; T2DM on metformin"
          className="w-full px-2 py-1.5 text-xs rounded border border-slate-300 outline-none"
        />
      </div>

      {/* Onset */}
      <div>
        <label className="block text-[9px] font-bold uppercase tracking-wider mb-1 text-slate-600">
          Onset date <span className="text-slate-400 normal-case font-normal">(optional)</span>
        </label>
        <input
          type="date"
          value={onsetDate}
          onChange={(e) => setOnsetDate(e.target.value)}
          className="w-full px-2 py-1.5 text-xs rounded border border-slate-300 outline-none"
        />
      </div>

      {error && <div className="text-[11px] text-red-700">{error}</div>}

      <div className="flex items-center gap-2 justify-end pt-1">
        <button
          type="button"
          onClick={onCancel}
          className="px-2 py-1 text-[11px] font-bold text-slate-500 hover:text-slate-700"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="inline-flex items-center gap-1 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-600 hover:bg-cyan-700 text-white disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting && <Loader2 className="w-3 h-3 animate-spin" />}
          Save condition
        </button>
      </div>
    </div>
  );
}

/* Silence the unused-import warning when CHRONIC_CONDITION_CATALOG
   itself is referenced elsewhere. */
void CHRONIC_CONDITION_CATALOG;
void CheckCircle2;
