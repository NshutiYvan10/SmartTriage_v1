/* ── BreakTheGlassModal (Phase 3) ──
 *
 * Emergency override to view a patient's cross-hospital deep record when no data-sharing consent
 * is on file. A clinical justification is MANDATORY; the override is recorded forensically and a
 * real-time governance alert fires to the clinician's hospital. Deliberately alarming (red) — this
 * is an exceptional, audited action.
 */
import { useState } from 'react';
import { AlertTriangle, Loader2, ShieldAlert, X } from 'lucide-react';

interface Props {
  patientLabel?: string;
  onConfirm: (reason: string) => Promise<void> | void;
  onClose: () => void;
}

const MIN_REASON = 10;

export function BreakTheGlassModal({ patientLabel, onConfirm, onClose }: Props) {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const ok = reason.trim().length >= MIN_REASON;

  const confirm = async () => {
    if (!ok) return;
    setSubmitting(true);
    try {
      await onConfirm(reason.trim());
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden bg-white">
        <div className="bg-red-600 px-5 py-3 flex items-center justify-between text-white">
          <div className="flex items-center gap-2">
            <ShieldAlert className="w-5 h-5" />
            <h3 className="text-sm font-bold">Break the glass — emergency record access</h3>
          </div>
          <button onClick={onClose} className="text-white/80 hover:text-white"><X className="w-5 h-5" /></button>
        </div>

        <div className="p-5 space-y-3">
          <div className="rounded-lg bg-red-50 border border-red-200 p-3 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-red-600 flex-shrink-0 mt-0.5" />
            <p className="text-xs text-red-800">
              No data-sharing consent is on file{patientLabel ? ` for ${patientLabel}` : ''}. Overriding
              records an immutable forensic event and notifies your hospital's governance team in real
              time. Use only when clinically necessary and consent cannot be obtained.
            </p>
          </div>

          <div>
            <label className="block text-[11px] font-bold uppercase tracking-wide text-slate-600 mb-1">
              Clinical justification (required)
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={4}
              placeholder="e.g. Unconscious trauma patient; need prior allergies and surgical history immediately"
              className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm"
            />
            <p className={`text-[11px] mt-1 ${ok ? 'text-slate-400' : 'text-red-500'}`}>
              {reason.trim().length}/{MIN_REASON} characters minimum
            </p>
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t border-slate-100">
          <button onClick={onClose} disabled={submitting} className="px-4 py-1.5 rounded-lg text-xs font-bold text-slate-600 hover:bg-slate-100">
            Cancel
          </button>
          <button
            onClick={confirm} disabled={!ok || submitting}
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-lg text-xs font-bold text-white ${
              ok && !submitting ? 'bg-red-600 hover:bg-red-500' : 'bg-slate-400 cursor-not-allowed'}`}
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldAlert className="w-3.5 h-3.5" />}
            Override & access records
          </button>
        </div>
      </div>
    </div>
  );
}
