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
  Gauge, HeartPulse, Loader2, MonitorSpeaker, Pause, Play,
  ShieldAlert, Square, Stethoscope, Thermometer, TrendingDown,
  TrendingUp, Minus, Wifi, Wind,
} from 'lucide-react';
import {
  ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip,
  CartesianGrid, Legend,
} from 'recharts';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { usePatientStore, visitResponseToPatient } from '@/store/patientStore';
import type { Patient } from '@/types';
import { iotApi } from '@/api/iot';
import { alertApi } from '@/api/alerts';
import { visitApi } from '@/api/visits';
import type {
  ClinicalAlertResponse, DeviceSessionResponse, VitalStreamResponse,
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

  const chartData = useMemo(() => readings.map((r) => ({
    t: fmtTime(r.capturedAt),
    hr: r.heartRate,
    spo2: r.spo2,
    rr: r.respiratoryRate,
    sbp: r.systolicBp,
    dbp: r.diastolicBp,
    temp: r.temperature,
  })), [readings]);

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
                  {lastReadingAgeSec == null ? 'no data' : lastReadingAgeSec <= 15 ? `LIVE · ${lastReadingAgeSec}s` : `last ${lastReadingAgeSec}s ago`}
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
          {/* Vital tiles */}
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            <VitalTile icon={HeartPulse} label="Heart rate" unit="bpm" value={latest?.heartRate} k="heartRate" data={chartData} dataKey="hr" glassInner={glassInner} text={text} />
            <VitalTile icon={Activity} label="SpO₂" unit="%" value={latest?.spo2} k="spo2" data={chartData} dataKey="spo2" glassInner={glassInner} text={text} />
            <VitalTile icon={Wind} label="Resp. rate" unit="/min" value={latest?.respiratoryRate} k="respiratoryRate" data={chartData} dataKey="rr" glassInner={glassInner} text={text} />
            <VitalTile icon={Gauge} label="Blood pressure" unit="mmHg" value={latest?.systolicBp} secondary={latest?.diastolicBp != null ? `/${latest.diastolicBp}` : undefined} k="systolicBp" data={chartData} dataKey="sbp" glassInner={glassInner} text={text} />
            <VitalTile icon={Thermometer} label="Temperature" unit="°C" value={latest?.temperature} k="temperature" data={chartData} dataKey="temp" glassInner={glassInner} text={text} />
          </div>

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

/* ─── VitalTile — big number + status colour + sparkline ─────────── */
function VitalTile({
  icon: Icon, label, unit, value, secondary, k, data, dataKey, glassInner, text,
}: {
  icon: any; label: string; unit: string;
  value: number | null | undefined; secondary?: string;
  k: VitalKey; data: any[]; dataKey: string;
  glassInner: any; text: any;
}) {
  const status = vitalStatus(k, value ?? null);
  const spark = data.slice(-30);
  const sparkColor = status === 'critical' ? '#ef4444' : status === 'warning' ? '#f59e0b' : '#10b981';
  return (
    <div className="rounded-2xl p-3.5" style={glassInner}>
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
