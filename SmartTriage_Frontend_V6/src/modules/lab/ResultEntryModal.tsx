/* ═══════════════════════════════════════════════════════════════
   Result Entry Modal — Lab tech files a result for an order.

   Safety-critical UI. For a single-analyte test it captures value +
   unit + reference range. For a PANEL (FBC, U&E, LFT, blood gas,
   coagulation) it renders one row per analyte so each is recorded —
   and flagged abnormal/critical — independently, instead of forcing
   one number for the whole panel. The tech can mark a specimen-quality
   concern at submit time. The doctor's read-back attestation lives on
   the AcknowledgeCriticalModal — this one just files the result.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { Beaker, Loader2, X, AlertOctagon, AlertTriangle, RefreshCw } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { labApi } from '@/api/lab';
import type { LabOrder, RecordLabResultRequest, LabPanelComponent } from '@/api/lab';
import { labCatalogApi, type LabTestCatalogResponse } from '@/api/labCatalog';

/** Normalize a unit for comparison: lowercase, strip spaces, µ/μ → u. */
const normUnit = (u: string) => u.trim().toLowerCase().replace(/\s+/g, '').replace(/[µμ]/g, 'u');

interface Props {
  order: LabOrder;
  enteredByName: string;
  onClose: () => void;
  onSaved: () => void;
}

interface PanelRow {
  value: string;
  unit: string;
}

