/* ═══════════════════════════════════════════════════════════════
   VitalMonitoring — the FULL monitoring view for one patient.

   Rebuilt from scratch. The previous version had NO data pipeline of
   its own: it read whatever the vital store happened to hold (which
   only updated while the Constant Monitoring page was mounted), its
   "auto-refresh" interval was an empty function, and it derived its
   own client-side trend that contradicted the server's. Hence the
   reported symptoms: frozen values, page-switching to refresh, and
   trend labels that didn't match reality.

   This version owns its data end to end:
     - seeds the waveform buffer from GET /iot/stream/recent
     - streams live readings over /topic/vitals/{visitId}
     - streams trend + detection over /topic/trend/{visitId}
       (server-authoritative — no client-side trend derivation)
     - polls the session every 5 s (state transitions, detection
       annotation, device battery) as the WS fallback
     - seeds alerts from GET /alerts/visit and streams new ones over
       /topic/alerts/{hospitalId}, with inline acknowledge
     - full session controls (Start / Pause / Resume / End)

   Detections are first-class: when the engine annotates the session
   with a pattern (e.g. SEPSIS_PATTERN) a labelled banner renders at
   the top with the clinical next step — no digging through panels.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Activity, AlertTriangle, ArrowLeft, BatteryMedium, CheckCircle2,
  Gauge, HeartPulse, History, Loader2, MonitorSpeaker, Pause, Play,
  ShieldAlert, Square, Stethoscope, Thermometer, TrendingDown,
  TrendingUp, Minus, Wifi, Wind,
} from 'lucide-react';
import {
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip,
  CartesianGrid, Legend, ReferenceLine,
} from 'recharts';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { usePatientStore, visitResponseToPatient } from '@/store/patientStore';
import type { Patient } from '@/types';
import { iotApi } from '@/api/iot';
import { alertApi } from '@/api/alerts';
import { visitApi } from '@/api/visits';
import type {
  ClinicalAlertResponse, DeviceSessionResponse, MonitoringEventResponse,
  MonitoringInsightsResponse, VitalStreamResponse,
} from '@/api/types';
import {
  subscribeToVitals, subscribeToTrendChanges, subscribeToAlerts,
} from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import MonitoringStatePill from '@/modules/monitoring/MonitoringStatePill';
import StartMonitoringConfirmModal from '@/modules/monitoring/StartMonitoringConfirmModal';
import EndMonitoringConfirmModal from '@/modules/monitoring/EndMonitoringConfirmModal';
import { ClinicalNotesPanel } from './ClinicalNotesPanel';
import { chartPath } from '@/lib/chartNav';

/** Ring-buffer size for the waveform charts (~20 min at 5 s cadence). */
const BUFFER_MAX = 240;

/** Human labels + guidance for the engine's detection patterns. */
const PATTERN_META: Record<string, { label: string; hint: string; sepsis?: boolean }> = {
  SEPSIS_PATTERN: {
    label: 'SEPSIS PATTERN DETECTED',
    hint: 'Fever + tachycardia/tachypnoea constellation on the monitor. Run a formal sepsis screening and start the bundle if confirmed.',
    sepsis: true,
  },
  SPO2_OVERRIDE: {
    label: 'SpO₂ CRITICAL',
    hint: 'Oxygen saturation below 92% (Rwanda protocol RED). Assess airway and start oxygen.',
  },
  SINGLE_VITAL_CRITICAL: {
    label: 'CRITICAL VITAL',
    hint: 'A vital sign is in the critical band, confirmed across consecutive readings.',
  },
  MULTI_VITAL_TREND: {
    label: 'MULTIPLE ABNORMAL VITALS',
    hint: 'Two or more vitals are simultaneously abnormal. Bedside review advised.',
  },
  RAPID_DECLINE: {
    label: 'RAPID DETERIORATION',
    hint: 'A key vital is changing quickly toward a dangerous range.',
  },
  HEMODYNAMIC_INSTABILITY: {
    label: 'HEMODYNAMIC INSTABILITY',
    hint: 'Blood-pressure pattern suggests circulatory compromise.',
  },
  RESPIRATORY_FAILURE_PATTERN: {
    label: 'RESPIRATORY FAILURE PATTERN',
    hint: 'Respiratory parameters suggest impending failure. Immediate review.',
  },
};

const TREND_META = {
  WORSENING: { label: 'Worsening', cls: 'bg-red-500/15 text-red-600 border-red-500/40', icon: TrendingUp },
  STABLE: { label: 'Stable', cls: 'bg-emerald-500/15 text-emerald-600 border-emerald-500/40', icon: Minus },
  IMPROVING: { label: 'Improving', cls: 'bg-cyan-500/15 text-cyan-600 border-cyan-500/40', icon: TrendingDown },
  UNKNOWN: { label: 'Assessing…', cls: 'bg-slate-500/15 text-slate-500 border-slate-400/40', icon: Minus },
} as const;

const CATEGORY_PILL: Record<string, string> = {
  RED: 'bg-red-500/15 text-red-600 border-red-500/40',
  ORANGE: 'bg-orange-500/15 text-orange-600 border-orange-500/40',
  YELLOW: 'bg-amber-400/20 text-amber-700 border-amber-500/40',
  GREEN: 'bg-emerald-500/15 text-emerald-600 border-emerald-500/40',
};

/* Glucose is deliberately NOT a monitor vital: the bedside monitor has no
   glucometer. Glucose readings reach the chart through the glucometer/lab
   workflows (Hypoglycemia module) and the paramedic telemetry path. */
type VitalKey = 'heartRate' | 'spo2' | 'respiratoryRate' | 'systolicBp' | 'temperature';

/** Adult display thresholds: [criticalLow, warnLow, warnHigh, criticalHigh]. */
const BANDS: Record<VitalKey, [number, number, number, number]> = {
  heartRate: [40, 50, 110, 130],
  spo2: [88, 92, 101, 101],
  respiratoryRate: [6, 9, 20, 30],
  systolicBp: [70, 90, 160, 200],
  temperature: [34, 35.5, 38, 40],
};

