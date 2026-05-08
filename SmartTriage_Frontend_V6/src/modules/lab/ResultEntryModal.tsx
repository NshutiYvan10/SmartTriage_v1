/* ═══════════════════════════════════════════════════════════════
   Result Entry Modal — Lab tech files a result for an order.

   Safety-critical UI. Captures value + unit + reference range, lets
   the tech mark a specimen-quality concern at submit time, and
   surfaces the back-end's critical-value flagging visually before
   release. The doctor's read-back attestation lives on the
   AcknowledgeCriticalModal — this one just files the result.
   ═══════════════════════════════════════════════════════════════ */

import { useState } from 'react';
import { Beaker, Loader2, X, AlertOctagon } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { labApi } from '@/api/lab';
import type { LabOrder, RecordLabResultRequest } from '@/api/lab';

interface Props {
  order: LabOrder;
  enteredByName: string;
  onClose: () => void;
  onSaved: () => void;
}

export function ResultEntryModal({ order, enteredByName, onClose, onSaved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
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
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const numericPreview =
    form.resultNumeric != null
      ? form.referenceRangeMin != null && form.resultNumeric < form.referenceRangeMin
        ? 'Below reference range'
        : form.referenceRangeMax != null && form.resultNumeric > form.referenceRangeMax
          ? 'Above reference range'
          : 'Within range'
      : null;

  async function submit() {
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

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
      <div className="rounded-2xl p-6 max-w-lg w-full max-h-[90vh] overflow-y-auto animate-fade-up" style={glassCard}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-emerald-500/15 flex items-center justify-center">
              <Beaker className="w-5 h-5 text-emerald-500" />
            </div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>Enter result</h3>
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

        {/* Form fields */}
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
            <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Unit</label>
            <input
              value={form.resultUnit ?? ''}
              onChange={(e) => setForm({ ...form, resultUnit: e.target.value })}
              placeholder="mmol/L"
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

        {/* Specimen quality concern */}
        <label className="flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer mb-3" style={glassInner}>
          <input
            type="checkbox"
            checked={form.specimenQualityConcern ?? false}
            onChange={(e) => setForm({ ...form, specimenQualityConcern: e.target.checked })}
            className="w-4 h-4 accent-rose-500"
          />
          <span className={`text-xs font-semibold ${text.body}`}>Specimen quality concern (annotate result)</span>
        </label>

        {/* Notes */}
        <div className="mb-4">
          <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Notes (optional)</label>
          <textarea
            value={form.notes ?? ''}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}
            rows={2}
            placeholder="Any annotation released to the doctor with the result"
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
        </div>

        {/* Critical heads-up */}
        <div className="rounded-xl p-3 mb-4 bg-rose-500/10 ring-1 ring-rose-500/20 flex items-start gap-2">
          <AlertOctagon className="w-4 h-4 text-rose-500 mt-0.5 shrink-0" />
          <p className={`text-[11px] ${text.body}`}>
            If this value triggers a critical threshold, the system files an alert to the ordering doctor automatically.
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
            disabled={submitting || !form.resultValue.trim()}
            className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-gradient-to-r from-emerald-600 to-emerald-500 disabled:opacity-50"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Beaker className="w-3.5 h-3.5" />}
            Release result
          </button>
        </div>
      </div>
    </div>
  );
}
