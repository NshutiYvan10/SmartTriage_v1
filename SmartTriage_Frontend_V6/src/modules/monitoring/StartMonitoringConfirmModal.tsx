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
  const [confirmed, setConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 backdrop-blur-sm animate-fade-in"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md bg-white rounded-2xl shadow-2xl overflow-hidden mx-4 animate-fade-up"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-emerald-600 to-emerald-500 px-5 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-white/20 flex items-center justify-center">
              <Activity className="w-4.5 h-4.5 text-white" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">Start Continuous Monitoring</h3>
              <p className="text-[10px] text-white/80 mt-0.5">Confirm sensor placement</p>
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
            You are starting continuous monitoring for{' '}
            <span className="font-semibold text-slate-900">{patientName}</span>
            {bedCode && (
              <>
                {' '}on bed <span className="font-semibold text-slate-900">{bedCode}</span>
              </>
            )}
            {deviceLabel && (
              <>
                {' '}with monitor{' '}
                <span className="font-semibold text-slate-900">{deviceLabel}</span>
              </>
            )}
            .
          </div>

          <div className="rounded-xl bg-amber-50 border border-amber-200 px-3 py-3 flex items-start gap-2.5">
            <AlertTriangle className="w-4 h-4 text-amber-600 flex-shrink-0 mt-0.5" />
            <p className="text-xs text-amber-800 leading-relaxed">
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
              className="mt-0.5 w-4 h-4 rounded border-slate-300 text-emerald-600 focus:ring-emerald-500/30"
            />
            <span className="text-sm text-slate-700">
              <span className="font-semibold">Sensors are placed on the patient</span> and a
              valid waveform is visible. Start continuous monitoring now.
            </span>
          </label>

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
            onClick={handleStart}
            disabled={!confirmed || submitting}
            className="px-4 py-2 text-xs font-bold rounded-xl bg-gradient-to-r from-emerald-600 to-emerald-500 text-white shadow-lg hover:-translate-y-0.5 transition-all flex items-center gap-2 disabled:opacity-50 disabled:hover:translate-y-0 disabled:cursor-not-allowed"
          >
            <CheckCircle2 className="w-3.5 h-3.5" />
            {submitting ? 'Starting…' : 'Start Monitoring'}
          </button>
        </div>
      </div>
    </div>
  );
}