function vitalStatus(key: VitalKey, value: number | null | undefined): 'critical' | 'warning' | 'normal' | 'none' {
  if (value == null) return 'none';
  const [cl, wl, wh, ch] = BANDS[key];
  if (value < cl || value >= ch) return 'critical';
  if (value < wl || value >= wh) return 'warning';
  return 'normal';
}

const STATUS_CLS = {
  critical: 'text-red-500',
  warning: 'text-amber-500',
  normal: 'text-emerald-500',
  none: 'text-slate-400',
} as const;

function fmtTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function VitalMonitoring() {
  const { patientId: visitId } = useParams<{ patientId: string }>();
  const navigate = useNavigate();
  const { glassCard, glassInner, isDark, text } = useTheme();
  const authUser = useAuthStore((s) => s.user);
  const storePatient = usePatientStore((state) => state.getPatient(visitId!));
  // Re-runs the subscription effects after the shared STOMP client is torn
  // down and rebuilt (app-level zone-change reconnects) — without this the
  // page's live feeds silently die on client replacement.
  const wsGeneration = useWebSocketGeneration();

  const [fetchedPatient, setFetchedPatient] = useState<Patient | null>(null);
  const patient = storePatient ?? fetchedPatient;

  const [session, setSession] = useState<DeviceSessionResponse | null>(null);
  const [sessionLoaded, setSessionLoaded] = useState(false);
  const [readings, setReadings] = useState<VitalStreamResponse[]>([]);
  const [alerts, setAlerts] = useState<ClinicalAlertResponse[]>([]);
  const [trend, setTrend] = useState<'WORSENING' | 'STABLE' | 'IMPROVING' | 'UNKNOWN'>('UNKNOWN');
  const [pattern, setPattern] = useState<string | null>(null);
  const [patternAt, setPatternAt] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showStart, setShowStart] = useState(false);
  const [showEnd, setShowEnd] = useState(false);
  const [nowTick, setNowTick] = useState(Date.now()); // freshness indicator
  // Clinical insights: journey timeline + TEWS trend + long-range charts +
  // arrival baseline. 'LIVE' charts the WS ring buffer (~20 min); hour
  // ranges chart server-side bucketed aggregates.
  const [range, setRange] = useState<'LIVE' | 2 | 6 | 12>('LIVE');
  const [insights, setInsights] = useState<MonitoringInsightsResponse | null>(null);
  // Monitoring event log (V119): the chronological record of engine
  // transitions. eventsBump forces a refetch when a WS nudge arrives.
  const [events, setEvents] = useState<MonitoringEventResponse[]>([]);
  const [eventsBump, setEventsBump] = useState(0);

  // ── Patient context: store first, fetch on miss (deep links) ──
  useEffect(() => {
    if (storePatient || !visitId) return;
    let cancelled = false;
    visitApi.getById(visitId)
      .then((v) => { if (!cancelled && v) setFetchedPatient(visitResponseToPatient(v)); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [storePatient, visitId]);

  // ── Session: fetch now + every 5 s (WS fallback for state/battery/detection) ──
  const refreshSession = useCallback(async () => {
    if (!visitId) return;
    try {
      const s = await iotApi.getActiveSessionForVisit(visitId);
      setSession(s ?? null);
      if (s) {
        if (s.trendStatus) setTrend(s.trendStatus as typeof trend);
        setPattern(s.lastDetectedPattern ?? null);
        setPatternAt(s.lastDetectedAt ?? null);
      }
    } catch { /* keep previous */ }
    finally { setSessionLoaded(true); }
  }, [visitId]);

  useEffect(() => {
    refreshSession();
    const iv = setInterval(refreshSession, 5000);
    return () => clearInterval(iv);
  }, [refreshSession]);

  // ── Waveform buffer: seed from history, then stream ──
  useEffect(() => {
    if (!visitId) return;
    let cancelled = false;
    iotApi.getRecentStream(visitId, 120)
      .then((list) => {
        if (cancelled || !Array.isArray(list)) return;
        const asc = [...list].sort(
          (a, b) => new Date(a.capturedAt).getTime() - new Date(b.capturedAt).getTime());
        setReadings(asc);
      })
      .catch(() => {});
    const unsub = subscribeToVitals(visitId, (vs) => {
      setReadings((prev) => {
        const next = [...prev, vs];
        return next.length > BUFFER_MAX ? next.slice(next.length - BUFFER_MAX) : next;
      });
    });
    return () => { cancelled = true; unsub(); };
  }, [visitId, wsGeneration]);

  // ── Trend + detection: instant over WS ──
  useEffect(() => {
    if (!visitId) return;
    const unsub = subscribeToTrendChanges(visitId, (ev) => {
      if (ev.trendStatus) setTrend(ev.trendStatus);
      if ('detectedPattern' in ev) {
        setPattern(ev.detectedPattern ?? null);
        setPatternAt(ev.detectedAt ?? null);
      }
      // Event-log nudge (V119): a new monitoring_events row was written —
      // refetch the history panel immediately instead of waiting for the poll.
      if ((ev as any).type === 'MONITORING_EVENT') setEventsBump((b) => b + 1);
    });
    return () => unsub();
  }, [visitId, wsGeneration]);

  // ── Alerts: seed + stream + ack ──
  useEffect(() => {
    if (!visitId) return;
    let cancelled = false;
    alertApi.getByVisit(visitId, 0, 30)
      .then((page) => { if (!cancelled && page?.content) setAlerts(page.content); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [visitId]);

  useEffect(() => {
    if (!authUser?.hospitalId || !visitId) return;
    const unsub = subscribeToAlerts(authUser.hospitalId, (alert: ClinicalAlertResponse) => {
      if (alert.visitId !== visitId) return;
      setAlerts((prev) => {
        if (prev.some((a) => a.id === alert.id)) {
          return prev.map((a) => (a.id === alert.id ? alert : a));
        }
        return [alert, ...prev];
      });
    });
    return () => unsub();
  }, [authUser?.hospitalId, visitId, wsGeneration]);

  const handleAck = useCallback(async (alertId: string) => {
    try {
      await alertApi.acknowledge(alertId);
      setAlerts((prev) => prev.map((a) => (a.id === alertId
        ? { ...a, acknowledged: true, acknowledgedAt: new Date().toISOString(), acknowledgedByName: authUser?.fullName ?? 'You' }
        : a)));
    } catch (e: any) {
      setError(e?.message ?? 'Failed to acknowledge alert');
    }
  }, [authUser?.fullName]);

  // Freshness ticker (re-render every second so "x s ago" stays honest).
  useEffect(() => {
    const iv = setInterval(() => setNowTick(Date.now()), 1000);
    return () => clearInterval(iv);
  }, []);

  // ── Clinical insights: fetch on mount/range change + refresh every 60 s ──
  useEffect(() => {
    if (!visitId) return;
    const hours = range === 'LIVE' ? 6 : range;
    const bucketMinutes = hours <= 2 ? 2 : hours <= 6 ? 5 : 10;
    let cancelled = false;
    const load = () => {
      iotApi.getMonitoringInsights(visitId, hours, bucketMinutes)
        .then((res) => { if (!cancelled && res) setInsights(res); })
        .catch(() => {});
    };
    load();
    const iv = setInterval(load, 60_000);
    return () => { cancelled = true; clearInterval(iv); };
  }, [visitId, range]);

  // ── Monitoring event log: fetch on mount + WS nudge + every 60 s ──
  useEffect(() => {
    if (!visitId) return;
    let cancelled = false;
    const load = () => {
      iotApi.getMonitoringEvents(visitId, 24)
        .then((list) => { if (!cancelled && Array.isArray(list)) setEvents(list); })
        .catch(() => {});
    };
    load();
    const iv = setInterval(load, 60_000);
    return () => { cancelled = true; clearInterval(iv); };
  }, [visitId, eventsBump]);

  // ── Session controls ──
  const handleStart = useCallback(async () => {
    if (!visitId) return;
    setBusy(true); setError(null);
    try {
      await iotApi.startMonitoringForVisit(visitId, authUser?.fullName || 'Clinician');
      await refreshSession();
      setTimeout(refreshSession, 700);
    } catch (e: any) {
      setError(e?.message ?? 'Failed to start monitoring');
      throw e;
    } finally { setBusy(false); }
  }, [visitId, authUser?.fullName, refreshSession]);

  const handlePauseResume = useCallback(async () => {
    if (!session) return;
    setBusy(true); setError(null);
    try {
      if (session.monitoringState === 'PAUSED') {
        await iotApi.resumeMonitoring(session.id, authUser?.fullName || 'Clinician');
        setTimeout(refreshSession, 700);
      } else {
        await iotApi.pauseMonitoring(session.id, authUser?.fullName || 'Clinician');
      }
      await refreshSession();
    } catch (e: any) {
      setError(e?.message ?? 'Failed to change monitoring state');
    } finally { setBusy(false); }
  }, [session, authUser?.fullName, refreshSession]);

  const handleEnd = useCallback(async () => {
    if (!session) return;
    await iotApi.stopMonitoring(session.id, authUser?.fullName || 'Clinician', 'Stopped by clinician');
    await refreshSession();
  }, [session, authUser?.fullName, refreshSession]);

  // ── Derived display data ──
  const latest = readings.length > 0 ? readings[readings.length - 1] : null;
  const lastReadingAgeSec = latest
    ? Math.max(0, Math.round((nowTick - new Date(latest.capturedAt).getTime()) / 1000))
    : null;

  const liveChartData = useMemo(() => readings.map((r) => ({
    t: fmtTime(r.capturedAt),
    hr: r.heartRate,
    spo2: r.spo2,
    rr: r.respiratoryRate,
    sbp: r.systolicBp,
    dbp: r.diastolicBp,
    temp: r.temperature,
  })), [readings]);

  const bucketChartData = useMemo(() => (insights?.buckets ?? []).map((b) => ({
    t: new Date(b.start).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    hr: b.hr, spo2: b.spo2, rr: b.rr, sbp: b.sbp, dbp: b.dbp, temp: b.temp,
    tews: b.tews,
  })), [insights]);

  // LIVE charts the WS ring buffer; hour ranges chart bucketed history.
  const chartData = range === 'LIVE' ? liveChartData : bucketChartData;

  // Arrival-baseline deltas — the "how have they changed since triage"
  // chips on the tiles. Only significant changes render (noise floor per
  // vital) so the tiles stay calm on a stable patient.
  const baseline = insights?.baseline ?? null;
  const deltaFor = (cur: number | null | undefined, base: number | null | undefined, floor: number): number | null => {
    if (cur == null || base == null) return null;
    const d = cur - base;
    return Math.abs(d) >= floor ? d : null;
  };

  // Mean arterial pressure — what clinicians track for perfusion.
  const map = latest?.systolicBp != null && latest?.diastolicBp != null
    ? Math.round((latest.systolicBp + 2 * latest.diastolicBp) / 3)
    : null;
  // BP status considers BOTH components (a diastolic of 125 or 38 must
  // not render green just because systolic is fine).
  const dbpStatus: 'critical' | 'warning' | 'normal' | 'none' =
    latest?.diastolicBp == null ? 'none'
      : latest.diastolicBp < 40 || latest.diastolicBp > 120 ? 'critical'
      : latest.diastolicBp < 50 || latest.diastolicBp > 100 ? 'warning'
      : 'normal';
  const bpStatus = (['critical', 'warning', 'normal', 'none'] as const)
    .find((s) => s === vitalStatus('systolicBp', latest?.systolicBp) || s === dbpStatus)!;

  const sortedAlerts = useMemo(() => {
    return [...alerts].sort((a, b) => {
      if (a.acknowledged !== b.acknowledged) return a.acknowledged ? 1 : -1;
      return new Date(b.createdAt ?? 0).getTime() - new Date(a.createdAt ?? 0).getTime();
    });
  }, [alerts]);
  const unackedCount = useMemo(() => alerts.filter((a) => !a.acknowledged).length, [alerts]);

  const streaming = session != null
    && ['STARTING', 'LIVE', 'DEGRADED', 'STALLED'].includes(session.monitoringState);
  const trendMeta = TREND_META[trend] ?? TREND_META.UNKNOWN;
  const TrendIcon = trendMeta.icon;
  const patternMeta = pattern ? (PATTERN_META[pattern] ?? {
    label: pattern.replace(/_/g, ' '),
    hint: 'The monitoring engine flagged this pattern. Bedside review advised.',
  }) : null;

  if (!visitId) return null;

  if (!patient) {
    return (
      <div className="min-h-full flex items-center justify-center p-6">
        <div className="text-center">
          <Loader2 className="w-8 h-8 mx-auto mb-3 animate-spin text-cyan-500" />
          <p className={`text-sm ${text.muted}`}>Loading patient…</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-full p-4 lg:p-6 max-w-[1500px] mx-auto space-y-4 animate-fade-in">
      {/* ── Header ── */}
      <div className="rounded-3xl overflow-hidden" style={glassCard}>
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => navigate('/monitoring')}
              className="w-9 h-9 rounded-xl bg-white/10 hover:bg-white/20 flex items-center justify-center"
              aria-label="Back to monitoring"
            >
              <ArrowLeft className="w-4 h-4 text-white" />
            </button>
            <div className="flex-1 min-w-[220px]">
              <div className="flex items-center gap-2 flex-wrap">
                <h1 className="text-lg font-bold text-white tracking-tight">{patient.fullName}</h1>
                {patient.category && (
                  <span className={`inline-flex items-center px-2 py-0.5 text-[10px] font-bold rounded-lg border ${CATEGORY_PILL[patient.category] ?? ''}`}>
                    {patient.category}
                  </span>
                )}
                <span className={`inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-bold rounded-lg border ${trendMeta.cls}`}>
                  <TrendIcon className="w-3 h-3" />
                  {trendMeta.label}
                </span>
              </div>
              <p className="text-[11px] text-white/50 mt-0.5">
                {patient.age} y · {patient.gender}
                {patient.currentEdZone ? ` · ${patient.currentEdZone}` : ''}
                {patient.currentBedLabel ? ` · Bed ${patient.currentBedLabel}` : ''}
                {' · '}
                <button type="button" className="underline hover:text-white/80" onClick={() => navigate(chartPath(visitId))}>
                  Open chart
                </button>
              </p>
            </div>

            {/* Session state + device + controls */}
            <div className="flex items-center gap-2 flex-wrap">
              {session && (
                <span className="inline-flex items-center gap-2 px-2.5 py-1 rounded-xl bg-white/10 text-[11px] text-white/80">
                  <MonitorSpeaker className="w-3.5 h-3.5" />
                  {session.deviceName ?? session.deviceSerialNumber}
                  {latest?.batteryLevel != null && (
                    <span className="inline-flex items-center gap-0.5"><BatteryMedium className="w-3 h-3" />{latest.batteryLevel}%</span>
                  )}
                  {latest?.wifiRssi != null && (
                    <span className="inline-flex items-center gap-0.5"><Wifi className="w-3 h-3" />{latest.wifiRssi} dBm</span>
                  )}
                </span>
              )}
              <MonitoringStatePill state={session?.monitoringState ?? 'NOT_STARTED'} />
              {streaming && (
                <span className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-xl text-[10px] font-bold ${
                  lastReadingAgeSec != null && lastReadingAgeSec <= 15
                    ? 'bg-emerald-500/20 text-emerald-300'
                    : 'bg-amber-500/20 text-amber-300'
                }`}>
                  <span className={`w-1.5 h-1.5 rounded-full ${lastReadingAgeSec != null && lastReadingAgeSec <= 15 ? 'bg-emerald-400 animate-pulse' : 'bg-amber-400'}`} />
                  {lastReadingAgeSec == null ? 'no data' : lastReadingAgeSec <= 15 ? `data · ${lastReadingAgeSec}s` : `stalled · ${lastReadingAgeSec}s`}
                </span>
              )}
              {!session || session.monitoringState === 'ENDED' || session.monitoringState === 'NOT_STARTED' ? (
                <button
                  type="button"
                  disabled={busy || !sessionLoaded}
                  onClick={() => setShowStart(true)}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold rounded-xl bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50"
                >
                  <Play className="w-3.5 h-3.5" /> Start monitoring
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={handlePauseResume}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold rounded-xl bg-white/10 text-white hover:bg-white/20 disabled:opacity-50"
                  >
                    {session.monitoringState === 'PAUSED'
                      ? (<><Play className="w-3.5 h-3.5" /> Resume</>)
                      : (<><Pause className="w-3.5 h-3.5" /> Pause</>)}
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => setShowEnd(true)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold rounded-xl bg-red-500/20 border border-red-500/40 text-red-300 hover:bg-red-500/30 disabled:opacity-50"
                  >
                    <Square className="w-3 h-3" /> End
                  </button>
                </>
              )}
            </div>
          </div>
        </div>

        {/* ── Detection banner ── */}
        {patternMeta && (
          <div className={`px-5 py-3 flex flex-wrap items-center gap-3 border-t ${
            patternMeta.sepsis
              ? 'bg-purple-500/10 border-purple-500/30'
              : 'bg-red-500/10 border-red-500/30'
          }`}>
            <ShieldAlert className={`w-5 h-5 flex-shrink-0 ${patternMeta.sepsis ? 'text-purple-500' : 'text-red-500'} animate-pulse`} />
            <div className="flex-1 min-w-[240px]">
              <p className={`text-sm font-black tracking-wide ${patternMeta.sepsis ? (isDark ? 'text-purple-300' : 'text-purple-700') : (isDark ? 'text-red-300' : 'text-red-700')}`}>
                {patternMeta.label}
                {patternAt && (
                  <span className={`ml-2 text-[10px] font-semibold ${text.muted}`}>
                    since {new Date(patternAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  </span>
                )}
              </p>
              <p className={`text-xs mt-0.5 ${text.body}`}>{patternMeta.hint}</p>
            </div>
            {patternMeta.sepsis && (
              <button
                type="button"
                onClick={() => navigate(chartPath(visitId))}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-bold rounded-xl bg-purple-600 text-white hover:bg-purple-700"
              >
                <Stethoscope className="w-3.5 h-3.5" />
                Run sepsis screening
              </button>
            )}
          </div>
        )}
      </div>

      {error && (
        <div className={`rounded-xl p-3 border border-red-500/30 bg-red-500/10 text-xs ${isDark ? 'text-red-300' : 'text-red-700'}`}>
          {error}
        </div>
      )}

      {/* ── Body grid ── */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        {/* Left ⅔: vitals + charts */}
        <div className="xl:col-span-2 space-y-4">
          {/* Vital tiles — live values with arrival-since deltas.
              Six-column grid with each tile spanning two: the first row
              holds three tiles; the second row's pair starts at column 2
              so Blood pressure + Temperature sit CENTERED under the row
              above instead of leaving a hole on the right. */}
          <div className="grid grid-cols-2 md:grid-cols-6 gap-3">
            <VitalTile className="md:col-span-2" icon={HeartPulse} label="Heart rate" unit="bpm" value={latest?.heartRate} k="heartRate" data={liveChartData} dataKey="hr" delta={deltaFor(latest?.heartRate, baseline?.hr, 10)} glassInner={glassInner} text={text} />
            <VitalTile className="md:col-span-2" icon={Activity} label="SpO₂" unit="%" value={latest?.spo2} k="spo2" data={liveChartData} dataKey="spo2" delta={deltaFor(latest?.spo2, baseline?.spo2, 3)} glassInner={glassInner} text={text} />
            <VitalTile className="md:col-span-2" icon={Wind} label="Resp. rate" unit="/min" value={latest?.respiratoryRate} k="respiratoryRate" data={liveChartData} dataKey="rr" delta={deltaFor(latest?.respiratoryRate, baseline?.rr, 4)} glassInner={glassInner} text={text} />
            <VitalTile className="md:col-span-2 md:col-start-2" icon={Gauge} label="Blood pressure" unit="mmHg" value={latest?.systolicBp}
              secondary={latest?.diastolicBp != null ? `/${latest.diastolicBp}${map != null ? ` (${map})` : ''}` : undefined}
              k="systolicBp" data={liveChartData} dataKey="sbp"
              delta={deltaFor(latest?.systolicBp, baseline?.sbp, 15)}
              statusOverride={bpStatus}
              footnote={map != null ? `MAP ${map} mmHg` : undefined}
              glassInner={glassInner} text={text} />
            <VitalTile className="md:col-span-2" icon={Thermometer} label="Temperature" unit="°C" value={latest?.temperature} k="temperature" data={liveChartData} dataKey="temp" delta={deltaFor(latest?.temperature, baseline?.temp, 0.8)} glassInner={glassInner} text={text} />
          </div>

          {/* Patient journey — trend band + clinical event markers */}
          <JourneyTimeline insights={insights} glassCard={glassCard} isDark={isDark} text={text} />

          {/* Event history — the sequence of engine detections (V119) */}
          <EventHistory events={events} glassCard={glassCard} isDark={isDark} text={text} />

          {/* Range selector for the history charts */}
          <div className="flex items-center gap-2">
            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Charts:</span>
            {(['LIVE', 2, 6, 12] as const).map((r) => (
              <button
                key={String(r)}
                type="button"
                onClick={() => setRange(r)}
                className={`px-3 py-1 text-xs font-bold rounded-xl transition-colors ${
                  range === r
                    ? 'bg-cyan-600 text-white'
                    : (isDark ? 'bg-white/10 text-slate-300 hover:bg-white/20' : 'bg-slate-200/70 text-slate-600 hover:bg-slate-300/70')
                }`}
              >
                {r === 'LIVE' ? 'Live' : `${r} h`}
              </button>
            ))}
          </div>

          {/* TEWS trend — the deterioration story in one score */}
          {range !== 'LIVE' && bucketChartData.length >= 2 && (
            <TewsChart data={bucketChartData} glassCard={glassCard} isDark={isDark} text={text} />
          )}

          {/* Trend charts */}
          <TrendChart
            title="Heart rate & SpO₂"
            data={chartData}
            series={[
              { key: 'hr', name: 'HR (bpm)', color: '#ef4444' },
              { key: 'spo2', name: 'SpO₂ (%)', color: '#06b6d4' },
            ]}
            glassCard={glassCard} isDark={isDark} text={text}
          />
          <TrendChart
            title="Blood pressure & respiratory rate"
            data={chartData}
            series={[
              { key: 'sbp', name: 'Systolic (mmHg)', color: '#8b5cf6' },
              { key: 'dbp', name: 'Diastolic (mmHg)', color: '#a78bfa' },
              { key: 'rr', name: 'RR (/min)', color: '#f59e0b' },
            ]}
            glassCard={glassCard} isDark={isDark} text={text}
          />

          {/* Session accountability */}
          {session && (
            <div className={`rounded-2xl px-4 py-2.5 flex flex-wrap items-center gap-x-5 gap-y-1 text-[11px] ${text.muted}`} style={glassInner}>
              <span><span className={`font-bold ${text.heading}`}>{session.totalReadings}</span> readings</span>
              <span><span className={`font-bold ${text.heading}`}>{session.rejectedReadings}</span> rejected</span>
              <span><span className={`font-bold ${text.heading}`}>{session.alertsGenerated}</span> alerts</span>
              <span><span className={`font-bold ${text.heading}`}>{session.retriagesTriggered}</span> auto-retriages</span>
              <span>since {session.startedAt ? new Date(session.startedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—'}</span>
              {session.startedByName && <span>started by {session.startedByName}</span>}
            </div>
          )}
        </div>

        {/* Right ⅓: alerts + notes */}
        <div className="space-y-4">
          <div className="rounded-2xl overflow-hidden" style={glassCard}>
            <div className={`px-4 py-3 flex items-center justify-between border-b ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
              <p className={`text-sm font-bold ${text.heading}`}>
                Alerts
                {unackedCount > 0 && (
                  <span className="ml-2 inline-flex items-center px-2 py-0.5 text-[10px] font-bold rounded-full bg-red-500 text-white">
                    {unackedCount} unacknowledged
                  </span>
                )}
              </p>
            </div>
            <div className="max-h-[420px] overflow-y-auto divide-y divide-slate-500/10">
              {sortedAlerts.length === 0 ? (
                <div className="p-6 text-center">
                  <CheckCircle2 className="w-6 h-6 mx-auto mb-1.5 text-emerald-500" />
                  <p className={`text-xs ${text.muted}`}>No alerts for this patient.</p>
                </div>
              ) : sortedAlerts.map((a) => (
                <div key={a.id} className={`px-4 py-3 ${!a.acknowledged ? (isDark ? 'bg-red-500/5' : 'bg-red-50/60') : ''}`}>
                  <div className="flex items-start gap-2">
                    <span className={`mt-1 w-2 h-2 rounded-full flex-shrink-0 ${
                      a.severity === 'CRITICAL' ? 'bg-red-500' + (!a.acknowledged ? ' animate-pulse' : '')
                      : a.severity === 'HIGH' ? 'bg-orange-500'
                      : a.severity === 'MEDIUM' ? 'bg-amber-400' : 'bg-slate-400'
                    }`} />
                    <div className="flex-1 min-w-0">
                      <p className={`text-xs font-bold leading-snug ${text.heading}`}>{a.title ?? a.alertType}</p>
                      <p className={`text-[11px] mt-0.5 leading-snug ${text.body}`}>{a.message}</p>
                      <p className={`text-[10px] mt-1 ${text.muted}`}>
                        {a.createdAt ? new Date(a.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''}
                        {a.acknowledged && a.acknowledgedByName ? ` · ack by ${a.acknowledgedByName}` : ''}
                      </p>
                    </div>
                    {!a.acknowledged && (
                      <button
                        type="button"
                        onClick={() => handleAck(a.id)}
                        className="flex-shrink-0 px-2 py-1 text-[10px] font-bold rounded-lg bg-emerald-500/15 text-emerald-600 border border-emerald-500/30 hover:bg-emerald-500/25"
                      >
                        Ack
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <ClinicalNotesPanel visitId={visitId} />
        </div>
      </div>

      {showStart && (
        <StartMonitoringConfirmModal
          patientName={patient.fullName}
          bedCode={patient.currentBedLabel ?? null}
          deviceLabel={session?.deviceName ?? null}
          onConfirm={handleStart}
          onClose={() => setShowStart(false)}
        />
      )}
      {showEnd && (
        <EndMonitoringConfirmModal
          patientName={patient.fullName}
          onConfirm={handleEnd}
          onClose={() => setShowEnd(false)}
        />
      )}
    </div>
  );
}

/* ─── JourneyTimeline — how the patient has been doing over hours ──
   A horizontal band coloured by the per-bucket trend (red worsening /
   emerald stable / cyan improving) with clinical event markers pinned
   at their timestamps. This is the "story of the last N hours" a
   doctor asks for before anything else.                              */
const TREND_BAND_COLOR: Record<string, string> = {
  WORSENING: '#ef4444',
  STABLE: '#10b981',
  IMPROVING: '#06b6d4',
};

function JourneyTimeline({
  insights, glassCard, isDark, text,
}: {
  insights: MonitoringInsightsResponse | null;
  glassCard: any; isDark: boolean; text: any;
}) {
  if (!insights || insights.buckets.length === 0) {
    return (
      <div className="rounded-2xl p-4" style={glassCard}>
        <p className={`text-sm font-bold ${text.heading}`}>Patient journey</p>
        <p className={`text-xs mt-2 ${text.muted}`}>
          No monitoring history in this window yet — the journey band fills
          in as readings accumulate.
        </p>
      </div>
    );
  }

  const from = new Date(insights.fromTime).getTime();
  const to = new Date(insights.toTime).getTime();
  const span = Math.max(1, to - from);
  const pct = (iso: string) =>
    Math.min(100, Math.max(0, ((new Date(iso).getTime() - from) / span) * 100));
  const bucketWidthPct = (insights.bucketMinutes * 60_000 / span) * 100;

  const hoursLabel = Math.round(span / 3_600_000);

  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-center justify-between flex-wrap gap-2">
        <p className={`text-sm font-bold ${text.heading}`}>
          Patient journey
          <span className={`ml-2 text-[10px] font-semibold ${text.muted}`}>last {hoursLabel} h</span>
        </p>
        <div className={`flex items-center gap-3 text-[10px] font-semibold ${text.muted}`}>
          <span className="inline-flex items-center gap-1"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: TREND_BAND_COLOR.WORSENING }} /> Worsening</span>
          <span className="inline-flex items-center gap-1"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: TREND_BAND_COLOR.STABLE }} /> Stable</span>
          <span className="inline-flex items-center gap-1"><span className="w-2.5 h-2.5 rounded-sm" style={{ background: TREND_BAND_COLOR.IMPROVING }} /> Improving</span>
          <span className="inline-flex items-center gap-1"><span className={`w-2 h-2 rounded-full border-2 ${isDark ? 'border-white bg-slate-900' : 'border-slate-800 bg-white'}`} /> Event</span>
        </div>
      </div>

      {/* Band + markers */}
      <div className="relative mt-6 mb-2">
        <div className={`relative h-6 rounded-lg overflow-hidden ${isDark ? 'bg-slate-800' : 'bg-slate-200/70'}`}>
          {insights.buckets.map((b) => (
            <div
              key={b.start}
              className="absolute top-0 h-full"
              style={{
                left: `${pct(b.start)}%`,
                width: `${Math.max(bucketWidthPct, 0.5)}%`,
                background: TREND_BAND_COLOR[b.trend] ?? '#94a3b8',
                opacity: 0.9,
              }}
              title={`${new Date(b.start).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} — ${b.trend.toLowerCase()}${b.tews != null ? ` · TEWS ${b.tews}` : ''} · HR ${b.hr ?? '—'} · SpO₂ ${b.spo2 ?? '—'}`}
            />
          ))}
        </div>
        {/* Event markers */}
        {insights.events.map((e, i) => (
          <div
            key={`${e.at}-${i}`}
            className="absolute -top-3.5 flex flex-col items-center"
            style={{ left: `calc(${pct(e.at)}% - 5px)` }}
            title={`${new Date(e.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} — ${e.label}`}
          >
            <span className={`w-2.5 h-2.5 rounded-full border-2 ${isDark ? 'border-white' : 'border-slate-800'} ${
              e.severity === 'CRITICAL' || e.severity === 'RED' ? 'bg-red-500'
              : e.severity === 'HIGH' || e.severity === 'ORANGE' ? 'bg-orange-500'
              : 'bg-amber-400'
            }`} />
            <span className={`w-px h-2 ${isDark ? 'bg-white/50' : 'bg-slate-500/60'}`} />
          </div>
        ))}
      </div>
      <div className={`flex justify-between text-[10px] ${text.muted}`}>
        <span>{new Date(insights.fromTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
        <span>now</span>
      </div>

      {/* Event list — the markers, readable */}
      {insights.events.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {insights.events.slice(-6).map((e, i) => (
            <span
              key={`${e.at}-chip-${i}`}
              className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-lg text-[10px] font-semibold border ${
                e.severity === 'CRITICAL' || e.severity === 'RED'
                  ? 'border-red-500/40 bg-red-500/10 text-red-500'
                  : e.severity === 'HIGH' || e.severity === 'ORANGE'
                    ? 'border-orange-500/40 bg-orange-500/10 text-orange-500'
                    : (isDark ? 'border-slate-600 text-slate-300' : 'border-slate-300 text-slate-600')
              }`}
            >
              {new Date(e.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              <span className="font-bold">{e.label}</span>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

/* ─── EventHistory — the sequence of engine detections (V119) ──
   "What happened, in what order?" — the handover question. Every row is
   a recorded transition (pattern detected/changed/cleared, trend change,
   auto-retriage, session lifecycle), newest first, with the vitals that
   triggered it. Written even when the matching ALERT was dedup-
   suppressed, so this is the complete story, not just the paged one.  */
const EVENT_META: Record<string, { dot: string; textCls: string }> = {
  PATTERN_DETECTED: { dot: 'bg-red-500', textCls: 'text-red-500' },
  AUTO_RETRIAGE: { dot: 'bg-red-500', textCls: 'text-red-500' },
  TREND_CHANGED: { dot: 'bg-amber-500', textCls: 'text-amber-500' },
  PATTERN_CLEARED: { dot: 'bg-emerald-500', textCls: 'text-emerald-500' },
  SESSION_STARTED: { dot: 'bg-cyan-500', textCls: '' },
  SESSION_RESUMED: { dot: 'bg-cyan-500', textCls: '' },
  SESSION_PAUSED: { dot: 'bg-slate-400', textCls: '' },
  SESSION_ENDED: { dot: 'bg-slate-400', textCls: '' },
};

function EventHistory({
  events, glassCard, isDark, text,
}: {
  events: MonitoringEventResponse[]; glassCard: any; isDark: boolean; text: any;
}) {
  const newestFirst = [...events].reverse();
  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-center justify-between flex-wrap gap-2">
        <p className={`text-sm font-bold ${text.heading}`}>
          <History className="w-4 h-4 inline-block mr-1.5 -mt-0.5" />
          Event history
          <span className={`ml-2 text-[10px] font-semibold ${text.muted}`}>last 24 h · every detection, even when the alert was already open</span>
        </p>
        <span className={`text-[10px] font-semibold ${text.muted}`}>{newestFirst.length} events</span>
      </div>
      {newestFirst.length === 0 ? (
        <p className={`text-xs mt-2 ${text.muted}`}>
          Nothing recorded yet — entries appear the moment the monitoring
          engine detects, clears, or escalates anything.
        </p>
      ) : (
        <div className="mt-3 max-h-72 overflow-y-auto pr-1">
          <ol className={`relative border-l-2 ml-1.5 space-y-3 ${isDark ? 'border-slate-700' : 'border-slate-200'}`}>
            {newestFirst.map((e) => {
              const meta = EVENT_META[e.eventType] ?? { dot: 'bg-slate-400', textCls: '' };
              const vitals = [
                e.heartRate != null ? `HR ${e.heartRate}` : null,
                e.spo2 != null ? `SpO₂ ${e.spo2}%` : null,
                e.respiratoryRate != null ? `RR ${e.respiratoryRate}` : null,
                e.systolicBp != null ? `SBP ${e.systolicBp}` : null,
                e.temperature != null ? `${e.temperature}°C` : null,
              ].filter(Boolean).join(' · ');
              return (
                <li key={e.id} className="ml-4 relative">
                  <span className={`absolute -left-[23px] top-1 w-3 h-3 rounded-full border-2 ${isDark ? 'border-slate-900' : 'border-white'} ${meta.dot}`} />
                  <div className="flex items-baseline gap-2 flex-wrap">
                    <span className={`text-[10px] font-bold tabular-nums ${text.muted}`}>
                      {new Date(e.occurredAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                    </span>
                    <span className={`text-xs font-bold ${meta.textCls || text.heading}`}>{e.label}</span>
                  </div>
                  {e.detail && (
                    <p className={`text-[11px] mt-0.5 leading-snug ${text.muted}`}>{e.detail}</p>
                  )}
                  {vitals && (
                    <p className={`text-[10px] mt-0.5 font-semibold tabular-nums ${text.muted}`}>{vitals}</p>
                  )}
                </li>
              );
            })}
          </ol>
        </div>
      )}
    </div>
  );
}

/* ─── TewsChart — the deterioration story as one score over time ── */
function TewsChart({
  data, glassCard, isDark, text,
}: {
  data: any[]; glassCard: any; isDark: boolean; text: any;
}) {
  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <p className={`text-sm font-bold mb-1 ${text.heading}`}>
        TEWS score trend
        <span className={`ml-2 text-[10px] font-semibold ${text.muted}`}>≥3 urgent · ≥5 very urgent · ≥7 critical</span>
      </p>
      <div className="h-40">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -24 }}>
            <CartesianGrid strokeDasharray="3 3" stroke={isDark ? 'rgba(148,163,184,0.12)' : 'rgba(100,116,139,0.15)'} />
            <XAxis dataKey="t" tick={{ fontSize: 10 }} minTickGap={40} stroke={isDark ? '#64748b' : '#94a3b8'} />
            <YAxis tick={{ fontSize: 10 }} stroke={isDark ? '#64748b' : '#94a3b8'} domain={[0, 'dataMax + 1']} allowDecimals={false} />
            <Tooltip contentStyle={{ background: isDark ? '#0f172a' : '#ffffff', border: '1px solid rgba(100,116,139,0.25)', borderRadius: 12, fontSize: 11 }} />
            <ReferenceLine y={3} stroke="#f59e0b" strokeDasharray="4 4" />
            <ReferenceLine y={5} stroke="#f97316" strokeDasharray="4 4" />
            <ReferenceLine y={7} stroke="#ef4444" strokeDasharray="4 4" />
            <Line type="stepAfter" dataKey="tews" name="TEWS" stroke="#8b5cf6" strokeWidth={2.5} dot={false} isAnimationActive={false} connectNulls />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

/* ─── VitalTile — big number + status colour + sparkline + delta ── */
function VitalTile({
  icon: Icon, label, unit, value, secondary, k, data, dataKey, delta,
  statusOverride, footnote, className, glassInner, text,
}: {
  icon: any; label: string; unit: string;
  value: number | null | undefined; secondary?: string;
  k: VitalKey; data: any[]; dataKey: string;
  delta?: number | null;
  statusOverride?: 'critical' | 'warning' | 'normal' | 'none';
  footnote?: string;
  className?: string;
  glassInner: any; text: any;
}) {
  const status = statusOverride ?? vitalStatus(k, value ?? null);
  const spark = data.slice(-30);
  const sparkColor = status === 'critical' ? '#ef4444' : status === 'warning' ? '#f59e0b' : '#10b981';
  return (
    <div className={`rounded-2xl p-3.5 ${className ?? ''}`} style={glassInner}>
      <div className="flex items-center justify-between">
        <span className={`inline-flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
          <Icon className={`w-3.5 h-3.5 ${STATUS_CLS[status]}`} />
          {label}
        </span>
        {status === 'critical' && <AlertTriangle className="w-3.5 h-3.5 text-red-500 animate-pulse" />}
      </div>
      <p className={`mt-1.5 text-2xl font-black tabular-nums leading-none ${STATUS_CLS[status]}`}>
        {value != null ? value : '—'}
        {secondary && <span className="text-base font-bold">{secondary}</span>}
        <span className={`ml-1 text-[10px] font-semibold ${text.muted}`}>{unit}</span>
      </p>
      {(delta != null || footnote) && (
        <p className={`mt-1 text-[10px] font-semibold ${text.muted}`}>
          {delta != null && (
            <span className="inline-flex items-center gap-0.5 mr-2">
              {delta > 0 ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
              {delta > 0 ? '+' : ''}{Number.isInteger(delta) ? delta : delta.toFixed(1)} vs arrival
            </span>
          )}
          {footnote}
        </p>
      )}
      <div className="h-8 mt-1.5 -mx-1">
        {spark.length >= 2 && (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={spark} margin={{ top: 2, right: 2, bottom: 0, left: 2 }}>
              <Line type="monotone" dataKey={dataKey} stroke={sparkColor} strokeWidth={1.5} dot={false} isAnimationActive={false} connectNulls />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

/* ─── TrendChart — full-width multi-series chart over the buffer ── */
function TrendChart({
  title, data, series, glassCard, isDark, text,
}: {
  title: string;
  data: any[];
  series: { key: string; name: string; color: string }[];
  glassCard: any; isDark: boolean; text: any;
}) {
  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <p className={`text-sm font-bold mb-2 ${text.heading}`}>{title}</p>
      <div className="h-52">
        {data.length < 2 ? (
          <div className="h-full flex items-center justify-center">
            <p className={`text-xs ${text.muted}`}>Waiting for readings…</p>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: -18 }}>
              <CartesianGrid strokeDasharray="3 3" stroke={isDark ? 'rgba(148,163,184,0.12)' : 'rgba(100,116,139,0.15)'} />
              <XAxis dataKey="t" tick={{ fontSize: 10 }} minTickGap={40} stroke={isDark ? '#64748b' : '#94a3b8'} />
              <YAxis tick={{ fontSize: 10 }} stroke={isDark ? '#64748b' : '#94a3b8'} domain={['auto', 'auto']} />
              <Tooltip
                contentStyle={{
                  background: isDark ? '#0f172a' : '#ffffff',
                  border: '1px solid rgba(100,116,139,0.25)',
                  borderRadius: 12,
                  fontSize: 11,
                }}
              />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              {series.map((s) => (
                <Line key={s.key} type="monotone" dataKey={s.key} name={s.name} stroke={s.color} strokeWidth={2} dot={false} isAnimationActive={false} connectNulls />
              ))}
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

export default VitalMonitoring;
