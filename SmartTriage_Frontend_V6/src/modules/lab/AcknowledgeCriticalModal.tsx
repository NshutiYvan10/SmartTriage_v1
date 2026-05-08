/* ═══════════════════════════════════════════════════════════════
   Acknowledge Critical Modal — JCI NPSG.02.03.01 read-back capture.

   Doctor acknowledges receipt of a critical lab value AND attests to
   the read-back: who called them, by what method, and what value
   they understood. The text is stored on the order row so an
   inspector can audit how the panic value was communicated.
   ═══════════════════════════════════════════════════════════════ */

import { useState } from 'react';
import { Phone, Loader2, X, AlertOctagon } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { labApi } from '@/api/lab';
import type { LabOrder, CriticalContactMethod } from '@/api/lab';

const METHODS: { value: CriticalContactMethod; label: string }[] = [
  { value: 'PHONE',     label: 'Phone call (read-back required)' },
  { value: 'IN_PERSON', label: 'In-person handover' },
  { value: 'IN_APP',    label: 'In-app alert' },
];

interface Props {
  order: LabOrder;
  acknowledgedByName: string;
  onClose: () => void;
  onSaved: () => void;
}

export function AcknowledgeCriticalModal({ order, acknowledgedByName, onClose, onSaved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [method, setMethod] = useState<CriticalContactMethod>('PHONE');
  const [readback, setReadback] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (method === 'PHONE' && !readback.trim()) {
      setError('Phone read-back text is required for phone notifications');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await labApi.acknowledgeCritical(order.id, {
        contactMethod: method,
        readbackText: readback || undefined,
        acknowledgedByName: acknowledgedByName || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to acknowledge');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
      <div className="rounded-2xl p-6 max-w-lg w-full max-h-[90vh] overflow-y-auto animate-fade-up" style={glassCard}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-rose-500/15 flex items-center justify-center">
              <AlertOctagon className="w-5 h-5 text-rose-500" />
            </div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>Acknowledge critical value</h3>
              <p className={`text-xs ${text.muted}`}>{order.testName} • {order.orderNumber}</p>
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className="w-4 h-4 text-slate-400" />
          </button>
        </div>

        {/* Result snapshot */}
        <div className="rounded-xl p-3 mb-4 bg-rose-500/10 ring-1 ring-rose-500/20">
          <div className="text-[10px] uppercase font-bold mb-1 text-rose-500">Critical result</div>
          <div className={`text-base font-bold ${text.heading}`}>
            {order.resultValue} {order.resultUnit}
          </div>
          {order.criticalValueType && (
            <div className={`text-[11px] ${text.body}`}>{order.criticalValueType.replace(/_/g, ' ')}</div>
          )}
          {order.referenceRangeMin !== null && order.referenceRangeMax !== null && (
            <div className={`text-[10px] ${text.muted}`}>
              Reference: {order.referenceRangeMin} – {order.referenceRangeMax} {order.resultUnit}
            </div>
          )}
        </div>

        {/* Method */}
        <div className="mb-3">
          <label className={`text-[10px] font-bold uppercase tracking-wider mb-2 block ${text.label}`}>How were you notified?</label>
          <div className="space-y-2">
            {METHODS.map((m) => (
              <label
                key={m.value}
                className={`flex items-center gap-3 px-3 py-2.5 rounded-xl cursor-pointer ${method === m.value ? 'ring-2 ring-rose-500/40' : ''}`}
                style={glassInner}
              >
                <input
                  type="radio"
                  name="method"
                  checked={method === m.value}
                  onChange={() => setMethod(m.value)}
                  className="w-4 h-4 accent-rose-500"
                />
                <span className={`text-xs font-semibold ${text.body}`}>{m.label}</span>
              </label>
            ))}
          </div>
        </div>

        {/* Read-back */}
        <div className="mb-3">
          <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>
            Read-back {method === 'PHONE' && <span className="text-rose-500">*</span>}
          </label>
          <textarea
            value={readback}
            onChange={(e) => setReadback(e.target.value)}
            rows={3}
            placeholder='Type back the value as you understood it. e.g. "Potassium six point eight, repeat six point eight"'
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
          <p className={`text-[10px] mt-1 ${text.muted}`}>
            JCI NPSG.02.03.01 — for phone notifications, document what you read back to the lab.
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
            disabled={submitting}
            className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-gradient-to-r from-rose-600 to-rose-500 disabled:opacity-50"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Phone className="w-3.5 h-3.5" />}
            Confirm acknowledgement
          </button>
        </div>
      </div>
    </div>
  );
}
