import { useState, useMemo, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Activity, Heart, Wind, Thermometer, Droplet, Search, Zap, Candy, Cpu,
  AlertTriangle, TrendingUp, TrendingDown, Minus, Clock,
  Eye, ChevronRight, RefreshCw, Users, Baby,
  Shield, Stethoscope, Monitor, Siren,
  Wifi, WifiOff, BatteryWarning, Radio,
} from 'lucide-react';
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, Area, AreaChart,
} from 'recharts';
import { EcgWaveformChart } from '@/components/ui/EcgWaveformChart';
import { usePatientStore } from '@/store/patientStore';
import { useDeviceStore } from '@/store/deviceStore';
import { useVitalStore } from '@/store/vitalStore';
import { useAuthStore } from '@/store/authStore';
import { useAlertStore } from '@/store/alertStore';
import { subscribeToVitals, subscribeToTrendChanges } from '@/api/websocket';
import { iotApi } from '@/api/iot';
import type { Patient, VitalSigns, AIAlert } from '@/types';
import { HandoffPriorityBadges } from '@/components/HandoffPriorityBadges';
import {
  SIGNAL_QUALITY_META,
  getBatteryColor,
} from '@/utils/iotDeviceManager';
import { useTheme } from '@/hooks/useTheme';
import MonitoringStatePill from './MonitoringStatePill';
import StartMonitoringConfirmModal from './StartMonitoringConfirmModal';
import EndMonitoringConfirmModal from './EndMonitoringConfirmModal';
import { Play, Pause, PlayCircle, Square } from 'lucide-react';

/* ── Monitoring Showcase Config ── */
const monitorShowcaseConfig = [
  {
    key: 'worsening' as const,
    label: 'Worsening',
    sublabel: 'Deteriorating — immediate review',
    icon: TrendingUp,
    gradient: 'from-red-500 to-red-600',
    lightBg: 'rgba(254,226,226,0.6)',
    borderColor: 'rgba(239,68,68,0.25)',
    accentColor: '#ef4444',
    dotColor: 'bg-red-500',
    textColor: 'text-red-700',
    badgeBg: 'bg-red-100',
    badgeBorder: 'border-red-200',
    shadowColor: 'shadow-red-500/20',
  },
  {
    key: 'stable' as const,
    label: 'Stable',
    sublabel: 'Vitals within expected range',
    icon: Minus,
    gradient: 'from-amber-500 to-amber-600',
    lightBg: 'rgba(255,237,213,0.6)',
    borderColor: 'rgba(245,158,11,0.25)',
    accentColor: '#f59e0b',
    dotColor: 'bg-amber-500',
    textColor: 'text-amber-700',
    badgeBg: 'bg-amber-100',
    badgeBorder: 'border-amber-200',
    shadowColor: 'shadow-amber-500/20',
  },
  {
    key: 'improving' as const,
    label: 'Improving',
    sublabel: 'Positive trend — continue monitoring',
    icon: TrendingDown,
    gradient: 'from-emerald-500 to-emerald-600',
    lightBg: 'rgba(209,250,229,0.6)',
    borderColor: 'rgba(16,185,129,0.25)',
    accentColor: '#10b981',
    dotColor: 'bg-emerald-500',
    textColor: 'text-emerald-700',
    badgeBg: 'bg-emerald-100',
    badgeBorder: 'border-emerald-200',
    shadowColor: 'shadow-emerald-500/20',
  },
];

const trendGradients: Record<string, [string, string]> = {
  worsening: ['#ef4444', '#dc2626'],
  stable: ['#f59e0b', '#d97706'],
  improving: ['#10b981', '#059669'],
};

// ── Monitored patient shape (derived from real store data) ──
interface MonitoredPatient extends Patient {
  currentVitals: {
    heartRate: number;
    respiratoryRate: number;
    spo2: number;
    systolicBP: number;
    diastolicBP: number;
    temperature: number;
    ecg: number;
    ecgRhythm?: string;
    ecgQrsDuration?: number;
    glucose: number;
  };
  vitalHistory: {
    time: string;
    hr: number;
    rr: number;
    spo2: number;
    sbp: number;
    temp: number;
    ecg: number;
    glucose: number;
  }[];
  tewsHistory: { time: string; score: number }[];
  lastAssessment: Date;
  trend: 'improving' | 'stable' | 'worsening';
  alerts: { message: string; severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'; time: Date }[];
}

// ── Build a flat TEWS history from the current score (single data point) ──
function buildTewsHistory(currentScore: number): { time: string; score: number }[] {
  const now = new Date();
  return [{ time: now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }), score: currentScore }];
}

const categoryColor: Record<string, string> = {
  RED: 'bg-red-500',
  ORANGE: 'bg-orange-500',
  YELLOW: 'bg-yellow-400',
  GREEN: 'bg-green-500',
  BLUE: 'bg-blue-500',
};

const alertSeverityStyle: Record<string, string> = {
  LOW: 'bg-gray-50 border-gray-200 text-gray-700',
  MEDIUM: 'bg-yellow-50 border-yellow-200 text-yellow-700',
  HIGH: 'bg-orange-50 border-orange-200 text-orange-700',
  CRITICAL: 'bg-red-50 border-red-200 text-red-700',
};

const trendIcon = {
  improving: <TrendingDown className="w-4 h-4 text-green-500" />,
  stable: <Minus className="w-4 h-4 text-gray-400" />,
  worsening: <TrendingUp className="w-4 h-4 text-red-500" />,
};

const trendLabel = {
  improving: 'Improving',
  stable: 'Stable',
  worsening: 'Worsening',
};

