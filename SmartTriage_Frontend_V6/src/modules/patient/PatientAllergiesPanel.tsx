/* ════════════════════════════════════════════════════════════════════
   PatientAllergiesPanel — structured allergy capture & display.

   Workflow 2 / V58 replacement for the free-text textarea. Renders:

     · A severity-graded list of recorded allergies (chip per row).
     · A searchable allergen picker that hits the drug formulary
       catalog and falls back to free-text for non-drug allergens
       (shellfish, latex, etc.).
     · A severity dropdown (MILD / MODERATE / SEVERE / ANAPHYLAXIS /
       UNKNOWN) — drives the prescribe-time safety dialog flavour.
     · A reaction free-text input so future clinicians see what
       happened to the patient last time (e.g. "facial swelling").
     · A refute action that flips the row to REFUTED without
       hard-deleting — refute is itself an audit event.

   The legacy free-text `Patient.knownAllergies` column stays in
   place on the parent card for transition; this panel manages the
   structured list independently. New entries should go here.
   ════════════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ShieldAlert, Plus, Search, X, Loader2, Calendar, FlaskConical,
  CheckCircle2, AlertTriangle, Trash2,
} from 'lucide-react';
import { patientAllergyApi } from '@/api/patientAllergies';
import { medsafetyApi } from '@/api/medsafety';
import type { DrugFormulary } from '@/api/medsafety';
import {
  ALLERGY_SEVERITIES,
  type AllergySeverity,
  type PatientAllergyResponse,
  type RecordAllergyRequest,
} from '@/api/types';
import { useAuthStore } from '@/store/authStore';

interface Props {
  patientId: string;
  /** Show the inline "+ Add allergy" form. Read-only surfaces (e.g.
   *  audit views) pass false to render the list only. */
  editable?: boolean;
  /** Notifies the parent that the active-allergy count changed so a
   *  badge can re-render in the surrounding card. */
  onCountChange?: (count: number) => void;
}

function fmtIsoDate(iso: string | null): string {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleDateString();
  } catch { return iso; }
}

function severityMeta(s: AllergySeverity) {
  return ALLERGY_SEVERITIES.find((row) => row.value === s)
    ?? ALLERGY_SEVERITIES[ALLERGY_SEVERITIES.length - 1];
}