export function ResultEntryModal({ order, enteredByName, onClose, onSaved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();

  // Panel detection: null = still loading, [] = single-analyte test, non-empty = panel.
  const [panelDefs, setPanelDefs] = useState<LabPanelComponent[] | null>(null);
  // True when the panel-definition fetch FAILED (network/auth/5xx). We must NOT silently
  // fall back to the single-result form for what might be a panel — that would skip every
  // per-analyte critical check (a U&E with K+ 7.2 etc. would file with isCritical=false).
  // Instead we block submission and offer a retry.
  const [loadFailed, setLoadFailed] = useState(false);
  const [rows, setRows] = useState<PanelRow[]>([]);

  const [form, setForm] = useState<RecordLabResultRequest>({
    resultValue: '',
    resultUnit: order.resultUnit ?? '',
    resultNumeric: undefined,
    referenceRangeMin: order.referenceRangeMin ?? undefined,
    referenceRangeMax: order.referenceRangeMax ?? undefined,
    enteredByName,
    specimenQualityConcern: false,
    notes: '',
  });
  const [specimenQualityConcern, setSpecimenQualityConcern] = useState(false);
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<LabTestCatalogResponse | null>(null);

  // Detect whether this order's test is a multi-analyte panel and, if so, seed one row
  // per analyte (unit pre-filled from the definition). On FAILURE we deliberately do NOT
  // assume single-analyte — we surface an error + retry and block submission, so a
  // transient fetch failure can never silently downgrade a panel to the single form.
  const loadPanelDefs = useCallback(() => {
    let alive = true;
    setLoadFailed(false);
    setPanelDefs(null);
    labApi.getPanelComponents(order.id).then((defs) => {
      if (!alive) return;
      const list = defs || [];
      setPanelDefs(list);
      if (list.length > 0) {
        setRows(list.map((d) => ({ value: '', unit: d.resultUnit ?? '' })));
      }
    }).catch(() => { if (alive) { setLoadFailed(true); } });
    return () => { alive = false; };
  }, [order.id]);

  useEffect(() => loadPanelDefs(), [loadPanelDefs]);

  // Single-analyte pre-fill from the catalog (skipped for panels).
  useEffect(() => {
    if (panelDefs === null || panelDefs.length > 0) return;
    let alive = true;
    labCatalogApi.search(order.testName).then((list) => {
      if (!alive) return;
      const key = order.testName.toLowerCase();
      const match = (list || []).find((c) =>
        c.testName.toLowerCase() === key || c.shortName?.toLowerCase() === key);
      if (!match) return;
      setCatalog(match);
      setForm((f) => ({
        ...f,
        resultUnit: f.resultUnit || match.resultUnit || '',
        referenceRangeMin: f.referenceRangeMin ?? match.referenceLow ?? undefined,
        referenceRangeMax: f.referenceRangeMax ?? match.referenceHigh ?? undefined,
      }));
    }).catch(() => { /* best-effort — the modal works without pre-fill */ });
    return () => { alive = false; };
  }, [order.testName, panelDefs]);

  const isPanel = (panelDefs?.length ?? 0) > 0;

  const expectedUnit = catalog?.resultUnit ?? null;
  const unitMismatch = !!expectedUnit && !!form.resultUnit && normUnit(form.resultUnit) !== normUnit(expectedUnit);

  const numericPreview =
    form.resultNumeric != null
      ? form.referenceRangeMin != null && form.resultNumeric < form.referenceRangeMin
        ? 'Below reference range'
        : form.referenceRangeMax != null && form.resultNumeric > form.referenceRangeMax
          ? 'Above reference range'
          : 'Within range'
      : null;

  /** Per-row mismatch (entered unit differs from the analyte's definition unit). */
  function rowUnitMismatch(i: number): boolean {
    const def = panelDefs?.[i];
    const u = rows[i]?.unit;
    return !!def?.resultUnit && !!u && normUnit(u) !== normUnit(def.resultUnit);
  }

  async function submitSingle() {
    if (!form.resultValue.trim()) {
      setError('Result value is required');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await labApi.recordResult(order.id, {
        ...form,
        resultUnit: form.resultUnit || undefined,
        notes: form.notes || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to record result');
    } finally {
      setSubmitting(false);
    }
  }

  async function submitPanel() {
    const components = (panelDefs || [])
      .map((d, i) => ({ d, r: rows[i] }))
      .filter(({ r }) => r && r.value.trim() !== '')
      .map(({ d, r }) => {
        const num = Number(r.value);
        return {
          analyteName: d.analyteName,
          analyteCode: d.analyteCode ?? undefined,
          resultValue: r.value.trim(),
          resultNumeric: r.value.trim() !== '' && !Number.isNaN(num) ? num : undefined,
          resultUnit: r.unit?.trim() ? r.unit.trim() : undefined,
        };
      });
    if (components.length === 0) {
      setError('Enter a value for at least one analyte');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await labApi.recordPanelResult(order.id, {
        components,
        enteredByName,
        specimenQualityConcern,
        notes: notes || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to record panel result');
    } finally {
      setSubmitting(false);
    }
  }

  const submit = isPanel ? submitPanel : submitSingle;
  const canSubmit = isPanel
    ? rows.some((r) => r.value.trim() !== '')
    : !!form.resultValue.trim();

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }}>
      <div className="rounded-2xl p-6 max-w-lg w-full max-h-[90vh] overflow-y-auto shadow-2xl animate-scale-in" style={glassCard}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-emerald-500/15 flex items-center justify-center">
              <Beaker className="w-5 h-5 text-emerald-500" />
            </div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>Enter result{isPanel ? ' (panel)' : ''}</h3>
              <p className={`text-xs ${text.muted}`}>{order.testName} • {order.orderNumber}</p>
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className="w-4 h-4 text-slate-400" />
          </button>
        </div>

        {/* Order context strip */}
        <div className="rounded-xl p-3 mb-4 text-xs" style={glassInner}>
          <div className={`grid grid-cols-2 gap-2 ${text.muted}`}>
            <div>Priority: <span className={text.body}>{order.priority}</span></div>
            <div>Specimen: <span className={text.body}>{order.specimenType ?? '—'}</span></div>
            <div>Ordered by: <span className={text.body}>{order.orderedByName ?? '—'}</span></div>
            {order.accessionNumber && <div>Accession: <span className={`${text.body} font-mono`}>{order.accessionNumber}</span></div>}
          </div>
          {order.clinicalIndication && (
            <p className={`italic mt-2 pt-2 border-t ${isDark ? 'border-white/10 text-white/70' : 'border-slate-200 text-slate-600'}`}>
              Indication: {order.clinicalIndication}
            </p>
          )}
        </div>

        {loadFailed ? (
          <div className="rounded-xl p-4 mb-3 bg-rose-500/10 ring-1 ring-rose-500/20">
            <div className="flex items-start gap-2 mb-3">
              <AlertTriangle className="w-4 h-4 text-rose-500 mt-0.5 shrink-0" />
              <p className={`text-xs ${text.body}`}>
                Couldn't load the result form for this test. To avoid mis-recording a panel as a
                single value (which would skip per-analyte critical checks), result entry is blocked
                until the form loads. Check your connection and retry.
              </p>
            </div>
            <button
              onClick={loadPanelDefs}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700"
            >
              <RefreshCw className="w-3.5 h-3.5" /> Retry
            </button>
          </div>
        ) : panelDefs === null ? (
          <div className="flex items-center justify-center py-8 gap-2 text-sm" style={{ color: 'inherit' }}>
            <Loader2 className="w-4 h-4 animate-spin" /> <span className={text.muted}>Loading result form…</span>
          </div>
        ) : isPanel ? (
          /* ── Multi-analyte (panel) form ── */
          <div className="mb-3 space-y-2">
            <p className={`text-[11px] ${text.muted} mb-1`}>
              Enter each analyte. Reference ranges shown are the panel defaults; each value is
              checked for abnormal/critical thresholds independently.
            </p>
            {panelDefs!.map((d, i) => {
              const refLabel = d.referenceLow != null || d.referenceHigh != null
                ? `${d.referenceLow ?? '–'}–${d.referenceHigh ?? '–'}${d.resultUnit ? ' ' + d.resultUnit : ''}`
                : null;
              return (
                <div key={d.analyteName} className="rounded-xl p-2.5" style={glassInner}>
                  <div className="flex items-center justify-between mb-1">
                    <span className={`text-xs font-bold ${text.body}`}>{d.analyteName}</span>
                    {refLabel && <span className={`text-[10px] ${text.muted}`}>Ref: {refLabel}</span>}
                  </div>
                  <div className="grid grid-cols-3 gap-2">
                    <input
                      value={rows[i]?.value ?? ''}
                      onChange={(e) => setRows((rs) => rs.map((r, j) => j === i ? { ...r, value: e.target.value } : r))}
                      placeholder="value"
                      className={`col-span-2 px-3 py-2 rounded-lg text-sm outline-none ${isDark ? 'bg-white/5 text-white placeholder-slate-500' : 'bg-white text-slate-800 placeholder-slate-400'}`}
                    />
                    <input
                      value={rows[i]?.unit ?? ''}
                      onChange={(e) => setRows((rs) => rs.map((r, j) => j === i ? { ...r, unit: e.target.value } : r))}
                      placeholder={d.resultUnit ?? 'unit'}
                      className={`px-2 py-2 rounded-lg text-sm outline-none ${isDark ? 'bg-white/5 text-white placeholder-slate-500' : 'bg-white text-slate-800 placeholder-slate-400'}`}
                    />
                  </div>
                  {rowUnitMismatch(i) && (
                    <p className="text-[10px] text-red-500 mt-1 flex items-center gap-1">
                      <AlertTriangle className="w-3 h-3 shrink-0" /> Unit differs from "{d.resultUnit}" — critical check skipped, verify manually.
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        ) : (
          /* ── Single-analyte form ── */
          <>
            <div className="grid grid-cols-2 gap-3 mb-3">
              <div className="col-span-2">
                <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Result value <span className="text-rose-500">*</span></label>
                <input
                  value={form.resultValue}
                  onChange={(e) => setForm({ ...form, resultValue: e.target.value })}
                  placeholder="e.g. 6.8 or POSITIVE or growth of E. coli"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                  style={glassInner}
                  autoFocus
                />
              </div>
              <div>
                <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Numeric value (if applicable)</label>
                <input
                  type="number"
                  step="any"
                  value={form.resultNumeric ?? ''}
                  onChange={(e) => setForm({ ...form, resultNumeric: e.target.value === '' ? undefined : Number(e.target.value) })}
                  placeholder="6.8"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>
                  Unit{expectedUnit ? ` (expected ${expectedUnit})` : ''}
                </label>
                <input
                  value={form.resultUnit ?? ''}
                  onChange={(e) => setForm({ ...form, resultUnit: e.target.value })}
                  placeholder={expectedUnit ?? 'mmol/L'}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Ref. min</label>
                <input
                  type="number"
                  step="any"
                  value={form.referenceRangeMin ?? ''}
                  onChange={(e) => setForm({ ...form, referenceRangeMin: e.target.value === '' ? undefined : Number(e.target.value) })}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Ref. max</label>
                <input
                  type="number"
                  step="any"
                  value={form.referenceRangeMax ?? ''}
                  onChange={(e) => setForm({ ...form, referenceRangeMax: e.target.value === '' ? undefined : Number(e.target.value) })}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
                  style={glassInner}
                />
              </div>
            </div>

            {/* Unit-mismatch guard — the backend skips the critical-value check on a unit
                mismatch and flags it for manual review, so warn the tech before submit. */}
            {unitMismatch && (
              <div className="rounded-xl px-3 py-2 mb-3 text-xs font-semibold bg-red-500/10 text-red-500 flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5" />
                <span>Unit "{form.resultUnit}" differs from the expected "{expectedUnit}". The automatic
                  critical-value check will be skipped and the result flagged for manual verification —
                  confirm the unit before submitting.</span>
              </div>
            )}

            {/* Sanity preview */}
            {numericPreview && (
              <div className={`rounded-xl px-3 py-2 mb-3 text-xs font-semibold ${
                numericPreview === 'Within range'
                  ? 'bg-emerald-500/10 text-emerald-500'
                  : 'bg-amber-500/10 text-amber-500'
              }`}>
                {numericPreview === 'Within range' ? 'Within reference range' : numericPreview + ' — abnormal flag will be set'}
              </div>
            )}
          </>
        )}

        {/* Specimen quality concern (both modes) */}
        {panelDefs !== null && (
          <label className="flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer mb-3" style={glassInner}>
            <input
              type="checkbox"
              checked={isPanel ? specimenQualityConcern : (form.specimenQualityConcern ?? false)}
              onChange={(e) => isPanel
                ? setSpecimenQualityConcern(e.target.checked)
                : setForm({ ...form, specimenQualityConcern: e.target.checked })}
              className="w-4 h-4 accent-rose-500"
            />
            <span className={`text-xs font-semibold ${text.body}`}>Specimen quality concern (annotate result)</span>
          </label>
        )}

        {/* Notes (both modes) */}
        {panelDefs !== null && (
          <div className="mb-4">
            <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Notes (optional)</label>
            <textarea
              value={isPanel ? notes : (form.notes ?? '')}
              onChange={(e) => isPanel ? setNotes(e.target.value) : setForm({ ...form, notes: e.target.value })}
              rows={2}
              placeholder="Any annotation released to the doctor with the result"
              className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
              style={glassInner}
            />
          </div>
        )}

        {/* Critical heads-up */}
        <div className="rounded-xl p-3 mb-4 bg-rose-500/10 ring-1 ring-rose-500/20 flex items-start gap-2">
          <AlertOctagon className="w-4 h-4 text-rose-500 mt-0.5 shrink-0" />
          <p className={`text-[11px] ${text.body}`}>
            If any value triggers a critical threshold, the system files an alert to the ordering doctor automatically.
            You must still call the doctor and document the read-back when you reach them.
          </p>
        </div>

        {error && (
          <div className="rounded-xl px-3 py-2 mb-3 text-xs font-semibold bg-rose-500/10 text-rose-500">
            {error}
          </div>
        )}

        <div className="flex items-center justify-end gap-2">
          <button onClick={onClose} disabled={submitting} className={`px-4 py-2 rounded-xl text-xs font-bold ${text.muted} hover:bg-white/5 disabled:opacity-50`}>Cancel</button>
          <button
            onClick={submit}
            disabled={submitting || panelDefs === null || !canSubmit}
            className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Beaker className="w-3.5 h-3.5" />}
            Release result
          </button>
        </div>
      </div>
    </div>
  );
}
