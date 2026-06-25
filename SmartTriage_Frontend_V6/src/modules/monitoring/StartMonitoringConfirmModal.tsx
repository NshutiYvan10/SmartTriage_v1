/**
 * StartMonitoringConfirmModal — clinical-safety guard for starting a
 * continuous-monitoring session.
 *
 * The previous workflow auto-started monitoring at bed placement, which
 * meant the first reading often arrived before sensors had been placed
 * on the patient — feeding noise into the auto-retriage engine. This
 * modal forces the clinician to confirm sensor placement before the
 * session opens. The confirmation checkbox is the explicit attestation
 * trail; the Start button stays disabled until it's checked.
 */
import { useState } from 'react';
import { Activity, AlertTriangle, CheckCircle2, X } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';

interface Props {
  patientName: string;
  bedCode?: string | null;
  deviceLabel?: string | null;
  onConfirm: () => Promise<void> | void;
  onClose: () => void;
}

export default function StartMonitoringConfirmModal({
  patientName,
  bedCode,
  deviceLabel,
  onConfirm,
  onClose,
}: Props) {
  const { glassCard, isDark, text } = useTheme();
  const [confirmed, setConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const borderStyle = isDark
    ? '1px solid rgba(2,132,199,0.12)'
    : '1px solid rgba(203,213,225,0.3)';

  const handleStart = async () => {
    if (!confirmed || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await onConfirm();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start monitoring');
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'rgba(2,6,23,0.65)' }}
      onClick={onClose}
    >
      <div
        style={glassCard}
        className="w-full max-w-md rounded-2xl shadow-2xl overflow-hidden mx-4 animate-scale-in"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-emerald-500/20 flex items-center justify-center">
              <Activity className="w-5 h-5 text-emerald-400" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">Start Continuous Monitoring</h3>
              <p className="text-[10px] text-white/50 mt-0.5">Confirm sensor placement</p>
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
          <div className={`text-sm leading-relaxed ${text.body}`}>
            You are starting continuous monitoring for{' '}
            <span className={`font-semibold ${text.heading}`}>{patientName}</span>
            {bedCode && (
              <>
                {' '}on bed <span className={`font-semibold ${text.heading}`}>{bedCode}</span>
              </>
            )}
            {deviceLabel && (
              <>
                {' '}with monitor{' '}
                <span className={`font-semibold ${text.heading}`}>{deviceLabel}</span>
              </>
            )}
            .
          </div>

          <div className="rounded-xl bg-amber-500/10 border border-amber-500/30 px-3 py-3 flex items-start gap-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
            <p className={`text-xs leading-relaxed ${isDark ? 'text-amber-200' : 'text-amber-800'}`}>
              The system will treat all incoming readings as the patient's
              vitals and may auto-retriage based on them. Confirm probes
              are placed on the patient and a waveform is visible on the
              monitor before pressing Start.
            </p>
          </div>

          <label className="flex items-start gap-3 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(e) => setConfirmed(e.target.checked)}
              className="mt-0.5 w-4 h-4 rounded border-slate-300 text-emerald-600 focus:outline-none focus:ring-2 focus:ring-cyan-500/20"
            />
            <span className={`text-sm ${text.body}`}>
              <span className={`font-semibold ${text.heading}`}>Sensors are placed on the patient</span> and a
              valid waveform is visible. Start continuous monitoring now.
            </span>
          </label>

          {error && (
            <div className="rounded-xl bg-red-500/10 border border-red-500/30 px-3 py-2 text-xs text-red-500">
              {error}
            </div>
          )}
        </div>

        <div
          style={{ borderTop: borderStyle }}
          className={`px-5 py-3.5 flex items-center justify-end gap-2 ${isDark ? 'bg-white/5' : 'bg-slate-50/60'}`}
        >
          <button
            onClick={onClose}
            disabled={submitting}
            className={`px-4 py-2 text-xs font-bold rounded-xl bg-white/10 hover:bg-white/20 disabled:opacity-50 ${text.label}`}
          >
            Cancel
          </button>
          <button
            onClick={handleStart}
            disabled={!confirmed || submitting}
            className="px-4 py-2 text-xs font-bold rounded-xl bg-cyan-600 hover:bg-cyan-700 text-white shadow-lg transition-all flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <CheckCircle2 className="w-3.5 h-3.5" />
            {submitting ? 'Starting…' : 'Start Monitoring'}
          </button>
        </div>
      </div>
    </div>
  );
}