export function PatientAllergiesPanel({ patientId, editable = false, onCountChange }: Props) {
  const userName = useAuthStore((s) => s.user?.fullName || 'unknown');
  const userRole = useAuthStore((s) => s.user?.role);

  const [allergies, setAllergies] = useState<PatientAllergyResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const [adding, setAdding] = useState(false);

  const refresh = useCallback(async () => {
    if (!patientId) return;
    setLoading(true);
    setErr(null);
    try {
      const rows = await patientAllergyApi.list(patientId);
      setAllergies(rows);
      onCountChange?.(rows.length);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load allergies');
    } finally {
      setLoading(false);
    }
  }, [patientId, onCountChange]);

  useEffect(() => { void refresh(); }, [refresh]);

  // ─── Add new ────────────────────────────────────────────────────────

  const handleAdded = useCallback((created: PatientAllergyResponse) => {
    setAllergies((prev) => {
      // Deduplicate: if the backend's idempotency returned an existing
      // row, replace it in place; otherwise prepend.
      const next = prev.filter((r) => r.id !== created.id);
      const out = [created, ...next];
      onCountChange?.(out.length);
      return out;
    });
    setAdding(false);
  }, [onCountChange]);

  // ─── Refute ────────────────────────────────────────────────────────

  const [refutingId, setRefutingId] = useState<string | null>(null);
  const [refuteReason, setRefuteReason] = useState('');
  const [refuteError, setRefuteError] = useState<string | null>(null);
  const [refuteSubmitting, setRefuteSubmitting] = useState(false);

  const handleRefute = useCallback(async (allergyId: string) => {
    setRefuteSubmitting(true);
    setRefuteError(null);
    try {
      await patientAllergyApi.refute(allergyId, {
        reason: refuteReason.trim(),
        refutedByName: userName,
      });
      // Refute removes the row from the active list (list endpoint
      // filters REFUTED). Update locally without a full refetch.
      setAllergies((prev) => {
        const next = prev.filter((r) => r.id !== allergyId);
        onCountChange?.(next.length);
        return next;
      });
      setRefutingId(null);
      setRefuteReason('');
    } catch (e) {
      setRefuteError(e instanceof Error ? e.message : 'Refute failed');
    } finally {
      setRefuteSubmitting(false);
    }
  }, [refuteReason, userName, onCountChange]);

  const canRefute = userRole === 'DOCTOR';

  // ─── Render ────────────────────────────────────────────────────────

  return (
    <div className="space-y-2">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="text-[10px] font-bold uppercase tracking-wider text-slate-500 inline-flex items-center gap-1.5">
          <ShieldAlert className="w-3.5 h-3.5 text-red-600" />
          Structured allergies
          {allergies.length > 0 && (
            <span
              className="ml-1 inline-flex items-center justify-center min-w-[18px] h-[18px] px-1.5 text-[10px] font-bold rounded-lg text-red-600"
              style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}
            >
              {allergies.length}
            </span>
          )}
        </div>
        {editable && !adding && (
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="inline-flex items-center gap-1 px-2 py-1 rounded-md bg-emerald-50 text-emerald-700 text-[10px] font-bold hover:bg-emerald-100 transition-colors"
            aria-label="Add allergy"
          >
            <Plus className="w-3 h-3" /> Add
          </button>
        )}
      </div>

      {/* Error / loading */}
      {err && (
        <div className="rounded-md border border-red-200 bg-red-50 px-2 py-1.5 text-[11px] text-red-700 flex items-start gap-1.5">
          <AlertTriangle className="w-3 h-3 mt-0.5 flex-shrink-0" />
          <span>{err}</span>
        </div>
      )}
      {loading && allergies.length === 0 && (
        <div className="text-[11px] text-slate-500 inline-flex items-center gap-1.5">
          <Loader2 className="w-3 h-3 animate-spin" /> Loading allergies…
        </div>
      )}

      {/* Add form */}
      {adding && (
        <AddAllergyForm
          recordedByName={userName}
          onCancel={() => setAdding(false)}
          onCreated={handleAdded}
          onSubmit={(req) => patientAllergyApi.record(patientId, req)}
        />
      )}

      {/* List */}
      {allergies.length === 0 && !loading && !adding && (
        <div className="text-[11px] text-slate-500 italic">
          No structured allergies on file.
        </div>
      )}

      <ul className="space-y-1.5">
        {allergies.map((a) => {
          const meta = severityMeta(a.severity);
          const isRefuting = refutingId === a.id;
          return (
            <li
              key={a.id}
              className={`rounded-md border p-2 ${meta.tint}`}
            >
              <div className="flex items-start gap-2">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-bold">{a.allergenName}</span>
                    <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-white/60 border border-current">
                      {meta.label}
                    </span>
                    {a.verificationStatus === 'CONFIRMED' && (
                      <span
                        className="text-[9px] font-bold uppercase tracking-wider px-2.5 py-0.5 rounded-lg text-emerald-600 inline-flex items-center gap-0.5"
                        style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}
                      >
                        <CheckCircle2 className="w-2.5 h-2.5" /> Confirmed
                      </span>
                    )}
                  </div>
                  {a.reaction && (
                    <div className="text-[11px] mt-0.5 text-slate-700">
                      Reaction: <span className="font-medium">{a.reaction}</span>
                    </div>
                  )}
                  <div className="text-[10px] text-slate-500 mt-1 flex items-center gap-2 flex-wrap">
                    {a.onsetDate && (
                      <span className="inline-flex items-center gap-0.5">
                        <Calendar className="w-2.5 h-2.5" /> {fmtIsoDate(a.onsetDate)}
                      </span>
                    )}
                    {a.recordedByName && <span>by {a.recordedByName}</span>}
                  </div>
                </div>
                {editable && canRefute && !isRefuting && (
                  <button
                    type="button"
                    onClick={() => { setRefutingId(a.id); setRefuteReason(''); setRefuteError(null); }}
                    className="text-[10px] text-slate-500 hover:text-red-700 inline-flex items-center gap-0.5"
                    title="Mark as refuted"
                  >
                    <Trash2 className="w-3 h-3" /> Refute
                  </button>
                )}
              </div>

              {/* Refute form (inline per row) */}
              {isRefuting && (
                <div className="mt-2 rounded-md bg-white/70 border border-red-200 p-2 space-y-2">
                  <textarea
                    rows={2}
                    placeholder="Reason for refuting (e.g. tested negative, was an intolerance)"
                    value={refuteReason}
                    onChange={(e) => setRefuteReason(e.target.value)}
                    className="w-full px-2 py-1 text-[11px] rounded border border-red-200 outline-none"
                  />
                  {refuteError && (
                    <div className="text-[10px] text-red-700">{refuteError}</div>
                  )}
                  <div className="flex items-center gap-2 justify-end">
                    <button
                      type="button"
                      onClick={() => { setRefutingId(null); setRefuteReason(''); setRefuteError(null); }}
                      className="px-2 py-1 text-[10px] font-bold text-slate-500 hover:text-slate-700"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      disabled={refuteReason.trim().length < 5 || refuteSubmitting}
                      onClick={() => handleRefute(a.id)}
                      className="inline-flex items-center gap-1 px-2 py-1 rounded-xl text-[10px] font-bold bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {refuteSubmitting && <Loader2 className="w-3 h-3 animate-spin" />}
                      Confirm refute
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
   AddAllergyForm — inline add UI used by the panel above.
   ════════════════════════════════════════════════════════════════════ */

function AddAllergyForm({
  recordedByName,
  onCancel,
  onCreated,
  onSubmit,
}: {
  recordedByName: string;
  onCancel: () => void;
  onCreated: (created: PatientAllergyResponse) => void;
  onSubmit: (req: RecordAllergyRequest) => Promise<PatientAllergyResponse>;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<DrugFormulary[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<DrugFormulary | null>(null);
  const searchSeq = useRef(0);

  const [severity, setSeverity] = useState<AllergySeverity>('MODERATE');
  const [reaction, setReaction] = useState('');
  const [onsetDate, setOnsetDate] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Debounced formulary search.
  useEffect(() => {
    if (selected) return;
    const trimmed = query.trim();
    if (trimmed.length < 2) {
      setResults([]);
      setSearching(false);
      return;
    }
    const seq = ++searchSeq.current;
    setSearching(true);
    const timer = setTimeout(() => {
      medsafetyApi.searchFormulary(trimmed)
        .then((rows) => {
          if (seq !== searchSeq.current) return;
          setResults(Array.isArray(rows) ? rows : []);
        })
        .catch(() => {
          if (seq !== searchSeq.current) return;
          setResults([]);
        })
        .finally(() => {
          if (seq === searchSeq.current) setSearching(false);
        });
    }, 250);
    return () => clearTimeout(timer);
  }, [query, selected]);

  const canSubmit = useMemo(
    () => (selected != null || query.trim().length >= 2) && !submitting,
    [selected, query, submitting],
  );

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const req: RecordAllergyRequest = {
        allergenName: selected?.genericName ?? query.trim(),
        allergenFormularyId: selected?.id,
        severity,
        reaction: reaction.trim() || undefined,
        onsetDate: onsetDate || undefined,
        recordedByName,
      };
      const created = await onSubmit(req);
      onCreated(created);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to record allergy');
    } finally {
      setSubmitting(false);
    }
  }, [canSubmit, selected, query, severity, reaction, onsetDate, recordedByName, onSubmit, onCreated]);

  return (
    <div className="rounded-md border border-emerald-200 bg-emerald-50/50 p-2.5 space-y-2">
      <div className="flex items-center justify-between">
        <div className="text-[10px] font-bold uppercase tracking-wider text-emerald-700 inline-flex items-center gap-1">
          <Plus className="w-3 h-3" /> Add allergy
        </div>
        <button
          type="button"
          onClick={onCancel}
          className="text-slate-500 hover:text-slate-700"
          aria-label="Cancel"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Allergen picker */}
      <div>
        <label className="block text-[9px] font-bold uppercase tracking-wider mb-1 text-slate-600">
          Allergen <span className="text-slate-400 normal-case font-normal">(search drug catalog or type free text)</span>
        </label>
        {selected ? (
          <div className="rounded-md border border-cyan-300 bg-cyan-50 p-2 flex items-start gap-2">
            <FlaskConical className="w-3.5 h-3.5 text-cyan-600 mt-0.5" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-1.5 flex-wrap">
                <span className="text-xs font-bold">{selected.genericName}</span>
                {selected.drugClass && (
                  <span
                    className="text-[9px] font-bold uppercase tracking-wider px-2.5 py-0.5 rounded-lg text-slate-600"
                    style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}
                  >
                    {selected.drugClass}
                  </span>
                )}
              </div>
              {selected.allergenGroups && (
                <p className="text-[10px] text-slate-600 mt-0.5">Groups: {selected.allergenGroups}</p>
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
              onChange={(e) => setQuery(e.target.value)}
              placeholder="e.g. penicillin, sulfa, shellfish, latex…"
              className="w-full pl-7 pr-2 py-1.5 text-xs rounded border border-slate-300 outline-none"
            />
            {(searching || results.length > 0) && (
              <div className="absolute z-10 mt-1 w-full max-h-48 overflow-y-auto rounded border border-slate-200 bg-white shadow-md">
                {searching && results.length === 0 && (
                  <p className="text-[11px] text-slate-400 text-center py-2">Searching…</p>
                )}
                {!searching && results.length === 0 && query.trim().length >= 2 && (
                  <p className="text-[10px] text-slate-500 text-center py-2 px-2">
                    No catalog match — submit as free text or refine the search.
                  </p>
                )}
                {results.map((r) => (
                  <button
                    key={r.id}
                    type="button"
                    onClick={() => { setSelected(r); }}
                    className="w-full text-left px-2 py-1.5 hover:bg-cyan-50 border-b border-slate-100 last:border-0"
                  >
                    <div className="text-xs font-bold">{r.genericName}</div>
                    {(r.drugClass || r.allergenGroups) && (
                      <div className="text-[10px] text-slate-500">
                        {r.drugClass}{r.allergenGroups ? ` · ${r.allergenGroups}` : ''}
                      </div>
                    )}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Severity chips */}
      <div>
        <label className="block text-[9px] font-bold uppercase tracking-wider mb-1 text-slate-600">
          Severity <span className="text-red-600">*</span>
        </label>
        <div className="grid grid-cols-5 gap-1">
          {ALLERGY_SEVERITIES.map((row) => {
            const active = severity === row.value;
            return (
              <button
                key={row.value}
                type="button"
                onClick={() => setSeverity(row.value)}
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

      {/* Reaction */}
      <div>
        <label className="block text-[9px] font-bold uppercase tracking-wider mb-1 text-slate-600">
          Reaction <span className="text-slate-400 normal-case font-normal">(what happened)</span>
        </label>
        <input
          value={reaction}
          onChange={(e) => setReaction(e.target.value)}
          placeholder="e.g. facial swelling, hives, anaphylactic shock"
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

      {error && (
        <div className="text-[11px] text-red-700">{error}</div>
      )}

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
          className="inline-flex items-center gap-1 px-3 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-600 text-white hover:bg-cyan-700 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting && <Loader2 className="w-3 h-3 animate-spin" />}
          Save allergy
        </button>
      </div>
    </div>
  );
}
