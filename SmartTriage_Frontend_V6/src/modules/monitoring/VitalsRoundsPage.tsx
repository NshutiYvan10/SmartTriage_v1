/* ═══════════════════════════════════════════════════════════════
   VitalsRoundsPage — the obs-round worklist for chair-based zones.

   Patients in GENERAL/AMBULATORY sit in chairs, not monitored beds.
   Their safety net is the reassessment clock: every clocked patient
   (not on a continuous monitor) appears here with a due/overdue
   countdown, soonest-due first. The nurse works the list top-down:
   Start check → wheel the shared roaming monitor to the patient →
   attach probes. The spot-check session self-completes once one
   validated full vitals set lands (≈90 s + a BP cycle), which resets
   the clock server-side — the row's countdown restarts on the next
   refresh. A bad reading rides the SAME pipeline as bed monitors:
   deterioration analysis, auto-retriage, and the pending zone
   transfer to Acute/Resus.

   Ratified intervals (2026-07-27): RED 0 (belongs on a monitor) ·
   ORANGE 30 · YELLOW 60 · GREEN 120 minutes.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity, AlarmClock, AlertTriangle, CheckCircle2, Clock, Loader2,
  MonitorSpeaker, RefreshCw, Stethoscope, Timer, X,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { recheckApi, type RecheckWorklistItem } from '@/api/recheck';
import { iotApi } from '@/api/iot';
import type { DeviceResponse, EdZone } from '@/api/types';
import { bedsApi } from '@/api/beds';
import { chartPath } from '@/lib/chartNav';
import { ModalPortal } from '@/components/ModalPortal';

const ZONE_FILTERS: { key: EdZone | 'ALL'; label: string }[] = [
  { key: 'GENERAL', label: 'General' },
  { key: 'AMBULATORY', label: 'Ambulatory' },
  { key: 'OBSERVATION', label: 'Observation' },
  { key: 'ALL', label: 'All zones' },
];

const CATEGORY_STYLE: Record<string, { pill: string; ring: string }> = {
  RED:    { pill: 'bg-red-500/15 text-red-600 border-red-500/40',          ring: 'text-red-500' },
  ORANGE: { pill: 'bg-orange-500/15 text-orange-600 border-orange-500/40', ring: 'text-orange-500' },
  YELLOW: { pill: 'bg-amber-400/20 text-amber-700 border-amber-500/40',    ring: 'text-amber-500' },
  GREEN:  { pill: 'bg-emerald-500/15 text-emerald-600 border-emerald-500/40', ring: 'text-emerald-500' },
};

/** "due in 42 min" / "OVERDUE 13 min" from the server-computed field. */
function dueLabel(item: RecheckWorklistItem): string {
  if (item.minutesUntilDue < 0) return `OVERDUE ${-item.minutesUntilDue} min`;
  if (item.minutesUntilDue === 0) return 'DUE NOW';
  return `due in ${item.minutesUntilDue} min`;
}

