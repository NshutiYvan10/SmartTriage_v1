/* ── BreakTheGlassModal (Phase 3) ──
 *
 * Emergency override to view a patient's cross-hospital deep record when no data-sharing consent
 * is on file. A clinical justification is MANDATORY; the override is recorded forensically and a
 * real-time governance alert fires to the clinician's hospital. Deliberately alarming (red) — this
 * is an exceptional, audited action.
 */
import { useState } from 'react';
import { AlertTriangle, Loader2, ShieldAlert, X } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';

interface Props {
  patientLabel?: string;
  onConfirm: (reason: string) => Promise<void> | void;
  onClose: () => void;
}

const MIN_REASON = 10;

export function BreakTheGlassModal({ patientLabel, onConfirm, onClose }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
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
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'rgba(2,6,23,0.65)' }}
    >
      <div style={glassCard} className="w-full max-w-lg rounded-2xl overflow-hidden shadow-2xl animate-scale-in">
        <div className="bg-red-600 px-5 py-3 flex items-center justify-between text-white">
          <div className="flex items-center gap-2">
            <ShieldAlert className="w-5 h-5" />
            <h3 className="text-sm font-bold">Break the glass — emergency record access</h3>
          </div>
          <button onClick={onClose} className="text-white/80 hover:text-white"><X className="w-4 h-4" /></button>
        </div>

        <div className="p-5 space-y-3">
          <div className="rounded-lg bg-red-500/20 border border-red-500/30 p-3 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
            <p className="text-xs text-red-300">
              No data-sharing consent is on file{patientLabel ? ` for ${patientLabel}` : ''}. Overriding
              records an immutable forensic event and notifies your hospital's governance team in real
              time. Use only when clinically necessary and consent cannot be obtained.
            </p>
          </div>

          <div>
            <label className={`block text-[11px] font-bold uppercase tracking-wide mb-1 ${text.label}`}>
              Clinical justification (required)
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={4}
              placeholder="e.g. Unconscious trauma patient; need prior allergies and surgical history immediately"
              style={glassInner}
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            />
            <p className={`text-[11px] mt-1 ${ok ? text.muted : 'text-red-400'}`}>
              {reason.trim().length}/{MIN_REASON} characters minimum
            </p>
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button onClick={onClose} disabled={submitting} className={`px-4 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 ${text.body}`}>
            Cancel
          </button>
          <button
            onClick={confirm} disabled={!ok || submitting}
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white ${
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
