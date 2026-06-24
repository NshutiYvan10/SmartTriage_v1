/**
 * EndMonitoringConfirmModal — in-app confirmation for ending a
 * monitoring session. Replaces the browser-native window.confirm()
 * which shows the URL bar / "localhost says" header — distracting and
 * out-of-style for a clinical UI. Uses the same modal scaffold as
 * StartMonitoringConfirmModal for visual consistency.
 */
import { useState } from 'react';
import { AlertTriangle, Square, X } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';

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
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const borderStyle = isDark
    ? '1px solid rgba(2,132,199,0.12)'
    : '1px solid rgba(203,213,225,0.3)';

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
        className="w-full max-w-md rounded-2xl shadow-2xl overflow-hidden mx-4 animate-scale-in"
        style={glassCard}
        onClick={(e) => e.stopPropagation()}
      >
        <div
          className="px-5 py-4 flex items-center justify-between"
          style={{ borderBottom: borderStyle }}
        >
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-red-500/20 flex items-center justify-center">
              <Square className="w-5 h-5 text-red-400" />
            </div>
            <div>
              <h3 className={`text-sm font-bold ${text.heading}`}>End Continuous Monitoring</h3>
              <p className={`text-[10px] mt-0.5 ${text.muted}`}>
                The session will be closed permanently
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className={`w-8 h-8 rounded-xl flex items-center justify-center ${
              isDark ? 'bg-white/10 hover:bg-white/20' : 'bg-slate-200/70 hover:bg-slate-300/70'
            }`}
            aria-label="Close"
          >
            <X className={`w-4 h-4 ${text.body}`} />
          </button>
        </div>

        <div className="px-5 py-5 space-y-4">
          <div className={`text-sm leading-relaxed ${text.body}`}>
            End monitoring for{' '}
            <span className={`font-semibold ${text.heading}`}>{patientName}</span>?
          </div>

          <div className="rounded-xl bg-amber-500/20 border border-amber-500/30 px-3 py-3 flex items-start gap-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
            <p className="text-xs text-amber-300 leading-relaxed">
              The session will be marked Ended and cannot be resumed. To monitor this
              patient again, press <span className="font-semibold">Start Monitoring</span>{' '}
              to open a fresh session. The historical record stays on the chart.
            </p>
          </div>

          {error && (
            <div className="rounded-xl bg-red-500/20 border border-red-500/30 px-3 py-2 text-xs text-red-300">
              {error}
            </div>
          )}
        </div>

        <div
          className="px-5 py-3.5 flex items-center justify-end gap-2"
          style={{ ...glassInner, borderTop: borderStyle }}
        >
          <button
            onClick={onClose}
            disabled={submitting}
            className={`px-4 py-2 text-xs font-bold rounded-xl disabled:opacity-50 ${
              isDark
                ? 'bg-white/10 text-slate-200 hover:bg-white/20'
                : 'bg-slate-200 text-slate-700 hover:bg-slate-300'
            }`}
          >
            Cancel
          </button>
          <button
            onClick={handleEnd}
            disabled={submitting}
            className="px-4 py-2 text-xs font-bold rounded-xl bg-red-600 text-white shadow-lg hover:bg-red-700 transition-all flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Square className="w-3.5 h-3.5" />
            {submitting ? 'Ending…' : 'End Monitoring'}
          </button>
        </div>
      </div>
    </div>
  );
}