export function VitalsRoundsPage() {
  const navigate = useNavigate();
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId ?? '';

  const [zone, setZone] = useState<EdZone | 'ALL'>('GENERAL');
  const [items, setItems] = useState<RecheckWorklistItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState<RecheckWorklistItem | null>(null);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    setError(null);
    try {
      const list = await recheckApi.worklist(hospitalId, zone === 'ALL' ? undefined : zone);
      setItems(Array.isArray(list) ? list : []);
    } catch (err: any) {
      console.error('[VitalsRounds] failed to load', err);
      setError(err?.message ?? 'Failed to load the vitals-round worklist');
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, zone]);

  useEffect(() => { load(); }, [load]);

  // Auto-refresh — 30 s keeps countdowns honest without hammering the API.
  useEffect(() => {
    const interval = setInterval(() => load(), 30_000);
    return () => clearInterval(interval);
  }, [load]);

  const overdueCount = useMemo(() => items.filter((i) => i.overdue).length, [items]);
  const dueSoonCount = useMemo(
    () => items.filter((i) => !i.overdue && i.minutesUntilDue <= 15).length, [items]);
  const inProgressCount = useMemo(() => items.filter((i) => i.checkInProgress).length, [items]);

  return (
    <div className="min-h-full p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
      {/* Header */}
      <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <AlarmClock className="w-5 h-5 text-cyan-300" />
            </div>
            <div>
              <h1 className="text-lg font-bold text-white tracking-tight leading-tight">
                Vitals Rounds
              </h1>
              <p className="text-sm mt-0.5 text-white/50">
                Scheduled rechecks for patients off continuous monitoring
                {overdueCount > 0 && (
                  <span className="ml-2 text-red-300 font-bold">· {overdueCount} overdue</span>
                )}
                {dueSoonCount > 0 && (
                  <span className="ml-2 text-amber-300 font-bold">· {dueSoonCount} due ≤15 min</span>
                )}
                {inProgressCount > 0 && (
                  <span className="ml-2 text-cyan-300 font-bold">· {inProgressCount} in progress</span>
                )}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {ZONE_FILTERS.map((z) => (
              <button
                key={z.key}
                type="button"
                onClick={() => setZone(z.key)}
                className={`px-3 py-1.5 text-xs font-bold rounded-xl transition-colors ${
                  zone === z.key
                    ? 'bg-cyan-500 text-white'
                    : 'bg-white/10 text-white/70 hover:bg-white/20'
                }`}
              >
                {z.label}
              </button>
            ))}
            <button
              type="button"
              onClick={load}
              className="inline-flex items-center gap-2 px-3 py-1.5 text-xs font-bold rounded-xl bg-white/10 text-white hover:bg-white/20"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
              Refresh
            </button>
          </div>
        </div>
      </div>

      {error && (
        <div className={`rounded-xl p-3 border border-red-500/30 bg-red-500/10 text-xs ${isDark ? 'text-red-300' : 'text-red-700'}`}>
          {error}
          {zone === 'ALL' && (
            <span className="ml-1 opacity-80">
              (the all-zones view needs charge-nurse / admin visibility — pick your zone above)
            </span>
          )}
        </div>
      )}

      {/* Worklist */}
      {loading && items.length === 0 ? (
        <div className="rounded-2xl p-10 text-center" style={glassCard}>
          <Loader2 className="w-6 h-6 mx-auto mb-2 text-cyan-500 animate-spin" />
          <p className={`text-sm ${text.muted}`}>Loading the round…</p>
        </div>
      ) : items.length === 0 && !error ? (
        <div className="rounded-2xl p-10 text-center" style={glassCard}>
          <CheckCircle2 className="w-8 h-8 mx-auto mb-2 text-emerald-500" />
          <p className={`text-sm font-semibold ${text.heading}`}>Round complete.</p>
          <p className={`text-xs mt-1 ${text.muted}`}>
            No patients on the recheck clock in {zone === 'ALL' ? 'any zone' : `the ${zone} zone`} —
            everyone is either freshly assessed or on a continuous monitor.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {items.map((item) => {
            const cat = CATEGORY_STYLE[item.category] ?? CATEGORY_STYLE.GREEN;
            const dueSoon = !item.overdue && item.minutesUntilDue <= 15;
            return (
              <div
                key={item.visitId}
                className={`rounded-2xl p-4 border ${
                  item.overdue
                    ? 'border-red-500/50 bg-red-500/5'
                    : dueSoon
                      ? 'border-amber-500/40 bg-amber-500/5'
                      : (isDark ? 'border-slate-700' : 'border-slate-200')
                }`}
                style={glassInner}
              >
                <div className="flex flex-wrap items-center gap-3">
                  <div className="flex-1 min-w-[240px]">
                    <div className="flex items-center gap-2 flex-wrap">
                      <button
                        type="button"
                        onClick={() => navigate(chartPath(item.visitId))}
                        className={`text-sm font-bold hover:underline text-left ${text.heading}`}
                        title="Open patient chart"
                      >
                        {item.patientName}
                      </button>
                      <span className={`inline-flex items-center px-2 py-0.5 text-[10px] font-bold rounded-lg border ${cat.pill}`}>
                        {item.category}
                      </span>
                      {item.isPediatric && (
                        <span className="inline-flex items-center px-2 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-pink-600 bg-pink-500/10 border border-pink-500/20">
                          Peds
                        </span>
                      )}
                      <span className={`text-[11px] ${text.muted}`}>
                        {item.zone ?? '—'}{item.bedCode ? ` · ${item.bedCode}` : ' · seated'}
                        {' · '}#{item.visitNumber}
                        {item.tewsScore != null && ` · TEWS ${item.tewsScore}`}
                      </span>
                    </div>
                    <div className={`mt-1.5 flex items-center gap-2 text-[11px] flex-wrap ${text.muted}`}>
                      <Clock className="w-3 h-3" />
                      Last assessed {new Date(item.lastAssessedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      <span>·</span>
                      <span>recheck q{item.intervalMinutes} min</span>
                      <span>·</span>
                      <span className={`inline-flex items-center gap-1 font-bold ${
                        item.overdue
                          ? `${isDark ? 'text-red-300' : 'text-red-700'} ${-item.minutesUntilDue >= item.intervalMinutes ? 'animate-pulse' : ''}`
                          : dueSoon
                            ? (isDark ? 'text-amber-300' : 'text-amber-700')
                            : ''
                      }`}>
                        {item.overdue && <AlertTriangle className="w-3 h-3" />}
                        <Timer className="w-3 h-3" />
                        {dueLabel(item)}
                      </span>
                    </div>
                  </div>

                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => navigate(chartPath(item.visitId))}
                      className={`inline-flex items-center gap-1 px-2.5 py-1.5 text-[11px] font-bold rounded-xl border ${isDark ? 'border-slate-600 text-slate-200 hover:bg-white/5' : 'border-slate-300 text-slate-700 hover:bg-slate-100'}`}
                    >
                      <Stethoscope className="w-3 h-3" />
                      Chart
                    </button>
                    {item.checkInProgress ? (
                      <span className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-xl bg-cyan-500/15 text-cyan-600 border border-cyan-500/40">
                        <Activity className="w-3 h-3 animate-pulse" />
                        Check in progress
                      </span>
                    ) : (
                      <button
                        type="button"
                        onClick={() => setChecking(item)}
                        className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-xl text-white ${
                          item.overdue ? 'bg-red-600 hover:bg-red-700' : 'bg-cyan-600 hover:bg-cyan-700'
                        }`}
                        title="Open a spot-check session with the roaming monitor"
                      >
                        <MonitorSpeaker className="w-3 h-3" />
                        Start check
                      </button>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {checking && (
        <StartCheckDialog
          item={checking}
          hospitalId={hospitalId}
          startedByName={user?.fullName || undefined}
          onStarted={load}
          onClose={() => setChecking(null)}
        />
      )}
    </div>
  );
}

/* ─── StartCheckDialog ──────────────────────────────────────────
   Tier-1 patient↔monitor linking: pick the roaming monitor, confirm
   the patient's identity, start a SPOT_CHECK session. Only genuinely
   roaming monitors are offered — bed-mounted and triage-station
   devices are excluded (they belong to their spaces).               */
function StartCheckDialog({
  item, hospitalId, startedByName, onStarted, onClose,
}: {
  item: RecheckWorklistItem;
  hospitalId: string;
  startedByName?: string;
  onStarted: () => Promise<void> | void;
  onClose: () => void;
}) {
  const { glassCard, isDark, text } = useTheme();
  const [devices, setDevices] = useState<DeviceResponse[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [available, beds] = await Promise.all([
          iotApi.getAvailableDevices(hospitalId),
          bedsApi.getBedsForHospital(hospitalId),
        ]);
        if (cancelled) return;
        const bedMounted = new Set(
          (beds ?? []).map((b) => b.assignedDeviceId).filter(Boolean) as string[]);
        const roaming = (available ?? []).filter(
          (d) =>
            d.deviceType === 'ESP32_MONITOR' &&
            !d.triageMonitor &&
            !bedMounted.has(d.id));
        setDevices(roaming);
        setSelected(roaming[0]?.id ?? null);
      } catch (e: any) {
        if (cancelled) return;
        setLoadError(e?.message ?? 'Failed to load available monitors');
        setDevices([]);
      }
    })();
    return () => { cancelled = true; };
  }, [hospitalId]);

  const start = async () => {
    if (!selected || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await iotApi.startMonitoring({
        deviceId: selected,
        visitId: item.visitId,
        startedByName,
        spotCheck: true,
      });
      await onStarted();
      onClose();
    } catch (e: any) {
      setError(e?.message ?? 'Failed to start the spot check');
      setSubmitting(false);
    }
  };

  return (
    <ModalPortal>
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
      onClick={onClose}
    >
      <div
        style={glassCard}
        className="w-full max-w-md rounded-2xl shadow-2xl overflow-hidden mx-4 animate-scale-in flex flex-col max-h-[90vh]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4 flex items-center justify-between flex-shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <MonitorSpeaker className="w-5 h-5 text-cyan-400" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-white">Start spot check</h3>
              <p className="text-[10px] text-white/50 mt-0.5">
                {item.patientName} · #{item.visitNumber} · {item.zone ?? '—'}
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

        <div className="px-5 py-4 space-y-4 overflow-y-auto">
          {/* Identity confirmation — the classic spot-check error is vitals
              charted to the wrong patient. Make the who explicit. */}
          <div className={`rounded-xl px-3 py-2.5 border ${isDark ? 'border-slate-600 bg-slate-800/50' : 'border-slate-200 bg-slate-50'}`}>
            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Confirm the patient</p>
            <p className={`text-sm font-bold mt-0.5 ${text.heading}`}>{item.patientName}</p>
            <p className={`text-[11px] ${text.muted}`}>
              {item.category} · visit #{item.visitNumber}
              {item.isPediatric ? ' · pediatric' : ''}
            </p>
          </div>

          {devices == null ? (
            <div className="py-4 text-center">
              <Loader2 className="w-5 h-5 mx-auto animate-spin text-cyan-500" />
              <p className={`text-xs mt-2 ${text.muted}`}>Finding a free roaming monitor…</p>
            </div>
          ) : devices.length === 0 ? (
            <div className="rounded-xl bg-amber-500/10 border border-amber-500/30 px-3 py-3 flex items-start gap-2.5">
              <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
              <p className={`text-xs leading-relaxed ${isDark ? 'text-amber-200' : 'text-amber-800'}`}>
                <span className="font-bold">No roaming monitor is free right now.</span>{' '}
                {loadError ? loadError + ' ' : ''}
                Finish the check in progress first, or record this patient's
                vitals manually from their chart — manual vitals reset the
                clock too.
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              <p className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Roaming monitor</p>
              {devices.map((d) => {
                const isSel = selected === d.id;
                return (
                  <button
                    key={d.id}
                    type="button"
                    onClick={() => setSelected(d.id)}
                    className={`w-full text-left rounded-xl px-3 py-2.5 border transition-all ${
                      isSel
                        ? `border-cyan-500 ring-2 ring-cyan-500/30 ${isDark ? 'bg-white/10' : 'bg-white'}`
                        : (isDark ? 'border-slate-600 hover:bg-white/5' : 'border-slate-200 hover:bg-slate-50')
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-sm font-bold ${text.heading}`}>{d.deviceName}</span>
                      {isSel && <CheckCircle2 className="w-4 h-4 text-cyan-500" />}
                    </div>
                    <p className={`text-[10px] mt-0.5 ${text.muted}`}>
                      {d.serialNumber}
                      {d.batteryLevel != null && ` · battery ${d.batteryLevel}%`}
                    </p>
                  </button>
                );
              })}
            </div>
          )}

          <p className={`text-[11px] leading-relaxed ${text.muted}`}>
            Attach the probes once you're at the patient. The check completes
            itself when a full vitals set is captured (≈2 min) and frees the
            monitor for the next patient; an abnormal reading escalates
            through the normal deterioration workflow.
          </p>

          {error && <p className="text-[11px] text-red-500 font-semibold">{error}</p>}
        </div>

        <div className={`px-5 py-3.5 flex items-center justify-end gap-2 border-t flex-shrink-0 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className={`px-3.5 py-2 text-xs font-bold rounded-xl border ${isDark ? 'border-slate-600 text-slate-200 hover:bg-white/5' : 'border-slate-300 text-slate-700 hover:bg-slate-100'}`}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={start}
            disabled={submitting || !selected}
            className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Activity className="w-3.5 h-3.5" />}
            Start spot check
          </button>
        </div>
      </div>
    </div>
    </ModalPortal>
  );
}

export default VitalsRoundsPage;
