/**
 * V54 — Stabilize & Pull modal.
 *
 * The nurse opens this from inside the triage form (Adult or Pediatric)
 * once a triage-zone monitor has been picked. The modal:
 *
 *   1. Auto-creates a monitoring session against the visit + picked
 *      device on first open (so vitals stream into the standard
 *      /topic/vitals/{visitId} pipe and get audit-recorded).
 *   2. Subscribes to that pipe and accumulates a rolling window of
 *      VitalStreamResponses.
 *   3. Runs each reading through the pure `evaluateStability` helper
 *      per vital — each shows 🟢 stable / 🟡 stabilizing / 🔴 unstable.
 *   4. On "Use these values", returns the median of each currently
 *      stable vital to the parent form. Unstable vitals are skipped
 *      (parent form falls back to manual entry for those).
 *
 * Manual entry is never blocked. The form's vital inputs stay editable
 * before, during, and after this modal is open.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Activity, AlertTriangle, CheckCircle, Loader2, RefreshCcw, X, Zap } from 'lucide-react';
import type { DeviceResponse, VitalStreamResponse } from '@/api/types';
import { iotApi } from '@/api/iot';
import { subscribeToVitals } from '@/api/websocket';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';
import {
  evaluateStability,
  formatVital,
  roundForVital,
  STABILITY_WINDOW_SIZE,
  VITAL_TOLERANCES,
  type VitalKey,
  type VitalStabilityResult,
} from '@/lib/vitalStability';

/** The shape we hand back to the parent form when nurse confirms. */
export interface PulledVitals {
  heartRate?: number;
  respiratoryRate?: number;
  spo2?: number;
  systolicBp?: number;
  diastolicBp?: number;
  temperature?: number;
  /** When the snapshot was confirmed (for the "From monitor at hh:mm" badge). */
  capturedAt: Date;
  /** Device name for audit display in the form. */
  deviceName: string;
}

interface Props {
  visitId: string;
  device: DeviceResponse;
  onClose: () => void;
  onUse: (vitals: PulledVitals) => void;
  /**
   * Vitals the parent doesn't want overwritten (e.g. nurse already
   * typed BP manually). Those rows still appear in the modal but the
   * "use" path skips them.
   */
  skipVitals?: ReadonlySet<VitalKey>;
}

const ORDER: ReadonlyArray<VitalKey> = [
  'heartRate',
  'respiratoryRate',
  'spo2',
  'systolicBp',
  'diastolicBp',
  'temperature',
];