/* ── Animated Monitoring Showcase Component ── */
function MonitoringShowcase({ allPatients, onNavigate }: { allPatients: MonitoredPatient[]; onNavigate: (id: string) => void }) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [activeIdx, setActiveIdx] = useState(0);
  const [isTransitioning, setIsTransitioning] = useState(false);

  const trendCounts = useMemo(() => {
    const counts: Record<string, MonitoredPatient[]> = { worsening: [], stable: [], improving: [] };
    allPatients.forEach((p) => {
      if (counts[p.trend]) counts[p.trend].push(p);
    });
    return counts;
  }, [allPatients]);

  useEffect(() => {
    const interval = setInterval(() => {
      setIsTransitioning(true);
      setTimeout(() => {
        setActiveIdx((prev) => (prev + 1) % monitorShowcaseConfig.length);
        setIsTransitioning(false);
      }, 200);
    }, 5000);
    return () => clearInterval(interval);
  }, []);

  const goTo = useCallback((idx: number) => {
    setIsTransitioning(true);
    setTimeout(() => {
      setActiveIdx(idx);
      setIsTransitioning(false);
    }, 200);
  }, []);

  const active = monitorShowcaseConfig[activeIdx];
  const activePatients = trendCounts[active.key] || [];
  const ActiveIcon = active.icon;
  const total = allPatients.length;
  const alertCount = allPatients.reduce((sum, p) => sum + (p.alerts?.length || 0), 0);

  return (
    <div className="animate-fade-up" style={{ animationDelay: '0.05s' }}>
      <div className="rounded-2xl overflow-hidden" style={glassCard}>
        <div className="flex flex-col lg:flex-row">

          {/* Left: Trend selector pills */}
          <div className="lg:w-52 flex lg:flex-col gap-1.5 p-3 lg:py-4 overflow-x-auto lg:overflow-x-visible flex-shrink-0" style={{ borderRight: isDark ? '1px solid rgba(2,132,199,0.18)' : '1px solid rgba(203,213,225,0.2)' }}>
            <div className="hidden lg:flex flex-col gap-1 px-2 pb-3 mb-1" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.15)' }}>
              <span className={`text-2xl font-bold ${text.heading}`}>{total}</span>
              <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Monitored</span>
              <div className="flex items-center gap-1.5 mt-1">
                <span className="w-2 h-2 rounded-full bg-red-400 animate-pulse" />
                <span className="text-[10px] text-red-600 font-semibold">{alertCount} alert{alertCount !== 1 ? 's' : ''}</span>
              </div>
            </div>

            {monitorShowcaseConfig.map((cat, idx) => {
              const count = (trendCounts[cat.key] || []).length;
              const isActive = idx === activeIdx;
              return (
                <button
                  key={cat.key}
                  onClick={() => goTo(idx)}
                  className={`flex items-center gap-2.5 px-3 py-2 rounded-xl transition-all duration-300 flex-shrink-0 ${
                    isActive ? 'shadow-md scale-[1.02]' : 'hover:bg-white/40'
                  }`}
                  style={isActive ? {
                    background: isDark ? `${cat.accentColor}20` : cat.lightBg,
                    border: `1px solid ${isDark ? `${cat.accentColor}40` : cat.borderColor}`,
                    boxShadow: `0 4px 16px ${isDark ? `${cat.accentColor}25` : cat.borderColor}`,
                  } : {}}
                >
                  <div className={`w-2.5 h-2.5 rounded-full ${cat.dotColor} flex-shrink-0 ${isActive ? 'animate-pulse' : ''}`} />
                  <span className={`text-xs font-bold flex-1 text-left ${isActive ? cat.textColor : text.body}`}>
                    {cat.label}
                  </span>
                  <span className={`text-xs font-bold min-w-[20px] text-center px-1.5 py-0.5 rounded-md ${
                    isActive ? `${cat.badgeBg} ${cat.textColor} border ${cat.badgeBorder}` : text.muted
                  }`}>
                    {count}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Right: Active trend showcase */}
          <div className="flex-1 p-5 lg:p-6 min-w-0">
            <div className={`transition-all duration-300 ${isTransitioning ? 'opacity-0 translate-y-2' : 'opacity-100 translate-y-0'}`}>
              <div className="flex items-center gap-3 mb-4">
                <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${active.gradient} flex items-center justify-center shadow-lg ${active.shadowColor}`}>
                  <ActiveIcon className="w-5 h-5 text-white" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className={`text-base font-bold ${active.textColor}`}>{active.label}</h3>
                    <span className={`text-[10px] font-bold ${active.badgeBg} ${active.textColor} border ${active.badgeBorder} px-2 py-0.5 rounded-md`}>
                      {activePatients.length} patient{activePatients.length !== 1 ? 's' : ''}
                    </span>
                  </div>
                  <p className={`text-[11px] ${text.muted} font-medium mt-0.5`}>{active.sublabel}</p>
                </div>
              </div>

              {activePatients.length === 0 ? (
                <div className="py-8 text-center rounded-xl" style={glassInner}>
                  <ActiveIcon className="w-8 h-8 mx-auto mb-2" style={{ color: active.accentColor, opacity: 0.3 }} />
                  <p className={`text-xs ${text.muted} font-medium`}>No patients with this trend</p>
                </div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
                  {activePatients.slice(0, 4).map((patient, pIdx) => (
                    <button
                      key={patient.id}
                      onClick={() => onNavigate(patient.id)}
                      className="flex items-center gap-3 px-3.5 py-3 rounded-xl text-left transition-all duration-300 hover:scale-[1.02] hover:shadow-md group"
                      style={{
                        ...glassInner,
                        borderLeft: `3px solid ${active.accentColor}`,
                        animationDelay: `${pIdx * 0.08}s`,
                      }}
                    >
                      <div className="w-9 h-9 rounded-lg flex items-center justify-center text-[11px] font-bold text-white flex-shrink-0"
                        style={{ background: `linear-gradient(135deg, ${trendGradients[active.key][0]}, ${trendGradients[active.key][1]})` }}
                      >
                        {patient.fullName.split(' ').map(n => n[0]).join('').slice(0, 2)}
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className={`text-sm font-semibold ${text.label} truncate group-hover:text-cyan-600 transition-colors`}>
                          {patient.fullName}
                        </p>
                        <p className={`text-[10px] ${text.muted} font-medium truncate`}>
                          {patient.age}y · {patient.chiefComplaint}
                        </p>
                      </div>
                      {patient.tewsScore !== undefined && (
                        <div className={`w-7 h-7 rounded-md flex items-center justify-center text-[11px] font-bold flex-shrink-0 ${
                          patient.tewsScore >= 7
                            ? 'bg-red-50 text-red-600 border border-red-200'
                            : patient.tewsScore >= 4
                              ? 'bg-amber-50 text-amber-600 border border-amber-200'
                              : 'bg-emerald-50 text-emerald-600 border border-emerald-200'
                        }`}>
                          {patient.tewsScore}
                        </div>
                      )}
                      <ChevronRight className="w-3.5 h-3.5 text-slate-300 group-hover:text-cyan-500 transition-colors flex-shrink-0" />
                    </button>
                  ))}
                  {activePatients.length > 4 && (
                    <div className={`flex items-center justify-center px-3 py-2 rounded-xl text-[11px] font-semibold ${text.muted}`} style={glassInner}>
                      +{activePatients.length - 4} more patient{activePatients.length - 4 > 1 ? 's' : ''}
                    </div>
                  )}
                </div>
              )}

              <div className="flex items-center justify-center gap-1.5 mt-4">
                {monitorShowcaseConfig.map((cat, idx) => (
                  <button
                    key={cat.key}
                    onClick={() => goTo(idx)}
                    className="p-1 rounded-full transition-all duration-300"
                    aria-label={cat.label}
                  >
                    <div
                      className={`rounded-full transition-all duration-300 ${
                        idx === activeIdx ? 'w-6 h-2' : 'w-2 h-2 hover:scale-125'
                      }`}
                      style={{ backgroundColor: idx === activeIdx ? cat.accentColor : '#cbd5e1' }}
                    />
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function formatElapsed(date: Date): string {
  const mins = Math.floor((Date.now() - date.getTime()) / 60000);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ${mins % 60}m ago`;
}

function formatSince(date: Date): string {
  const mins = Math.floor((Date.now() - date.getTime()) / 60000);
  if (mins < 60) return `${mins}m`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ${mins % 60}m`;
}

// ── Helper: convert store Patient + vitalStore data → MonitoredPatient ──
function toMonitoredPatient(
  p: Patient,
  vitals: VitalSigns | undefined,
  vitalHistoryMap: Map<string, { timestamp: Date; value: number }[]> | undefined,
  trendOverride?: 'WORSENING' | 'STABLE' | 'IMPROVING' | 'UNKNOWN',
  storeAlerts?: AIAlert[],
): MonitoredPatient {
  const v = vitals ?? { heartRate: 0, respiratoryRate: 0, spo2: 0, systolicBP: 0, diastolicBP: 0, temperature: 0, ecg: 0, glucose: 0, timestamp: new Date(), deviceConnected: false };
  const currentVitals = {
    heartRate: v.heartRate,
    respiratoryRate: v.respiratoryRate,
    spo2: v.spo2,
    systolicBP: v.systolicBP,
    diastolicBP: v.diastolicBP,
    temperature: v.temperature,
    ecg: v.ecg,
    ecgRhythm: v.ecgRhythm,
    ecgQrsDuration: v.ecgQrsDuration,
    glucose: v.glucose,
  };

  // Build vitalHistory from the last 8 readings in vitalStore (or generate placeholder)
  const hrHist = vitalHistoryMap?.get('heartRate') ?? [];
  const rrHist = vitalHistoryMap?.get('respiratoryRate') ?? [];
  const spo2Hist = vitalHistoryMap?.get('spo2') ?? [];
  const sbpHist = vitalHistoryMap?.get('systolicBP') ?? [];
  const tempHist = vitalHistoryMap?.get('temperature') ?? [];
  const glcHist = vitalHistoryMap?.get('glucose') ?? [];

  const maxLen = Math.max(hrHist.length, 1);
  const vitalHistory = Array.from({ length: Math.min(maxLen, 8) }, (_, i) => ({
    time: (hrHist[i]?.timestamp ?? new Date()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    hr: hrHist[i]?.value ?? currentVitals.heartRate,
    rr: rrHist[i]?.value ?? currentVitals.respiratoryRate,
    spo2: spo2Hist[i]?.value ?? currentVitals.spo2,
    sbp: sbpHist[i]?.value ?? currentVitals.systolicBP,
    temp: tempHist[i]?.value ?? currentVitals.temperature,
    ecg: currentVitals.ecg,
    glucose: glcHist[i]?.value ?? currentVitals.glucose,
  }));

  // Server-authoritative trend (classified with hysteresis in the backend
  // and pushed over /topic/trend/{visitId}). The trendOverride map from the
  // component is the single source of truth; p.trendStatus is a fallback.
  const serverTrend = trendOverride ?? p.trendStatus;
  const trend: 'improving' | 'stable' | 'worsening' =
    serverTrend === 'WORSENING' ? 'worsening'
    : serverTrend === 'IMPROVING' ? 'improving'
    : 'stable';

  const tewsHistory = buildTewsHistory(p.tewsScore ?? 0);

  return {
    ...p,
    currentVitals,
    vitalHistory,
    tewsHistory,
    lastAssessment: v.timestamp ?? new Date(),
    trend,
    alerts: ((storeAlerts && storeAlerts.length > 0) ? storeAlerts : (p.aiAlerts ?? [])).map((a) => ({
      message: a.message,
      severity: a.severity,
      time: a.timestamp,
    })),
  };
}

export function ConstantMonitoring() {
  const { glassCard, glassInner, glassPatientCard, glassVitalTile, glassExpandedBg, isDark, text } = useTheme();
  const navigate = useNavigate();
  const storePatients = usePatientStore((s) => s.patients);
  const getDevicesForPatient = useDeviceStore((s) => s.getDevicesForPatient);
  const getPatientDeviceSummary = useDeviceStore((s) => s.getPatientDeviceSummary);
  const allDevices = useDeviceStore((s) => s.getAllDevices);
  const vitalsByPatient = useVitalStore((s) => s.vitalsByPatient);
  const vitalHistoryStore = useVitalStore((s) => s.vitalHistory);
  const fetchLatestVitals = useVitalStore((s) => s.fetchLatestVitals);
  const fetchVitalHistory = useVitalStore((s) => s.fetchVitalHistory);
  const authUser = useAuthStore((s) => s.user);
  const storeAlerts = useAlertStore((s) => s.alerts);
  // Group store alerts by visitId (== patient.id in the monitoring view) for O(1) lookup
  const alertsByVisitId = useMemo(() => {
    const m = new Map<string, AIAlert[]>();
    for (const a of storeAlerts) {
      if (!a.patientId) continue;
      const arr = m.get(a.patientId) ?? [];
      arr.push(a);
      m.set(a.patientId, arr);
    }
    // Sort newest first and cap at 5 per patient
    for (const [k, arr] of m) {
      arr.sort((x, y) => new Date(y.timestamp).getTime() - new Date(x.timestamp).getTime());
      m.set(k, arr.slice(0, 5));
    }
    return m;
  }, [storeAlerts]);
  const [searchQuery, setSearchQuery] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<string>('all');
  const [trendFilter, setTrendFilter] = useState<string>('all');
  const [lastRefresh, setLastRefresh] = useState(new Date());
  const [expandedPatient, setExpandedPatient] = useState<string | null>(null);
  // visitId → server-classified trend. Seeded from getActiveSessions, updated
  // live from /topic/trend/{visitId}. Independent of patientStore so patient
  // re-fetches never wipe it.
  const [trendByVisitId, setTrendByVisitId] = useState<Map<string, 'WORSENING' | 'STABLE' | 'IMPROVING' | 'UNKNOWN'>>(new Map());

  // visitId → monitoring session (or null if not started). Drives the
  // MonitoringStatePill and the inline Start Monitoring button. Polled
  // on a fixed interval AND on user actions.
  const [sessionsByVisitId, setSessionsByVisitId] = useState<Map<string, import('@/api/types').DeviceSessionResponse | null>>(new Map());
  // Modal target: patient for whom Start Monitoring was clicked.
  const [startTarget, setStartTarget] = useState<Patient | null>(null);
  // Modal target for End: { patient, sessionId } — sessionId is captured
  // at click-time so the confirmation can act even if the row's state
  // has changed by the time the clinician confirms.
  const [endTarget, setEndTarget] = useState<{ patient: Patient; sessionId: string } | null>(null);

  // Refresh device store on mount AND every 30s tick. A device's
  // status flips to MONITORING only after a DeviceSession is created
  // (e.g. via heartbeat auto-pair when a patient is placed in its bed).
  // Without periodic re-fetch the dashboard shows DEMO forever.
  useEffect(() => {
    if (authUser?.hospitalId) {
      useDeviceStore.getState().fetchDevicesFromApi(authUser.hospitalId);
    }
  }, [authUser?.hospitalId, lastRefresh]);

  // Auto-refresh timestamp every 30 seconds
  useEffect(() => {
    const interval = setInterval(() => setLastRefresh(new Date()), 30000);
    return () => clearInterval(interval);
  }, []);

  // Fetch vitals for all monitorable patients on mount and every 30s
  const monitorableIds = useMemo(() => {
    return storePatients
      .filter((p) => p.triageStatus === 'TRIAGED' || p.triageStatus === 'IN_TREATMENT' || p.triageStatus === 'IN_TRIAGE')
      .map((p) => p.id);
  }, [storePatients]);

  useEffect(() => {
    monitorableIds.forEach((id) => {
      fetchLatestVitals(id);
      fetchVitalHistory(id);
    });
  }, [monitorableIds, lastRefresh]);

  // ── Monitoring sessions per visit ──
  //
  // Drives the state pill and the Start / Pause / Resume / End controls.
  // We refresh on every 30s tick (alongside the existing refresh loop)
  // so state transitions written by the backend's MonitoringStateWatcher
  // (Phase 2) show up without a manual reload.
  const refreshSessions = useCallback(async (ids: string[]) => {
    const entries = await Promise.all(
      ids.map(async (id) => {
        try {
          const session = await iotApi.getActiveSessionForVisit(id);
          return [id, session] as const;
        } catch {
          // Per-visit fetch failure is non-fatal; treat as "unknown" by
          // leaving the previous value in place.
          return null;
        }
      }),
    );
    setSessionsByVisitId((prev) => {
      const next = new Map(prev);
      for (const entry of entries) {
        if (entry) next.set(entry[0], entry[1]);
      }
      return next;
    });
  }, []);

  useEffect(() => {
    if (monitorableIds.length === 0) return;
    refreshSessions(monitorableIds);
  }, [monitorableIds, lastRefresh, refreshSessions]);

  // Tight-poll any visit currently in STARTING. The backend kickstarts
  // a simulator reading on Start/Resume so the transition happens in
  // milliseconds; a 500ms poll picks that up effectively instantly.
  // Auto-stops once everything settles into a non-transient state.
  useEffect(() => {
    const startingIds = monitorableIds.filter((id) => {
      const s = sessionsByVisitId.get(id);
      return s != null && s.monitoringState === 'STARTING';
    });
    if (startingIds.length === 0) return;
    const interval = setInterval(() => {
      refreshSessions(startingIds);
    }, 500);
    return () => clearInterval(interval);
  }, [monitorableIds, sessionsByVisitId, refreshSessions]);

  const handleStartMonitoring = useCallback(async (patient: Patient) => {
    await iotApi.startMonitoringForVisit(patient.id, authUser?.fullName || 'Clinician');
    // Refresh immediately so the pill flips to STARTING; the tight
    // 500ms poll then catches the LIVE transition that the backend
    // kickstart triggers within a few hundred ms.
    refreshSessions([patient.id]);
    // One more refresh at ~600ms covers the very common case where the
    // simulator's kickstart reading lands just as we return.
    setTimeout(() => refreshSessions([patient.id]), 600);
  }, [authUser?.fullName, refreshSessions]);

  const handlePauseMonitoring = useCallback(async (visitId: string, sessionId: string) => {
    try {
      await iotApi.pauseMonitoring(sessionId, authUser?.fullName || 'Clinician');
      refreshSessions([visitId]);
    } catch (e) {
      console.error('Pause monitoring failed', e);
    }
  }, [authUser?.fullName, refreshSessions]);

  const handleResumeMonitoring = useCallback(async (visitId: string, sessionId: string) => {
    try {
      await iotApi.resumeMonitoring(sessionId, authUser?.fullName || 'Clinician');
      refreshSessions([visitId]);
      // Same kickstart-poll pattern as Start.
      setTimeout(() => refreshSessions([visitId]), 600);
    } catch (e) {
      console.error('Resume monitoring failed', e);
    }
  }, [authUser?.fullName, refreshSessions]);

  const handleEndMonitoring = useCallback(async (visitId: string, sessionId: string) => {
    try {
      await iotApi.stopMonitoring(sessionId, authUser?.fullName || 'Clinician', 'Stopped by clinician');
      refreshSessions([visitId]);
    } catch (e) {
      console.error('End monitoring failed', e);
      throw e;
    }
  }, [authUser?.fullName, refreshSessions]);

  // ── WebSocket subscriptions for patients with paired IoT devices ──
  // Subscribe to /topic/vitals/{visitId} for every patient that has a
  // streaming device. This gives true real-time updates every ~5s.
  const wsUnsubs = useRef<Map<string, () => void>>(new Map());
  const trendUnsubs = useRef<Map<string, () => void>>(new Map());

  // ── Incremental sync: WebSocket vital subscriptions track the
  //    session state, not the device store ──
  //
  // The previous gate was driven by `useDeviceStore.allDevices()` which
  // refreshes only on the 30-second `lastRefresh` tick. After a
  // clinician pressed Start the session-state pill flipped to LIVE in
  // ~500 ms, but the WebSocket subscription waited up to 30 seconds
  // for the device store to catch up — so the numeric vitals stayed
  // frozen even though the pill said LIVE. Switching the gate to
  // `sessionsByVisitId` (which is already tight-polled at 500 ms
  // during STARTING + immediately after Start/Resume) closes that gap.
  //
  // We also split the effect into "incremental sync" (this one, no
  // destructive cleanup) and a separate unmount-only cleanup below,
  // so that frequent session-map updates during STARTING don't tear
  // down and rebuild every WebSocket sub every 500 ms.
  useEffect(() => {
    const currentSubs = wsUnsubs.current;

    // States that should receive live vital readings. PAUSED is
    // intentionally excluded — paused sessions stop streaming on the
    // backend; un-subscribing here visually freezes the chart on the
    // last value.
    const desired = new Set<string>();
    sessionsByVisitId.forEach((session, visitId) => {
      if (!session) return;
      const s = session.monitoringState;
      if (s === 'STARTING' || s === 'LIVE' || s === 'DEGRADED' || s === 'STALLED') {
        desired.add(visitId);
      }
    });

    // Add subs for visits that need them but don't have them yet.
    desired.forEach((visitId) => {
      if (!currentSubs.has(visitId)) {
        const unsub = subscribeToVitals(visitId, (vs) => {
          useVitalStore.getState().updateVitals(visitId, {
            heartRate: vs.heartRate ?? 0,
            respiratoryRate: vs.respiratoryRate ?? 0,
            spo2: vs.spo2 ?? 0,
            systolicBP: vs.systolicBp ?? 0,
            diastolicBP: vs.diastolicBp ?? 0,
            temperature: vs.temperature ?? 0,
            ecg: vs.ecgStDeviation ?? 0,
            ecgRhythm: vs.ecgRhythm ?? undefined,
            ecgQrsDuration: vs.ecgQrsDuration ?? undefined,
            glucose: vs.bloodGlucose ?? 0,
            timestamp: new Date(vs.capturedAt),
            deviceConnected: true,
          });
        });
        currentSubs.set(visitId, unsub);
      }
    });

    // Drop subs for visits that have left the desired set (Pause,
    // End, etc.).
    currentSubs.forEach((unsub, visitId) => {
      if (!desired.has(visitId)) {
        unsub();
        currentSubs.delete(visitId);
      }
    });
    // No cleanup on dep change — unmount cleanup is handled by the
    // dedicated effect below.
  }, [sessionsByVisitId]);

  // Unmount-only cleanup — close every still-open WebSocket sub when
  // the page is left. Empty dep array → the cleanup only fires on
  // unmount, never on the high-frequency sessionsByVisitId churn.
  useEffect(() => {
    return () => {
      wsUnsubs.current.forEach((unsub) => unsub());
      wsUnsubs.current.clear();
    };
  }, []);

  // ── Seed trendStatus from active sessions ──
  // The backend pushes /topic/trend/{visitId} ONLY when the label changes.
  // If we join the dashboard after a change already fired, we'd be stuck on
  // "stable" until the next transition. Fetch active sessions on mount and
  // on each 30s tick, and seed patient.trendStatus from session.trendStatus.
  useEffect(() => {
    if (!authUser?.hospitalId) return;
    let cancelled = false;
    iotApi
      .getActiveSessions(authUser.hospitalId)
      .then((sessions) => {
        if (cancelled) return;
        setTrendByVisitId((prev) => {
          const next = new Map(prev);
          sessions.forEach((s) => {
            if (!s.visitId || !s.trendStatus) return;
            next.set(s.visitId, s.trendStatus);
          });
          return next;
        });
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [authUser?.hospitalId, lastRefresh]);

  // ── WebSocket subscriptions for server-authoritative trend changes ──
  // Backend classifies trend (with hysteresis) and pushes WORSENING/STABLE/
  // IMPROVING to /topic/trend/{visitId}. This replaces the old client-side
  // HR-only heuristic that caused badge flicker.
  //
  // Same session-state gate + non-destructive incremental sync as the
  // vitals subscription above — trend signals only make sense while
  // monitoring is actually streaming.
  useEffect(() => {
    const currentSubs = trendUnsubs.current;

    const desired = new Set<string>();
    sessionsByVisitId.forEach((session, visitId) => {
      if (!session) return;
      const s = session.monitoringState;
      if (s === 'STARTING' || s === 'LIVE' || s === 'DEGRADED' || s === 'STALLED') {
        desired.add(visitId);
      }
    });

    desired.forEach((visitId) => {
      if (!currentSubs.has(visitId)) {
        const unsub = subscribeToTrendChanges(visitId, (ev) => {
          setTrendByVisitId((prev) => {
            const next = new Map(prev);
            next.set(visitId, ev.trendStatus);
            return next;
          });
        });
        currentSubs.set(visitId, unsub);
      }
    });

    currentSubs.forEach((unsub, visitId) => {
      if (!desired.has(visitId)) {
        unsub();
        currentSubs.delete(visitId);
      }
    });
    // No cleanup on dep change — unmount cleanup below.
  }, [sessionsByVisitId]);

  // Unmount cleanup for trend subscriptions.
  useEffect(() => {
    return () => {
      trendUnsubs.current.forEach((unsub) => unsub());
      trendUnsubs.current.clear();
    };
  }, []);

  const patients = useMemo(() => {
    const monitorable = storePatients.filter((p) =>
      p.triageStatus === 'TRIAGED' || p.triageStatus === 'IN_TREATMENT' || p.triageStatus === 'IN_TRIAGE'
    );
    return monitorable.map((p) =>
      toMonitoredPatient(p, vitalsByPatient.get(p.id), vitalHistoryStore.get(p.id), trendByVisitId.get(p.id), alertsByVisitId.get(p.id))
    );
  }, [storePatients, vitalsByPatient, vitalHistoryStore, trendByVisitId, alertsByVisitId]);

  const filtered = useMemo(() => {
    return patients.filter((p) => {
      if (categoryFilter !== 'all' && p.category !== categoryFilter) return false;
      if (trendFilter !== 'all' && p.trend !== trendFilter) return false;
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase();
        return p.fullName.toLowerCase().includes(q) || p.chiefComplaint.toLowerCase().includes(q);
      }
      return true;
    });
  }, [patients, searchQuery, categoryFilter, trendFilter]);

  const stats = useMemo(() => ({
    total: patients.length,
    critical: patients.filter((p) => p.category === 'RED').length,
    worsening: patients.filter((p) => p.trend === 'worsening').length,
    activeAlerts: patients.reduce((sum, p) => sum + (p.alerts?.length || 0), 0),
    pediatric: patients.filter((p) => p.isPediatric).length,
    adult: patients.filter((p) => !p.isPediatric).length,
  }), [patients]);

  const MiniVitalBadge = ({ value, unit, icon: Icon, status }: {
    label: string; value: number; unit: string; icon: any; status: 'normal' | 'warning' | 'critical';
  }) => (
    <div
      className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl"
      style={{
        background: status === 'critical' ? 'rgba(239,68,68,0.1)' : status === 'warning' ? 'rgba(245,158,11,0.1)' : isDark ? 'rgba(12,74,110,0.25)' : 'rgba(255,255,255,0.6)',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
        border: `1px solid ${status === 'critical' ? 'rgba(239,68,68,0.25)' : status === 'warning' ? 'rgba(245,158,11,0.25)' : isDark ? 'rgba(2,132,199,0.2)' : 'rgba(255,255,255,0.7)'}`,
        boxShadow: status === 'critical' ? '0 2px 8px rgba(239,68,68,0.1)' : status === 'warning' ? '0 2px 8px rgba(245,158,11,0.1)' : '0 1px 4px rgba(0,0,0,0.03)',
      }}
    >
      <Icon className={`w-3.5 h-3.5 ${status === 'critical' ? 'text-red-500' : status === 'warning' ? 'text-amber-500' : text.muted}`} />
      <span className={`text-xs font-extrabold tabular-nums ${status === 'critical' ? 'text-red-700' : status === 'warning' ? 'text-amber-700' : text.label}`}>{value}</span>
      <span className={`text-[9px] ${text.muted} font-medium`}>{unit}</span>
    </div>
  );

  const getVitalStatus = (vital: string, value: number, isPed: boolean): 'normal' | 'warning' | 'critical' => {
    const ranges: Record<string, { min: number; max: number }> = isPed
      ? { hr: { min: 100, max: 160 }, rr: { min: 20, max: 40 }, spo2: { min: 94, max: 100 }, sbp: { min: 70, max: 110 }, temp: { min: 36, max: 38 }, ecg: { min: -0.5, max: 1.0 }, glucose: { min: 70, max: 140 } }
      : { hr: { min: 60, max: 100 }, rr: { min: 12, max: 20 }, spo2: { min: 95, max: 100 }, sbp: { min: 90, max: 140 }, temp: { min: 36, max: 37.5 }, ecg: { min: -0.5, max: 1.0 }, glucose: { min: 70, max: 140 } };
    const r = ranges[vital];
    if (!r) return 'normal';
    if (value < r.min * 0.85 || value > r.max * 1.15) return 'critical';
    if (value < r.min || value > r.max) return 'warning';
    return 'normal';
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Dark Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={{ boxShadow: '0 8px 32px rgba(0,0,0,0.12)' }}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                <Monitor className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-white tracking-wide">Constant Monitoring</h1>
                <p className="text-white/70 text-xs font-medium">Real-time vitals & case evolution — Emergency Department</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                <span className="text-xs font-semibold text-white/90">LIVE</span>
              </div>
              <div className="bg-white/10 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                <RefreshCw className="w-3.5 h-3.5 text-white/70" />
                <span className="text-[10px] text-white/70 font-medium">Updated {lastRefresh.toLocaleTimeString()}</span>
              </div>
            </div>
          </div>
        </div>

        {/* ── Monitoring Showcase ── */}
        <MonitoringShowcase allPatients={patients} onNavigate={(id) => navigate(`/vitals/${id}`)} />

        {/* ── Inline Glass Search & Filters ── */}
        <div
          className="rounded-2xl p-4 animate-fade-up"
          style={{ ...glassCard, animationDelay: '0.15s' } as any}
        >
          <div className="flex flex-wrap items-center gap-3">
            {/* Search */}
            <div className="flex-1 relative min-w-[200px]">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search patient name or complaint..."
                className={`w-full pl-10 pr-4 py-2.5 text-sm rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 transition-all duration-300 ${text.body}`}
                style={glassInner}
              />
            </div>

            {/* Category filter */}
            <div className="flex items-center gap-1.5">
              {['all', 'RED', 'ORANGE', 'YELLOW', 'GREEN'].map((c) => (
                <button
                  key={c}
                  onClick={() => setCategoryFilter(c)}
                  className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all duration-300 flex items-center gap-1.5 ${
                    categoryFilter === c
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-lg shadow-slate-800/20'
                      : `${isDark ? 'text-slate-300' : 'text-slate-600'} hover:-translate-y-0.5`
                  }`}
                  style={categoryFilter !== c ? {
                    background: isDark ? 'rgba(12,74,110,0.2)' : 'rgba(255,255,255,0.6)',
                    border: isDark ? '1px solid rgba(2,132,199,0.2)' : '1px solid rgba(203,213,225,0.4)',
                    boxShadow: '0 1px 4px rgba(0,0,0,0.03)',
                  } : undefined}
                >
                  {c !== 'all' && <div className={`w-2 h-2 rounded-full ${categoryColor[c]}`} />}
                  {c === 'all' ? 'All' : c}
                </button>
              ))}
            </div>

            {/* Trend filter */}
            <div className="flex items-center gap-1.5">
              {['all', 'worsening', 'stable', 'improving'].map((t) => (
                <button
                  key={t}
                  onClick={() => setTrendFilter(t)}
                  className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all duration-300 ${
                    trendFilter === t
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-lg shadow-slate-800/20'
                      : `${isDark ? 'text-slate-300' : 'text-slate-600'} hover:-translate-y-0.5`
                  }`}
                  style={trendFilter !== t ? {
                    background: isDark ? 'rgba(12,74,110,0.2)' : 'rgba(255,255,255,0.6)',
                    border: isDark ? '1px solid rgba(2,132,199,0.2)' : '1px solid rgba(203,213,225,0.4)',
                    boxShadow: '0 1px 4px rgba(0,0,0,0.03)',
                  } : undefined}
                >
                  {t === 'all' ? 'All Trends' : t.charAt(0).toUpperCase() + t.slice(1)}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* ── Patient Monitoring Table ── */}
        <div
          className="rounded-2xl p-5 animate-fade-up"
          style={{ ...glassCard, animationDelay: '0.22s' } as any}
        >
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Monitored Patients</h3>
              <p className={`text-xs ${text.body} font-medium mt-0.5`}>{filtered.length} patient{filtered.length !== 1 ? 's' : ''} under active monitoring</p>
            </div>
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 bg-cyan-400 rounded-full animate-pulse" />
              <span className="text-[10px] font-bold text-cyan-600 uppercase tracking-wider">Live Feed</span>
            </div>
          </div>

          <div className="space-y-2.5">
            {filtered.length === 0 ? (
              patients.length === 0 ? (
                <div className="py-12 text-center rounded-2xl" style={glassInner}>
                  <Monitor className="w-12 h-12 mx-auto mb-3 text-slate-300" />
                  <h4 className={`text-sm font-bold ${text.label} mb-1`}>No patients under active monitoring</h4>
                  <p className={`text-xs ${text.muted} max-w-sm mx-auto mb-4`}>
                    Patients will appear here once they are triaged and have vitals recorded or an IoT device assigned.
                  </p>
                  <button
                    onClick={() => navigate('/iot-devices')}
                    className="inline-flex items-center gap-2 px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl hover:-translate-y-0.5 transition-all shadow-md"
                  >
                    <Cpu className="w-3.5 h-3.5" /> Manage IoT Devices
                  </button>
                </div>
              ) : (
                <div className="text-center py-16">
                  <Activity className="w-10 h-10 mx-auto mb-3 text-slate-300" />
                  <p className={`text-sm font-bold ${text.muted}`}>No patients match your filters</p>
                  <p className={`text-xs ${text.muted} mt-1`}>Try adjusting your search criteria</p>
                </div>
              )
            ) : filtered
              .sort((a, b) => {
                const catOrder: Record<string, number> = { RED: 0, ORANGE: 1, YELLOW: 2, GREEN: 3, BLUE: 4 };
                const trendOrder: Record<string, number> = { worsening: 0, stable: 1, improving: 2 };
                const tDiff = (trendOrder[a.trend] ?? 3) - (trendOrder[b.trend] ?? 3);
                if (tDiff !== 0) return tDiff;
                return (catOrder[a.category || 'BLUE'] ?? 5) - (catOrder[b.category || 'BLUE'] ?? 5);
              })
              .map((patient, idx) => {
                const isExpanded = expandedPatient === patient.id;

                const catAccent = patient.category === 'RED' ? '#ef4444' :
                  patient.category === 'ORANGE' ? '#f97316' :
                  patient.category === 'YELLOW' ? '#eab308' :
                  patient.category === 'GREEN' ? '#22c55e' : '#3b82f6';

                return (
                  <div
                    key={patient.id}
                    className={`rounded-2xl overflow-hidden hover:-translate-y-0.5 transition-all duration-500 animate-fade-up cursor-pointer group/card ${
                      patient.category === 'RED' ? 'animate-critical-border' : ''
                    }`}
                    style={{
                      ...glassPatientCard,
                      border: patient.category === 'RED'
                        ? undefined  // handled by animate-critical-border class
                        : `1px solid ${patient.trend === 'worsening' ? 'rgba(239,68,68,0.2)' : isDark ? 'rgba(2,132,199,0.2)' : 'rgba(255,255,255,0.6)'}`,
                      animationDelay: `${idx * 0.04}s`,
                    }}
                  >
                    {/* ── Compact Header ─────────────────────────────────────
                       Two-row layout so nothing visually overlaps even when
                       many status chips are present:

                         Row 1 — Identity (always on one line, never wraps):
                           [Avatar] [Name / age / complaint] ........... [Category][TEWS][›]

                         Row 2 — Telemetry (flex-wraps cleanly):
                           [HR][SpO₂][BP][ECG][Gluc]  ...  [Trend][Alerts][IoT][Time]

                       Row 2 starts indented to align with the patient-info
                       column in row 1, so the two rows read as one visual
                       block. Row 2 hides below sm so ultra-narrow screens
                       stay readable. ─────────────────────────────────── */}
                    <div
                      className="cursor-pointer"
                      onClick={() => setExpandedPatient(isExpanded ? null : patient.id)}
                    >
                      {/* ── Row 1: Identity + triage badges ── */}
                      <div className="flex items-center gap-4 px-5 pt-3.5 pb-2 min-w-0">
                        {/* Patient Avatar */}
                        <div
                          className="w-10 h-10 rounded-xl flex items-center justify-center text-xs font-bold text-white flex-shrink-0 shadow-md"
                          style={{
                            background: `linear-gradient(135deg, ${catAccent}dd, ${catAccent}99)`,
                            boxShadow: `0 4px 12px ${catAccent}30`,
                          }}
                        >
                          {patient.fullName.split(' ').map(n => n[0]).join('').slice(0, 2)}
                        </div>

                        {/* Patient Info — name + age/sex chip + chief complaint */}
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-1.5 flex-wrap">
                            <span className={`text-sm font-bold ${text.heading} whitespace-nowrap group-hover/card:text-cyan-700 transition-colors`}>{patient.fullName}</span>
                            {patient.isPediatric && (
                              <span className="text-[9px] font-bold px-2 py-0.5 rounded-full whitespace-nowrap flex items-center gap-1"
                                style={{ background: 'rgba(139,92,246,0.1)', color: '#7c3aed', border: '1px solid rgba(139,92,246,0.2)' }}
                              ><Baby className="w-2.5 h-2.5" />PEDS</span>
                            )}
                            {patient.trend === 'worsening' && (
                              <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse flex-shrink-0" />
                            )}
                            {/* Urgent-only handoff badges. Monitoring is the
                                real-time deterioration page — pending labs /
                                meds would be noise here, but an open ICU
                                escalation or a critical lab result coming
                                back during the previous shift absolutely
                                demands this page's attention. */}
                            <HandoffPriorityBadges signals={patient} mode="urgent-only" />
                          </div>
                          <div className="flex items-center gap-2 min-w-0">
                            <span className={`text-[11px] ${text.body} font-semibold whitespace-nowrap flex-shrink-0`}
                              style={{ background: 'rgba(148,163,184,0.08)', padding: '1px 6px', borderRadius: 6, border: '1px solid rgba(148,163,184,0.12)' }}
                            >
                              {patient.age < 1 ? `${Math.round(patient.age * 12)}mo` : `${patient.age}y`} · {patient.gender === 'MALE' ? 'M' : 'F'}
                            </span>
                            <span className={`text-[11px] ${text.muted} truncate font-medium min-w-0`}>{patient.chiefComplaint}</span>
                          </div>
                        </div>

                        {/* Monitoring state pill + inline Start button.
                            Always visible on the identity row so the
                            clinician sees at-a-glance whether the patient
                            is being monitored. NOT_STARTED also exposes
                            the Start button right here — no need to open
                            an admin page. */}
                        <div
                          className="flex items-center gap-2 flex-shrink-0"
                          onClick={(e) => e.stopPropagation()}
                        >
                          {(() => {
                            const session = sessionsByVisitId.get(patient.id);
                            const state = session ? session.monitoringState : 'NOT_STARTED';
                            return (
                              <>
                                <MonitoringStatePill state={state} />
                                {state === 'NOT_STARTED' && (
                                  <button
                                    onClick={() => setStartTarget(patient)}
                                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-[10px] font-bold bg-emerald-600 text-white hover:bg-emerald-700 transition-colors shadow-sm"
                                    title="Start continuous monitoring"
                                  >
                                    <Play className="w-3 h-3" />
                                    Start
                                  </button>
                                )}
                                {session && (state === 'LIVE' || state === 'DEGRADED' || state === 'STALLED') && (
                                  <>
                                    <button
                                      onClick={() => handlePauseMonitoring(patient.id, session.id)}
                                      className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold transition-colors ${isDark ? 'bg-white/10 text-slate-200 hover:bg-white/15' : 'bg-slate-200 text-slate-700 hover:bg-slate-300'}`}
                                      title="Pause monitoring (patient at imaging / procedure)"
                                    >
                                      <Pause className="w-3 h-3" />
                                    </button>
                                    <button
                                      onClick={() => setEndTarget({ patient, sessionId: session.id })}
                                      className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold bg-red-100 text-red-700 hover:bg-red-200 transition-colors"
                                      title="End monitoring"
                                    >
                                      <Square className="w-3 h-3" />
                                    </button>
                                  </>
                                )}
                                {session && state === 'PAUSED' && (
                                  <>
                                    <button
                                      onClick={() => handleResumeMonitoring(patient.id, session.id)}
                                      className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-[10px] font-bold bg-emerald-600 text-white hover:bg-emerald-700 transition-colors shadow-sm"
                                      title="Resume monitoring"
                                    >
                                      <PlayCircle className="w-3 h-3" />
                                      Resume
                                    </button>
                                    <button
                                      onClick={() => setEndTarget({ patient, sessionId: session.id })}
                                      className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold bg-red-100 text-red-700 hover:bg-red-200 transition-colors"
                                      title="End monitoring"
                                    >
                                      <Square className="w-3 h-3" />
                                    </button>
                                  </>
                                )}
                                {session && state === 'DISCONNECTED' && (
                                  <button
                                    onClick={() => setEndTarget({ patient, sessionId: session.id })}
                                    className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold bg-red-100 text-red-700 hover:bg-red-200 transition-colors"
                                    title="End monitoring (e.g. swap device)"
                                  >
                                    <Square className="w-3 h-3" />
                                  </button>
                                )}
                              </>
                            );
                          })()}
                        </div>

                        {/* Category + TEWS — always visible on identity row */}
                        <div className="flex items-center gap-2 flex-shrink-0">
                          {patient.category && (
                            <span
                              className="px-2.5 py-1 rounded-xl text-[10px] font-extrabold whitespace-nowrap"
                              style={{
                                background: isDark ? `${catAccent}35` : `${catAccent}14`,
                                color: catAccent,
                                border: `1px solid ${isDark ? `${catAccent}55` : `${catAccent}30`}`,
                                boxShadow: `0 1px 4px ${isDark ? `${catAccent}25` : `${catAccent}10`}`,
                              }}
                            >
                              {patient.category}
                            </span>
                          )}
                          {patient.tewsScore !== undefined && (
                            <span
                              className="text-[10px] font-extrabold px-2 py-1 rounded-xl whitespace-nowrap"
                              style={{
                                background: patient.tewsScore >= 7 ? `rgba(239,68,68,${isDark ? '0.22' : '0.08'})` : patient.tewsScore >= 4 ? `rgba(245,158,11,${isDark ? '0.22' : '0.08'})` : `rgba(34,197,94,${isDark ? '0.22' : '0.08'})`,
                                color: patient.tewsScore >= 7 ? (isDark ? '#f87171' : '#dc2626') : patient.tewsScore >= 4 ? (isDark ? '#fbbf24' : '#d97706') : (isDark ? '#34d399' : '#16a34a'),
                                border: `1px solid ${patient.tewsScore >= 7 ? `rgba(239,68,68,${isDark ? '0.4' : '0.2'})` : patient.tewsScore >= 4 ? `rgba(245,158,11,${isDark ? '0.4' : '0.2'})` : `rgba(34,197,94,${isDark ? '0.4' : '0.2'})`}`,
                              }}
                            >TEWS {patient.tewsScore}</span>
                          )}
                        </div>

                        <ChevronRight className={`w-5 h-5 text-slate-300 group-hover/card:text-cyan-500 transition-all duration-300 flex-shrink-0 ${isExpanded ? 'rotate-90' : ''}`} />
                      </div>

                      {/* ── Row 2: Vitals + status chips (wraps cleanly) ── */}
                      <div className="hidden sm:flex items-start gap-x-3 gap-y-2 flex-wrap pb-3.5 pr-5 pl-[4.5rem]">
                        {/* Vital badges cluster — all 5 always visible, wraps if needed */}
                        <div className="flex items-center gap-1.5 flex-wrap">
                          <MiniVitalBadge label="HR" value={patient.currentVitals.heartRate} unit="bpm" icon={Heart} status={getVitalStatus('hr', patient.currentVitals.heartRate, patient.isPediatric)} />
                          <MiniVitalBadge label="SpO2" value={patient.currentVitals.spo2} unit="%" icon={Activity} status={getVitalStatus('spo2', patient.currentVitals.spo2, patient.isPediatric)} />
                          <MiniVitalBadge label="BP" value={patient.currentVitals.systolicBP} unit="mmHg" icon={Droplet} status={getVitalStatus('sbp', patient.currentVitals.systolicBP, patient.isPediatric)} />
                          <MiniVitalBadge label="ECG" value={patient.currentVitals.ecg} unit="mV" icon={Zap} status={getVitalStatus('ecg', patient.currentVitals.ecg, patient.isPediatric)} />
                          <MiniVitalBadge label="Gluc" value={patient.currentVitals.glucose} unit="mg/dL" icon={Candy} status={getVitalStatus('glucose', patient.currentVitals.glucose, patient.isPediatric)} />
                        </div>

                        {/* Spacer — pushes status chips to the right when there's room */}
                        <div className="flex-1 min-w-2" />

                        {/* Status chips cluster */}
                        <div className="flex items-center gap-1.5 flex-wrap justify-end">
                          {/* Trend */}
                          <div
                            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl text-xs font-bold flex-shrink-0"
                            style={{
                              background: patient.trend === 'worsening' ? 'rgba(239,68,68,0.1)' : patient.trend === 'improving' ? 'rgba(34,197,94,0.1)' : isDark ? 'rgba(148,163,184,0.14)' : 'rgba(148,163,184,0.08)',
                              backdropFilter: 'blur(8px)',
                              color: patient.trend === 'worsening' ? '#dc2626' : patient.trend === 'improving' ? '#16a34a' : isDark ? '#cbd5e1' : '#64748b',
                              border: `1px solid ${patient.trend === 'worsening' ? 'rgba(239,68,68,0.2)' : patient.trend === 'improving' ? 'rgba(34,197,94,0.2)' : 'rgba(148,163,184,0.15)'}`,
                              boxShadow: patient.trend === 'worsening' ? '0 2px 8px rgba(239,68,68,0.08)' : 'none',
                            }}
                          >
                            {trendIcon[patient.trend]}
                            <span>{trendLabel[patient.trend]}</span>
                          </div>

                          {/* Alerts badge */}
                          {patient.alerts && patient.alerts.length > 0 && (
                            <div
                              className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl flex-shrink-0"
                              style={{
                                background: 'rgba(239,68,68,0.1)',
                                backdropFilter: 'blur(8px)',
                                border: '1px solid rgba(239,68,68,0.2)',
                                boxShadow: '0 2px 8px rgba(239,68,68,0.08)',
                              }}
                            >
                              <AlertTriangle className="w-3.5 h-3.5 text-red-500" />
                              <span className="text-xs font-extrabold text-red-600">{patient.alerts.length}</span>
                            </div>
                          )}

                          {/* IoT Device Indicator */}
                          {(() => {
                            const devices = getDevicesForPatient(patient.id);
                            if (devices.length === 0) return null;
                            const summary = getPatientDeviceSummary(patient.id);
                            const connected = devices.filter((d) => d.connectionStatus === 'CONNECTED');
                            const healthColor = summary.overallHealth === 'HEALTHY' ? '34,197,94' : summary.overallHealth === 'WARNING' ? '245,158,11' : '239,68,68';
                            return (
                              <div
                                className="flex items-center gap-1.5 px-2 py-1.5 rounded-xl flex-shrink-0"
                                style={{
                                  background: `rgba(${healthColor},0.1)`,
                                  backdropFilter: 'blur(8px)',
                                  border: `1px solid rgba(${healthColor},0.2)`,
                                }}
                              >
                                {connected.length > 0 ? (
                                  <Wifi className={`w-3 h-3 ${
                                    summary.overallHealth === 'HEALTHY' ? 'text-green-500' :
                                    summary.overallHealth === 'WARNING' ? 'text-amber-500' : 'text-red-500'
                                  }`} />
                                ) : (
                                  <WifiOff className="w-3 h-3 text-red-400" />
                                )}
                                <span className={`text-[10px] font-bold ${
                                  summary.overallHealth === 'HEALTHY' ? 'text-green-600' :
                                  summary.overallHealth === 'WARNING' ? 'text-amber-600' : 'text-red-600'
                                }`}>
                                  {connected.length}/{devices.length}
                                </span>
                                {summary.lowestBattery < 25 && (
                                  <BatteryWarning className={`w-3 h-3 ${getBatteryColor(summary.lowestBattery)}`} />
                                )}
                              </div>
                            );
                          })()}

                          {/* Time in ED */}
                          <div
                            className={`flex items-center gap-1.5 text-[11px] ${text.muted} flex-shrink-0 whitespace-nowrap font-semibold px-2 py-1.5 rounded-lg`}
                            style={{ background: 'rgba(148,163,184,0.06)', border: '1px solid rgba(148,163,184,0.1)' }}
                          >
                            <Clock className="w-3 h-3" />
                            {formatSince(patient.arrivalTimestamp)}
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* ── Expanded Detail ── */}
                    {isExpanded && (
                      <div
                        className="px-5 py-5"
                        style={{ ...glassExpandedBg, borderTop: isDark ? '1px solid rgba(2,132,199,0.18)' : '1px solid rgba(203,213,225,0.2)' }}
                      >
                        {/* ── ECG Waveform Strip ── */}
                        <div className="mb-5">
                          <div className="flex items-center gap-2 mb-2">
                            <Zap className="w-3.5 h-3.5 text-emerald-400" />
                            <h3 className={`text-[11px] font-bold ${text.body} uppercase tracking-wider`}>
                              ECG — Lead II
                            </h3>
                            {patient.currentVitals.ecgRhythm && (
                              <span
                                className="text-[10px] font-bold px-2 py-0.5 rounded-md"
                                style={{
                                  background: 'rgba(0,255,65,0.08)',
                                  color: '#22c55e',
                                  border: '1px solid rgba(0,255,65,0.2)',
                                }}
                              >
                                {patient.currentVitals.ecgRhythm}
                              </span>
                            )}
                            {(() => {
                              const session = sessionsByVisitId.get(patient.id);
                              const state = session ? session.monitoringState : 'NOT_STARTED';
                              return (
                                <div className="ml-auto">
                                  <MonitoringStatePill state={state} compact />
                                </div>
                              );
                            })()}
                          </div>
                          {(() => {
                            // The ECG waveform must reflect monitoring state,
                            // not just "is there a vitals object". When state
                            // is NOT_STARTED / PAUSED / DISCONNECTED / ENDED
                            // the chart should freeze on a flat baseline so
                            // the clinician can see at a glance that the
                            // trace is not real-time data.
                            const session = sessionsByVisitId.get(patient.id);
                            const state = session ? session.monitoringState : 'NOT_STARTED';
                            const liveNow = state === 'LIVE' || state === 'DEGRADED';
                            return (
                              <EcgWaveformChart
                                heartRate={liveNow ? (patient.currentVitals.heartRate || 75) : 0}
                                stDeviation={liveNow ? (patient.currentVitals.ecg || 0) : 0}
                                rhythm={liveNow ? (patient.currentVitals.ecgRhythm || 'NSR') : 'NSR'}
                                qrsDuration={patient.currentVitals.ecgQrsDuration}
                                isLive={liveNow}
                                height={160}
                              />
                            );
                          })()}
                        </div>

                        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">

                          {/* Left — Current Vitals */}
                          <div className="space-y-3">
                            <h3 className={`text-[11px] font-bold ${text.body} uppercase tracking-wider flex items-center gap-1.5`}>
                              <Activity className="w-3 h-3" /> Current Vitals
                            </h3>
                            <div className="grid grid-cols-2 gap-2">
                              {[
                                { label: 'Heart Rate', value: patient.currentVitals.heartRate, unit: 'bpm', icon: Heart, key: 'hr', color: 'text-red-500' },
                                { label: 'Resp. Rate', value: patient.currentVitals.respiratoryRate, unit: 'br/min', icon: Wind, key: 'rr', color: 'text-blue-500' },
                                { label: 'SpO₂', value: patient.currentVitals.spo2, unit: '%', icon: Activity, key: 'spo2', color: 'text-indigo-500' },
                                { label: 'Blood Pressure', value: patient.currentVitals.systolicBP, unit: `/${patient.currentVitals.diastolicBP} mmHg`, icon: Droplet, key: 'sbp', color: 'text-red-500' },
                                { label: 'Temperature', value: patient.currentVitals.temperature, unit: '°C', icon: Thermometer, key: 'temp', color: 'text-orange-500' },
                                { label: 'ECG (ST)', value: patient.currentVitals.ecg, unit: 'mV', icon: Zap, key: 'ecg', color: 'text-yellow-500' },
                                { label: 'Glucose', value: patient.currentVitals.glucose, unit: 'mg/dL', icon: Candy, key: 'glucose', color: 'text-pink-500' },
                              ].map((v) => {
                                const status = getVitalStatus(v.key, v.value, patient.isPediatric);
                                return (
                                  <div
                                    key={v.key}
                                    className="p-3 rounded-xl hover:scale-[1.02] transition-transform duration-200"
                                    style={{
                                      ...glassVitalTile,
                                      background: status === 'critical' ? 'rgba(239,68,68,0.08)' : status === 'warning' ? 'rgba(245,158,11,0.08)' : glassVitalTile.background,
                                      border: `1px solid ${status === 'critical' ? 'rgba(239,68,68,0.25)' : status === 'warning' ? 'rgba(245,158,11,0.25)' : isDark ? 'rgba(2,132,199,0.2)' : 'rgba(255,255,255,0.6)'}`,
                                      boxShadow: status === 'critical' ? `0 3px 12px rgba(239,68,68,0.1)${isDark ? '' : ', inset 0 1px 0 rgba(255,255,255,0.5)'}`
                                        : status === 'warning' ? `0 3px 12px rgba(245,158,11,0.1)${isDark ? '' : ', inset 0 1px 0 rgba(255,255,255,0.5)'}`
                                        : glassVitalTile.boxShadow,
                                    }}
                                  >
                                    <div className="flex items-center gap-1.5 mb-1">
                                      <v.icon className={`w-3.5 h-3.5 ${v.color}`} />
                                      <span className={`text-[10px] font-bold ${text.body}`}>{v.label}</span>
                                      {status !== 'normal' && (
                                        <div className={`w-1.5 h-1.5 rounded-full ml-auto ${status === 'critical' ? 'bg-red-500 animate-pulse' : 'bg-amber-500'}`} />
                                      )}
                                    </div>
                                    <div className="flex items-baseline gap-1">
                                      <span className={`text-lg font-extrabold tabular-nums ${status === 'critical' ? 'text-red-700' : status === 'warning' ? 'text-amber-700' : text.heading}`}>{v.value}</span>
                                      <span className={`text-[10px] ${text.muted} font-medium`}>{v.unit}</span>
                                    </div>
                                  </div>
                                );
                              })}

                              {/* Assessment info */}
                              <div
                                className="p-3 rounded-xl"
                                style={glassVitalTile}
                              >
                                <div className="flex items-center gap-1.5 mb-1">
                                  <Stethoscope className="w-3.5 h-3.5 text-blue-500" />
                                  <span className={`text-[10px] font-bold ${text.body}`}>Last Assessment</span>
                                </div>
                                <span className={`text-sm font-extrabold ${text.heading}`}>{formatElapsed(patient.lastAssessment)}</span>
                              </div>
                            </div>
                          </div>

                          {/* Center — Vital Trend Charts */}
                          <div className="space-y-3">
                            <h3 className={`text-[11px] font-bold ${text.body} uppercase tracking-wider flex items-center gap-1.5`}>
                              <TrendingUp className="w-3 h-3" /> Vital Trends
                            </h3>
                            <div
                              className="rounded-xl p-3"
                              style={glassVitalTile}
                            >
                              <p className={`text-[10px] font-bold ${text.muted} uppercase mb-2`}>Heart Rate (bpm)</p>
                              <div className="h-[80px]">
                                <ResponsiveContainer width="100%" height="100%">
                                  <AreaChart data={patient.vitalHistory}>
                                    <defs>
                                      <linearGradient id={`hr-${patient.id}`} x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor="#ef4444" stopOpacity={0.15} />
                                        <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
                                      </linearGradient>
                                    </defs>
                                    <XAxis dataKey="time" tick={{ fontSize: 9, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                                    <YAxis hide domain={['auto', 'auto']} />
                                    <Tooltip contentStyle={{ fontSize: 11, borderRadius: 12, border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)', boxShadow: isDark ? '0 4px 12px rgba(0,0,0,0.25)' : '0 4px 12px rgba(0,0,0,0.08)', backgroundColor: isDark ? 'rgba(8,47,73,0.92)' : undefined, color: isDark ? '#e2e8f0' : undefined }} />
                                    <Area type="monotone" dataKey="hr" stroke="#ef4444" strokeWidth={2} fill={`url(#hr-${patient.id})`} dot={false} />
                                  </AreaChart>
                                </ResponsiveContainer>
                              </div>
                            </div>
                            <div
                              className="rounded-xl p-3"
                              style={glassVitalTile}
                            >
                              <p className={`text-[10px] font-bold ${text.muted} uppercase mb-2`}>SpO₂ (%)</p>
                              <div className="h-[80px]">
                                <ResponsiveContainer width="100%" height="100%">
                                  <AreaChart data={patient.vitalHistory}>
                                    <defs>
                                      <linearGradient id={`spo2-${patient.id}`} x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor="#4f46e5" stopOpacity={0.15} />
                                        <stop offset="95%" stopColor="#4f46e5" stopOpacity={0} />
                                      </linearGradient>
                                    </defs>
                                    <XAxis dataKey="time" tick={{ fontSize: 9, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                                    <YAxis hide domain={['auto', 'auto']} />
                                    <Tooltip contentStyle={{ fontSize: 11, borderRadius: 12, border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)', boxShadow: isDark ? '0 4px 12px rgba(0,0,0,0.25)' : '0 4px 12px rgba(0,0,0,0.08)', backgroundColor: isDark ? 'rgba(8,47,73,0.92)' : undefined, color: isDark ? '#e2e8f0' : undefined }} />
                                    <Area type="monotone" dataKey="spo2" stroke="#4f46e5" strokeWidth={2} fill={`url(#spo2-${patient.id})`} dot={false} />
                                  </AreaChart>
                                </ResponsiveContainer>
                              </div>
                            </div>
                          </div>

                          {/* Right — TEWS Evolution & Alerts */}
                          <div className="space-y-3">
                            <h3 className={`text-[11px] font-bold ${text.body} uppercase tracking-wider flex items-center gap-1.5`}>
                              <Shield className="w-3 h-3" /> Case Evolution
                            </h3>

                            {/* TEWS Trend */}
                            <div
                              className="rounded-xl p-3"
                              style={glassVitalTile}
                            >
                              <p className={`text-[10px] font-bold ${text.muted} uppercase mb-2`}>TEWS Score Over Time</p>
                              <div className="h-[80px]">
                                <ResponsiveContainer width="100%" height="100%">
                                  <LineChart data={patient.tewsHistory}>
                                    <XAxis dataKey="time" tick={{ fontSize: 9, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                                    <YAxis hide domain={[0, 17]} />
                                    <Tooltip contentStyle={{ fontSize: 11, borderRadius: 12, border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)', boxShadow: isDark ? '0 4px 12px rgba(0,0,0,0.25)' : '0 4px 12px rgba(0,0,0,0.08)', backgroundColor: isDark ? 'rgba(8,47,73,0.92)' : undefined, color: isDark ? '#e2e8f0' : undefined }} />
                                    <Line type="monotone" dataKey="score" stroke="#4f46e5" strokeWidth={2} dot={{ r: 3, fill: '#4f46e5' }} />
                                  </LineChart>
                                </ResponsiveContainer>
                              </div>
                            </div>

                            {/* Recent Alerts */}
                            <div
                              className="rounded-xl p-3"
                              style={glassVitalTile}
                            >
                              <p className={`text-[10px] font-bold ${text.muted} uppercase mb-2`}>Recent AI Alerts</p>
                              {patient.alerts && patient.alerts.length > 0 ? (
                                <div className="space-y-1.5">
                                  {patient.alerts.map((alert, i) => (
                                    <div
                                      key={i}
                                      className="px-2.5 py-2 rounded-lg text-xs"
                                      style={{
                                        background: alert.severity === 'CRITICAL' ? 'rgba(239,68,68,0.06)' :
                                          alert.severity === 'HIGH' ? 'rgba(249,115,22,0.06)' :
                                          alert.severity === 'MEDIUM' ? 'rgba(234,179,8,0.06)' : 'rgba(148,163,184,0.06)',
                                        border: `1px solid ${alert.severity === 'CRITICAL' ? 'rgba(239,68,68,0.2)' :
                                          alert.severity === 'HIGH' ? 'rgba(249,115,22,0.2)' :
                                          alert.severity === 'MEDIUM' ? 'rgba(234,179,8,0.2)' : 'rgba(148,163,184,0.2)'}`,
                                        color: alert.severity === 'CRITICAL' ? '#dc2626' :
                                          alert.severity === 'HIGH' ? '#ea580c' :
                                          alert.severity === 'MEDIUM' ? '#a16207' : '#475569',
                                      }}
                                    >
                                      <div className="flex items-start gap-2">
                                        <AlertTriangle className="w-3 h-3 flex-shrink-0 mt-0.5" />
                                        <div>
                                          <p className="font-bold">{alert.message}</p>
                                          <p className="text-[10px] opacity-70 mt-0.5">{formatElapsed(alert.time)}</p>
                                        </div>
                                      </div>
                                    </div>
                                  ))}
                                </div>
                              ) : (
                                <p className={`text-xs ${text.muted} italic`}>No active alerts</p>
                              )}
                            </div>

                            {/* IoT Devices Summary */}
                            {(() => {
                              const devices = getDevicesForPatient(patient.id);
                              if (devices.length === 0) return null;
                              const summary = getPatientDeviceSummary(patient.id);
                              return (
                                <div
                                  className="rounded-xl p-3"
                                  style={glassVitalTile}
                                >
                                  <p className={`text-[10px] font-bold ${text.muted} uppercase mb-2`}>IoT Devices</p>
                                  <div className="space-y-1.5">
                                    {devices.map((dev) => {
                                      const sigMeta = SIGNAL_QUALITY_META[dev.health.signalQuality];
                                      return (
                                        <div key={dev.id} className="flex items-center gap-2 text-xs">
                                          {dev.connectionStatus === 'CONNECTED' ? (
                                            <Wifi className="w-3 h-3 text-green-500" />
                                          ) : dev.connectionStatus === 'RECONNECTING' ? (
                                            <Radio className="w-3 h-3 text-amber-500 animate-pulse" />
                                          ) : (
                                            <WifiOff className="w-3 h-3 text-red-400" />
                                          )}
                                          <span className={`font-semibold ${text.label} flex-1 truncate`}>{dev.name}</span>
                                          <div className="flex items-center gap-0.5">
                                            {[1, 2, 3, 4].map((bar) => (
                                              <div
                                                key={bar}
                                                className={`w-0.5 rounded-sm ${bar <= sigMeta.bars ? sigMeta.color : 'bg-gray-200'}`}
                                                style={{ height: `${bar * 2 + 2}px` }}
                                              />
                                            ))}
                                          </div>
                                          <span className={`text-[10px] font-bold ${getBatteryColor(dev.health.batteryPercent)}`}>
                                            {Math.round(dev.health.batteryPercent)}%
                                          </span>
                                        </div>
                                      );
                                    })}
                                  </div>
                                  {summary.uncoveredVitals.length > 0 && (
                                    <p className="text-[10px] text-amber-500 font-medium mt-2">
                                      Uncovered: {summary.uncoveredVitals.length} vital{summary.uncoveredVitals.length > 1 ? 's' : ''}
                                    </p>
                                  )}
                                </div>
                              );
                            })()}

                            {/* Action Button */}
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                navigate(`/vitals/${patient.id}`);
                              }}
                              className="w-full bg-gradient-to-r from-slate-800 to-slate-700 text-white px-4 py-2.5 rounded-xl text-xs font-bold hover:shadow-lg hover:-translate-y-0.5 transition-all duration-300 flex items-center justify-center gap-2 shadow-md"
                            >
                              <Eye className="w-4 h-4" />
                              Full Monitoring View
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
          </div>
        </div>

      </div>

      {startTarget && (
        <StartMonitoringConfirmModal
          patientName={startTarget.fullName}
          onConfirm={() => handleStartMonitoring(startTarget)}
          onClose={() => setStartTarget(null)}
        />
      )}

      {endTarget && (
        <EndMonitoringConfirmModal
          patientName={endTarget.patient.fullName}
          onConfirm={() => handleEndMonitoring(endTarget.patient.id, endTarget.sessionId)}
          onClose={() => setEndTarget(null)}
        />
      )}
    </div>
  );
}
