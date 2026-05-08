/* ═══════════════════════════════════════════════════════════════
   Reject Specimen Modal — closes the haemolysed/clotted/mislabelled
   loop. Posting fires a HIGH-severity alert to the ordering doctor
   so they redraw.
   ═══════════════════════════════════════════════════════════════ */

import { useState } from 'react';
import { XCircle, Loader2, X } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { labApi } from '@/api/lab';
import type { LabOrder, SpecimenRejectionReason } from '@/api/lab';

const REASONS: { value: SpecimenRejectionReason; label: string; hint: string }[] = [
  { value: 'HAEMOLYSED',           label: 'Haemolysed',          hint: 'Red cells lysed — K+ falsely elevated' },
  { value: 'CLOTTED',              label: 'Clotted',             hint: 'Anticoagulant tube needed' },
  { value: 'INSUFFICIENT_VOLUME',  label: 'Insufficient volume', hint: 'Not enough sample for assay' },
  { value: 'MISLABELLED',          label: 'Mislabelled',         hint: 'Patient identity cannot be confirmed' },
  { value: 'WRONG_CONTAINER',      label: 'Wrong container',     hint: 'Wrong tube / additive' },
  { value: 'EXPIRED',              label: 'Expired',             hint: 'Stability window exceeded' },
  { value: 'OTHER',                label: 'Other',               hint: 'Specify in notes' },
];

interface Props {
  order: LabOrder;
  rejectedByName: string;
  onClose: () => void;
  onSaved: () => void;
}

export function RejectSpecimenModal({ order, rejectedByName, onClose, onSaved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [reason, setReason] = useState<SpecimenRejectionReason | null>(null);
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!reason) {
      setError('Pick a reason');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await labApi.rejectSpecimen(order.id, {
        reason,
        notes: notes || undefined,
        rejectedByName: rejectedByName || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to reject specimen');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
      <div className="rounded-2xl p-6 max-w-md w-full max-h-[90vh] overflow-y-auto animate-fade-up" style={glassCard}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-rose-500/15 flex items-center justify-center">
              <XCircle className="w-5 h-5 text-rose-500" />
            </div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>Reject specimen</h3>
              <p className={`text-xs ${text.muted}`}>{order.testName} • {order.orderNumber}</p>
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className="w-4 h-4 text-slate-400" />
          </button>
        </div>

        <p className={`text-xs mb-3 ${text.muted}`}>
          The ordering doctor will be alerted to redraw the sample.
        </p>

        <div className="space-y-2 mb-4">
          {REASONS.map((r) => (
            <label
              key={r.value}
              className={`flex items-start gap-3 px-3 py-2.5 rounded-xl cursor-pointer transition-colors ${
                reason === r.value ? 'ring-2 ring-rose-500/40' : ''
              }`}
              style={glassInner}
            >
              <input
                type="radio"
                name="reason"
                checked={reason === r.value}
                onChange={() => setReason(r.value)}
                className="w-4 h-4 mt-0.5 accent-rose-500"
              />
              <div className="flex-1">
                <div className={`text-xs font-bold ${text.heading}`}>{r.label}</div>
                <div className={`text-[10px] ${text.muted}`}>{r.hint}</div>
              </div>
            </label>
          ))}
        </div>

        <div className="mb-4">
          <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Notes (optional)</label>
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            rows={2}
            placeholder="Detail (which tube, what was wrong, etc.)"
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
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
            disabled={submitting || !reason}
            className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-gradient-to-r from-rose-600 to-rose-500 disabled:opacity-50"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
            Reject
          </button>
        </div>
      </div>
    </div>
  );
}