export default function StabilizeAndPullModal({ visitId, device, onClose, onUse, skipVitals }: Props) {
  const { isDark } = useTheme();
  const user = useAuthStore(s => s.user);
  const [readings, setReadings] = useState<VitalStreamResponse[]>([]);
  const [sessionStarted, setSessionStarted] = useState(false);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [usingPolling, setUsingPolling] = useState(false);
  const subRef = useRef<(() => void) | null>(null);
  const pollRef = useRef<number | null>(null);

  // ── 1. Start the monitoring session on first open ────────────────
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        await iotApi.startMonitoring({
          deviceId: device.id,
          visitId,
          startedByName: user?.fullName ?? user?.email ?? 'Triage Nurse',
        });
      } catch (err: any) {
        // Session may already exist (nurse re-opened the modal). That's fine.
        const msg = String(err?.message ?? '');
        if (!/already|active|exists/i.test(msg)) {
          if (!cancelled) setSessionError(msg || 'Could not start monitor session.');
          return;
        }
      }
      if (!cancelled) setSessionStarted(true);
    })();
    return () => { cancelled = true; };
  }, [device.id, visitId, user]);

  // ── 2. Subscribe to the live stream (with REST polling fallback) ─
  useEffect(() => {
    if (!sessionStarted) return;

    const push = (vs: VitalStreamResponse) => {
      setReadings(prev => {
        // Dedup by sequenceNumber, keep newest STABILITY_WINDOW_SIZE.
        const seen = new Set(prev.map(r => r.sequenceNumber));
        if (seen.has(vs.sequenceNumber)) return prev;
        const next = [...prev, vs];
        return next.slice(-STABILITY_WINDOW_SIZE);
      });
    };

    let stillSubscribed = true;
    try {
      const unsub = subscribeToVitals(visitId, vs => {
        if (stillSubscribed) push(vs);
      });
      subRef.current = unsub;
    } catch {
      // WebSocket setup failed; fall through to polling.
      setUsingPolling(true);
    }

    // Polling fallback: every 5s pull the latest reading via REST.
    // Always runs alongside the subscription — if the socket is fine,
    // dedup-by-sequenceNumber means polling adds zero extra readings.
    const pollFn = async () => {
      try {
        const recent = await iotApi.getRecentStream(visitId, STABILITY_WINDOW_SIZE);
        recent.forEach(push);
      } catch {
        // Ignore — next tick will retry.
      }
    };
    pollFn();
    pollRef.current = window.setInterval(pollFn, 5000);

    return () => {
      stillSubscribed = false;
      subRef.current?.();
      subRef.current = null;
      if (pollRef.current != null) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [sessionStarted, visitId]);

  // ── 3. Per-vital stability evaluation ────────────────────────────
  const results = useMemo<VitalStabilityResult[]>(
    () => ORDER.map(k => evaluateStability(readings, k)),
    [readings],
  );

  const anyStable = results.some(r => r.state === 'stable');

  // ── 4. Confirm & hand the snapshot back to the parent ────────────
  const handleUse = () => {
    const out: PulledVitals = {
      capturedAt: new Date(),
      deviceName: device.deviceName,
    };
    for (const r of results) {
      if (r.state === 'stable' && r.value != null && !skipVitals?.has(r.key)) {
        out[r.key] = roundForVital(r.value, r.key);
      }
    }
    onUse(out);
  };

  // ── Render ───────────────────────────────────────────────────────
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm animate-fade-in"
      onClick={onClose}
    >
      <div
        className={`w-full max-w-xl rounded-3xl shadow-2xl overflow-hidden mx-4 animate-fade-up ${
          isDark ? 'bg-slate-900 text-white' : 'bg-white text-slate-800'
        }`}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="bg-gradient-to-r from-cyan-600 to-cyan-500 px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-xl bg-white/20 flex items-center justify-center">
                <Activity className="w-4.5 h-4.5 text-white" />
              </div>
              <div>
                <h3 className="text-sm font-bold text-white">Stabilize &amp; Pull from Monitor</h3>
                <p className="text-[10px] text-white/80 mt-0.5">
                  {device.deviceName} · <span className="font-mono">{device.serialNumber}</span>
                  {usingPolling && <span className="ml-2 px-1.5 py-0.5 rounded bg-amber-400/30 text-[9px]">Polling fallback</span>}
                </p>
              </div>
            </div>
            <button
              onClick={onClose}
              className="w-8 h-8 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25 transition-colors"
              aria-label="Close"
            >
              <X className="w-4 h-4 text-white" />
            </button>
          </div>
        </div>

        {/* Body */}
        <div className="px-6 py-5 space-y-3">
          {sessionError && (
            <div className="flex items-center gap-2 px-3 py-2 rounded-xl bg-red-500/10 border border-red-500/20">
              <AlertTriangle className="w-4 h-4 text-red-400 flex-shrink-0" />
              <p className="text-[11px] font-medium text-red-400">{sessionError}</p>
            </div>
          )}

          {!sessionStarted && !sessionError && (
            <div className={`flex items-center gap-2 px-3 py-2 rounded-xl ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
              <Loader2 className="w-4 h-4 animate-spin text-cyan-500" />
              <p className={`text-[11px] ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>
                Connecting to monitor…
              </p>
            </div>
          )}

          {sessionStarted && readings.length === 0 && (
            <div className={`flex items-center gap-2 px-3 py-2 rounded-xl ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
              <Loader2 className="w-4 h-4 animate-spin text-cyan-500" />
              <p className={`text-[11px] ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>
                Waiting for first reading from {device.deviceName}…
              </p>
            </div>
          )}

          {results.map(r => (
            <VitalRow
              key={r.key}
              result={r}
              skipped={skipVitals?.has(r.key) === true}
              isDark={isDark}
            />
          ))}

          <p className={`text-[10px] mt-2 ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>
            🟢 = stable · 🟡 = stabilizing · 🔴 = no signal. Only 🟢 vitals are pulled into the form.
            Manual entry remains available for any vital you skip.
          </p>
        </div>

        {/* Footer */}
        <div className={`px-6 py-4 flex items-center justify-end gap-2 ${isDark ? 'bg-white/5' : 'bg-slate-50'}`}>
          <button
            onClick={() => setReadings([])}
            disabled={readings.length === 0}
            className={`px-3 py-2 text-[11px] font-bold rounded-xl transition-colors flex items-center gap-1.5 disabled:opacity-40 ${
              isDark ? 'bg-white/10 text-white hover:bg-white/20' : 'bg-slate-200 text-slate-700 hover:bg-slate-300'
            }`}
            title="Discard the current window and restart stability detection"
          >
            <RefreshCcw className="w-3.5 h-3.5" /> Reset
          </button>
          <button
            onClick={onClose}
            className={`px-4 py-2 text-xs font-bold rounded-xl transition-colors ${
              isDark ? 'bg-white/10 text-white hover:bg-white/20' : 'bg-slate-200 text-slate-700 hover:bg-slate-300'
            }`}
          >
            Cancel
          </button>
          <button
            onClick={handleUse}
            disabled={!anyStable}
            className="px-4 py-2 text-xs font-bold rounded-xl bg-gradient-to-r from-cyan-600 to-cyan-500 text-white shadow-lg hover:-translate-y-0.5 transition-all flex items-center gap-2 disabled:opacity-50 disabled:hover:translate-y-0"
            title={anyStable ? 'Pull stable values into the triage form' : 'Wait until at least one vital is stable'}
          >
            <Zap className="w-3.5 h-3.5" /> Use these values
          </button>
        </div>
      </div>
    </div>
  );
}

interface RowProps {
  result: VitalStabilityResult;
  skipped: boolean;
  isDark: boolean;
}

function VitalRow({ result, skipped, isDark }: RowProps) {
  const cfg = VITAL_TOLERANCES[result.key];
  const palette =
    result.state === 'stable'      ? { bg: 'bg-emerald-500/10', border: 'border-emerald-500/30', text: 'text-emerald-600', icon: <CheckCircle className="w-4 h-4 text-emerald-500" /> }
    : result.state === 'stabilizing' ? { bg: 'bg-amber-500/10',  border: 'border-amber-500/30',   text: 'text-amber-600',  icon: <Loader2 className="w-4 h-4 text-amber-500 animate-spin" /> }
    : /* unstable */                  { bg: 'bg-red-500/10',     border: 'border-red-500/30',     text: 'text-red-500',    icon: <AlertTriangle className="w-4 h-4 text-red-500" /> };

  const display =
    result.state === 'stable' && result.value != null
      ? formatVital(result.value, result.key)
      : result.latest != null
        ? `${formatVital(result.latest, result.key)} (drifting)`
        : '—';

  return (
    <div className={`flex items-center justify-between gap-3 px-3 py-2 rounded-xl border ${palette.bg} ${palette.border}`}>
      <div className="flex items-center gap-2 min-w-0">
        {palette.icon}
        <div className="min-w-0">
          <p className={`text-xs font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{cfg.label}</p>
          <p className={`text-[10px] ${palette.text}`}>
            {skipped
              ? 'Skipped — manual value already entered'
              : result.state === 'stable'
                ? `Stable — ${result.agreeingCount} agreeing readings within ±${cfg.tolerance}${cfg.unit === '°C' ? '' : ' '}${cfg.unit}`
                : result.state === 'stabilizing'
                  ? `Stabilizing — ${result.agreeingCount}/3 agreeing readings`
                  : (result.reason ?? 'Unstable signal')}
          </p>
        </div>
      </div>
      <div className={`text-sm font-mono font-bold whitespace-nowrap ${palette.text}`}>
        {display}
      </div>
    </div>
  );
}
