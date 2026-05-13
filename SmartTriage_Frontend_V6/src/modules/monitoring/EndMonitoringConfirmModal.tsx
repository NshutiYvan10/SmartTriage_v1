/**
 * EndMonitoringConfirmModal — in-app confirmation for ending a
 * monitoring session. Replaces the browser-native window.confirm()
 * which shows the URL bar / "localhost says" header — distracting and
 * out-of-style for a clinical UI. Uses the same modal scaffold as
 * StartMonitoringConfirmModal for visual consistency.
 */
import { useState } from 'react';
import { AlertTriangle, Square, X } from 'lucide-react';

interface Props {
  patientName: string;
  onConfirm: () => Promise<void> | void;
  onClose: () => void;
}

export default function EndMonitoringConfirmModal({
  patientName,
  onConfirm,
  onClose,
}: Props) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleEnd = async () => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await onConfirm();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to end monitoring');
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 backdrop-blur-sm animate-fade-in"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md bg-white rounded-2xl shadow-2xl overflow-hidden mx-4 animate-fade-up"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-red-600 to-red-500 px-5 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-white/20 flex items-center justify-center">
              <Square className="w-4.5 h-4.5 text-white" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">End Continuous Monitoring</h3>
              <p className="text-[10px] text-white/80 mt-0.5">
                The session will be closed permanently
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-8 h-8 rounded-xl bg-white/15 hover:bg-white/25 flex items-center justify-center"
            aria-label="Close"
          >
            <X className="w-4 h-4 text-white" />
          </button>
        </div>

        <div className="px-5 py-5 space-y-4">
          <div className="text-sm text-slate-700 leading-relaxed">
            End monitoring for{' '}
            <span className="font-semibold text-slate-900">{patientName}</span>?
          </div>

          <div className="rounded-xl bg-amber-50 border border-amber-200 px-3 py-3 flex items-start gap-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-600 flex-shrink-0 mt-0.5" />
            <p className="text-xs text-amber-800 leading-relaxed">
              The session will be marked Ended and cannot be resumed. To monitor this
              patient again, press <span className="font-semibold">Start Monitoring</span>{' '}
              to open a fresh session. The historical record stays on the chart.
            </p>
          </div>

          {error && (
            <div className="rounded-xl bg-red-50 border border-red-200 px-3 py-2 text-xs text-red-700">
              {error}
            </div>
          )}
        </div>

        <div className="px-5 py-3.5 bg-slate-50 flex items-center justify-end gap-2">
          <button
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-xs font-bold rounded-xl bg-slate-200 text-slate-700 hover:bg-slate-300 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleEnd}
            disabled={submitting}
            className="px-4 py-2 text-xs font-bold rounded-xl bg-gradient-to-r from-red-600 to-red-500 text-white shadow-lg hover:-translate-y-0.5 transition-all flex items-center gap-2 disabled:opacity-50 disabled:hover:translate-y-0 disabled:cursor-not-allowed"
          >
            <Square className="w-3.5 h-3.5" />
            {submitting ? 'Ending…' : 'End Monitoring'}
          </button>
        </div>
      </div>
    </div>
  );
}
