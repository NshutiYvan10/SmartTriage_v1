/**
 * AckReasonModal — collects the MANDATORY reason for acknowledging a CRITICAL alert.
 *
 * Patient-safety fix (1b): acknowledging a critical alert removes it from every escalation
 * reminder loop, so the server now requires a documented reason. This modal is the shared UI
 * that captures it wherever a clinician acknowledges a critical (see useAckAlert). A bare
 * click can no longer silence a life-critical alarm.
 */
import { useEffect, useRef } from 'react';
import { AlertTriangle } from 'lucide-react';

interface Props {
  open: boolean;
  patientName?: string;
  message?: string;
  reason: string;
  onReasonChange: (value: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
  submitting?: boolean;
}

export function AckReasonModal({
  open, patientName, message, reason, onReasonChange, onConfirm, onCancel, submitting,
}: Props) {
  const taRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (open) taRef.current?.focus();
  }, [open]);

  if (!open) return null;
  const canConfirm = reason.trim().length > 0 && !submitting;

  return (
    <div
      className="fixed inset-0 z-[10000] flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Acknowledge critical alert"
    >
      <div className="w-full max-w-md rounded-2xl bg-white dark:bg-slate-800 shadow-2xl border border-rose-300 dark:border-rose-500/40 overflow-hidden">
        <div className="bg-rose-600 text-white px-5 py-4 flex items-start gap-3">
          <AlertTriangle className="w-6 h-6 flex-shrink-0 mt-0.5" />
          <div>
            <div className="text-[11px] font-bold uppercase tracking-widest opacity-80">Critical alert</div>
            <div className="font-bold">Acknowledge — reason required</div>
          </div>
        </div>
        <div className="p-5 space-y-3">
          {(patientName || message) && (
            <div className="text-sm text-slate-600 dark:text-slate-300">
              {patientName && <div className="font-semibold">{patientName}</div>}
              {message && <div className="text-xs opacity-90 line-clamp-3">{message}</div>}
            </div>
          )}
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Acknowledging removes this critical alert from every escalation reminder. Record the
            clinical action you took (or why it is being dismissed).
          </p>
          <textarea
            ref={taRef}
            value={reason}
            onChange={(e) => onReasonChange(e.target.value)}
            rows={3}
            placeholder="e.g. IV dextrose given, 15-min recheck ordered"
            className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-transparent p-2 text-sm focus:outline-none focus:ring-2 focus:ring-rose-400"
          />
          <div className="flex justify-end gap-2 pt-1">
            <button
              onClick={onCancel}
              disabled={submitting}
              className="px-4 py-2 rounded-lg text-sm font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              onClick={onConfirm}
              disabled={!canConfirm}
              className="px-4 py-2 rounded-lg text-sm font-bold text-white bg-rose-600 hover:bg-rose-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {submitting ? 'Acknowledging…' : 'Acknowledge'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
