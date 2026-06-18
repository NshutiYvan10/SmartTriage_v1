/* ═══════════════════════════════════════════════════════════════
   Quality Metrics Dashboard — Module 21
   Hospital-wide quality KPIs, triage distribution, time metrics,
   clinical quality, capacity & safety indicators
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  BarChart3, RefreshCw, Loader2, TrendingUp, TrendingDown,
  Minus, Users, Activity, Clock, ShieldCheck, AlertTriangle,
  Heart, Bed, ArrowUpRight, ArrowDownRight, Zap,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { qualityApi } from '@/api/quality';
import type { QualityMetricSnapshot } from '@/api/quality';
import { format } from 'date-fns';

/* ── Period options ── */
type SnapshotPeriod = 'HOURLY' | 'SHIFT' | 'DAILY' | 'WEEKLY' | 'MONTHLY';

const PERIODS: { value: SnapshotPeriod; label: string }[] = [
  { value: 'HOURLY', label: 'Hourly' },
  { value: 'SHIFT', label: 'Shift' },
  { value: 'DAILY', label: 'Daily' },
  { value: 'WEEKLY', label: 'Weekly' },
  { value: 'MONTHLY', label: 'Monthly' },
];

/* ── Time metric targets (minutes) ── */
const TIME_TARGETS: Record<string, number> = {
  averageWaitTimeMinutes: 30,
  averageDoorToTriageMinutes: 10,
  averageDoorToPhysicianMinutes: 60,
  averageTotalEdStayMinutes: 240,
};

/* ── Color helpers ── */
const timeColor = (value: number, target: number): string => {
  const ratio = value / target;
  if (ratio <= 0.75) return 'text-emerald-400';
  if (ratio <= 1.0) return 'text-amber-400';
  return 'text-red-400';
};

const timeBg = (value: number, target: number): string => {
  const ratio = value / target;
  if (ratio <= 0.75) return 'bg-emerald-500/10';
  if (ratio <= 1.0) return 'bg-amber-500/10';
  return 'bg-red-500/10';
};

const rateColor = (value: number): string => {
  if (value >= 90) return 'text-emerald-400';
  if (value >= 70) return 'text-amber-400';
  return 'text-red-400';
};

const rateBg = (value: number): string => {
  if (value >= 90) return 'bg-emerald-500/10';
  if (value >= 70) return 'bg-amber-500/10';
  return 'bg-red-500/10';
};

/* ── Delta badge ── */
function DeltaBadge({ current, previous, invert = false }: { current: number; previous: number | null; invert?: boolean }) {
  if (previous === null || previous === 0) return null;
  const delta = ((current - previous) / previous) * 100;
  const isPositive = delta > 0;
  const isGood = invert ? !isPositive : isPositive;
  const color = Math.abs(delta) < 1
    ? 'text-slate-400'
    : isGood ? 'text-emerald-400' : 'text-red-400';
  const Icon = Math.abs(delta) < 1 ? Minus : isPositive ? TrendingUp : TrendingDown;

  return (
    <span className={`inline-flex items-center gap-0.5 text-[10px] font-semibold ${color}`}>
      <Icon className="w-3 h-3" />
      {Math.abs(delta).toFixed(1)}%
    </span>
  );
}

/* ═══════════════════════════════════════════════════════════════ */

export function QualityDashboard() {
  const { glassCard, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [current, setCurrent] = useState<QualityMetricSnapshot | null>(null);
  const [previous, setPrevious] = useState<QualityMetricSnapshot | null>(null);
  const [period, setPeriod] = useState<SnapshotPeriod>('DAILY');
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);

  /* ── Load latest snapshot ── */
  const loadData = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const latest = await qualityApi.getLatest(hospitalId);
      setCurrent(latest);

      /* Load history to find previous period for comparison */
      const history = await qualityApi.getForHospital(hospitalId, 0);
      if (history.content.length > 1) {
        const prev = history.content.find(
          (s) => s.snapshotPeriod === (latest?.snapshotPeriod || period) && s.id !== latest?.id
        );
        setPrevious(prev || null);
      }
    } catch (err) {
      console.error('Failed to load quality metrics:', err);
      setCurrent(null);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, period]);

  useEffect(() => { loadData(); }, [loadData]);

  /* ── Generate new snapshot ── */
  const handleGenerate = async () => {
    if (!hospitalId) return;
    setGenerating(true);
    try {
      const today = format(new Date(), 'yyyy-MM-dd');
      const snapshot = await qualityApi.generate(hospitalId, today, period);
      setCurrent(snapshot);
      await loadData();
    } catch (err) {
      console.error('Failed to generate snapshot:', err);
    } finally {
      setGenerating(false);
    }
  };

  /* ── Helper to get previous value for delta ── */
  const prevVal = (key: keyof QualityMetricSnapshot): number | null => {
    if (!previous) return null;
    return previous[key] as number;
  };

  /* ── Section divider ── */
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-indigo-500/20 flex items-center justify-center">
                  <BarChart3 className="w-5 h-5 text-indigo-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Quality Metrics Dashboard</h1>
                  <p className="text-white/50 text-xs">
                    {current
                      ? `Snapshot: ${format(new Date(current.snapshotDate), 'MMM dd, yyyy')} — ${current.snapshotPeriod}`
                      : 'No snapshot loaded'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {/* Computing a snapshot is an admin-only write; READ_ONLY (auditor)
                    can view but not trigger a recompute (backend enforces this). */}
                {user?.role !== 'READ_ONLY' && (
                <button
                  onClick={handleGenerate}
                  disabled={generating}
                  className="flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-500/20 border border-indigo-500/30 text-indigo-300 text-xs font-bold hover:bg-indigo-500/30 transition-colors disabled:opacity-50"
                >
                  {generating ? <Loader2 className="w-4 h-4 animate-spin" /> : <Zap className="w-4 h-4" />}
                  Generate Snapshot
                </button>
                )}
                <button
                  onClick={loadData}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>

          {/* ── Period Selector ── */}
          <div
            className="flex gap-1 px-4 py-2"
            style={{ borderTop: borderStyle }}
          >
            {PERIODS.map(({ value, label }) => (
              <button
                key={value}
                onClick={() => setPeriod(value)}
                className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                  period === value
                    ? 'bg-indigo-500/20 text-indigo-400 border border-indigo-500/30'
                    : `${text.secondary} hover:bg-white/5`
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {/* ── Loading State ── */}
        {loading && (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 animate-spin text-indigo-400" />
          </div>
        )}

        {/* ── Empty State ── */}
        {!loading && !current && (
          <div className="rounded-3xl overflow-hidden" style={glassCard}>
            <div className="flex flex-col items-center justify-center py-16 px-6">
              <BarChart3 className="w-12 h-12 text-slate-500 mb-4" />
              <p className={`text-sm font-semibold ${text.primary}`}>No Quality Snapshots Available</p>
              <p className={`text-xs mt-1 ${text.secondary}`}>Generate a snapshot to view quality metrics</p>
            </div>
          </div>
        )}

        {/* ── Metrics Grid ── */}
        {!loading && current && (
          <div className="space-y-4">

            {/* ── Volume Metrics ── */}
            <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
              <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2">
                  <Users className="w-4 h-4 text-blue-400" />
                  <h2 className={`text-sm font-bold ${text.primary}`}>Patient Volume</h2>
                </div>
              </div>
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-px" style={{ background: borderStyle.includes('rgba') ? 'transparent' : undefined }}>
                {([
                  { label: 'Total Patients', key: 'totalPatients' as const, icon: Users, color: 'text-blue-400', bg: 'bg-blue-500/10' },
                  { label: 'Admissions', key: 'totalAdmissions' as const, icon: ArrowUpRight, color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
                  { label: 'Discharges', key: 'totalDischarges' as const, icon: ArrowDownRight, color: 'text-cyan-400', bg: 'bg-cyan-500/10' },
                  { label: 'Transfers', key: 'totalTransfers' as const, icon: Activity, color: 'text-violet-400', bg: 'bg-violet-500/10' },
                ]).map(({ label, key, icon: Icon, color, bg }) => (
                  <div key={key} className="px-5 py-4">
                    <div className="flex items-center justify-between mb-2">
                      <div className={`w-8 h-8 rounded-lg ${bg} flex items-center justify-center`}>
                        <Icon className={`w-4 h-4 ${color}`} />
                      </div>
                      <DeltaBadge current={current[key]} previous={prevVal(key)} />
                    </div>
                    <p className={`text-2xl font-bold ${text.primary}`}>{current[key].toLocaleString()}</p>
                    <p className={`text-xs mt-0.5 ${text.secondary}`}>{label}</p>
                  </div>
                ))}
              </div>
            </div>

            {/* ── Triage Distribution ── */}
            <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
              <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2">
                  <Activity className="w-4 h-4 text-orange-400" />
                  <h2 className={`text-sm font-bold ${text.primary}`}>Triage Distribution</h2>
                </div>
              </div>
              <div className="grid grid-cols-2 lg:grid-cols-5 gap-px">
                {([
                  { label: 'RED', key: 'redPatients' as const, color: 'text-red-400', bg: 'bg-red-500/15', badgeBg: 'bg-red-500', badgeText: 'text-white' },
                  { label: 'ORANGE', key: 'orangePatients' as const, color: 'text-orange-400', bg: 'bg-orange-500/15', badgeBg: 'bg-orange-500', badgeText: 'text-white' },
                  { label: 'YELLOW', key: 'yellowPatients' as const, color: 'text-yellow-400', bg: 'bg-yellow-500/15', badgeBg: 'bg-yellow-500', badgeText: 'text-black' },
                  { label: 'GREEN', key: 'greenPatients' as const, color: 'text-emerald-400', bg: 'bg-emerald-500/15', badgeBg: 'bg-emerald-500', badgeText: 'text-white' },
                  { label: 'Pediatric', key: 'pediatricPatients' as const, color: 'text-pink-400', bg: 'bg-pink-500/15', badgeBg: 'bg-pink-500', badgeText: 'text-white' },
                ]).map(({ label, key, color, bg, badgeBg, badgeText }) => {
                  const value = current[key];
                  const total = current.totalPatients || 1;
                  const pct = ((value / total) * 100).toFixed(1);
                  return (
                    <div key={key} className="px-5 py-4">
                      <div className="flex items-center justify-between mb-2">
                        <span className={`px-2 py-0.5 rounded-md text-[10px] font-bold ${badgeBg} ${badgeText}`}>
                          {label}
                        </span>
                        <DeltaBadge current={value} previous={prevVal(key)} />
                      </div>
                      <p className={`text-2xl font-bold ${color}`}>{value}</p>
                      <div className="mt-2">
                        <div className="w-full h-1.5 rounded-full bg-white/5">
                          <div
                            className={`h-full rounded-full ${badgeBg}`}
                            style={{ width: `${Math.min(parseFloat(pct), 100)}%` }}
                          />
                        </div>
                        <p className={`text-[10px] mt-1 ${text.secondary}`}>{pct}% of total</p>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* ── Time Metrics ── */}
            <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
              <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2">
                  <Clock className="w-4 h-4 text-cyan-400" />
                  <h2 className={`text-sm font-bold ${text.primary}`}>Time Metrics</h2>
                </div>
              </div>
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-px">
                {([
                  { label: 'Avg Wait Time', key: 'averageWaitTimeMinutes' as const, target: TIME_TARGETS.averageWaitTimeMinutes, unit: 'min' },
                  { label: 'Door-to-Triage', key: 'averageDoorToTriageMinutes' as const, target: TIME_TARGETS.averageDoorToTriageMinutes, unit: 'min' },
                  { label: 'Door-to-Physician', key: 'averageDoorToPhysicianMinutes' as const, target: TIME_TARGETS.averageDoorToPhysicianMinutes, unit: 'min' },
                  { label: 'Total ED Stay', key: 'averageTotalEdStayMinutes' as const, target: TIME_TARGETS.averageTotalEdStayMinutes, unit: 'min' },
                ]).map(({ label, key, target, unit }) => {
                  const value = current[key];
                  return (
                    <div key={key} className="px-5 py-4">
                      <div className="flex items-center justify-between mb-2">
                        <div className={`w-8 h-8 rounded-lg ${timeBg(value, target)} flex items-center justify-center`}>
                          <Clock className={`w-4 h-4 ${timeColor(value, target)}`} />
                        </div>
                        <DeltaBadge current={value} previous={prevVal(key)} invert />
                      </div>
                      <p className={`text-2xl font-bold ${timeColor(value, target)}`}>
                        {value.toFixed(0)}
                        <span className={`text-xs font-normal ml-1 ${text.secondary}`}>{unit}</span>
                      </p>
                      <p className={`text-xs mt-0.5 ${text.secondary}`}>{label}</p>
                      <div className="mt-2">
                        <div className="w-full h-1.5 rounded-full bg-white/5">
                          <div
                            className={`h-full rounded-full transition-all ${
                              value <= target ? 'bg-emerald-500' : value <= target * 1.5 ? 'bg-amber-500' : 'bg-red-500'
                            }`}
                            style={{ width: `${Math.min((value / (target * 2)) * 100, 100)}%` }}
                          />
                        </div>
                        <p className={`text-[10px] mt-1 ${text.secondary}`}>Target: {target} {unit}</p>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* ── Clinical Quality ── */}
            <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
              <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2">
                  <ShieldCheck className="w-4 h-4 text-emerald-400" />
                  <h2 className={`text-sm font-bold ${text.primary}`}>Clinical Quality</h2>
                </div>
              </div>
              <div className="grid grid-cols-1 lg:grid-cols-3 gap-px">
                {([
                  { label: 'Sepsis Screening Rate', key: 'sepsisScreeningRate' as const },
                  { label: 'Bundle Compliance', key: 'sepsisBundleComplianceRate' as const },
                  { label: '% Seen Within Target', key: 'percentSeenWithinTarget' as const },
                ]).map(({ label, key }) => {
                  const value = current[key];
                  return (
                    <div key={key} className="px-5 py-4">
                      <div className="flex items-center justify-between mb-2">
                        <div className={`w-8 h-8 rounded-lg ${rateBg(value)} flex items-center justify-center`}>
                          <ShieldCheck className={`w-4 h-4 ${rateColor(value)}`} />
                        </div>
                        <DeltaBadge current={value} previous={prevVal(key)} />
                      </div>
                      <p className={`text-2xl font-bold ${rateColor(value)}`}>
                        {value.toFixed(1)}
                        <span className={`text-xs font-normal ml-0.5 ${text.secondary}`}>%</span>
                      </p>
                      <p className={`text-xs mt-0.5 ${text.secondary}`}>{label}</p>
                      <div className="mt-2">
                        <div className="w-full h-1.5 rounded-full bg-white/5">
                          <div
                            className={`h-full rounded-full transition-all ${
                              value >= 90 ? 'bg-emerald-500' : value >= 70 ? 'bg-amber-500' : 'bg-red-500'
                            }`}
                            style={{ width: `${Math.min(value, 100)}%` }}
                          />
                        </div>
                        <p className={`text-[10px] mt-1 ${text.secondary}`}>Target: 90%</p>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* ── Capacity & Safety ── */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

              {/* Capacity */}
              <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
                <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                  <div className="flex items-center gap-2">
                    <Bed className="w-4 h-4 text-violet-400" />
                    <h2 className={`text-sm font-bold ${text.primary}`}>Capacity</h2>
                  </div>
                </div>
                <div className="px-5 py-4 space-y-4">
                  {([
                    { label: 'Peak ED Occupancy', key: 'peakEdOccupancy' as const, color: 'bg-violet-500', warn: 0.85 },
                    { label: 'ICU Bed Utilization', key: 'icuBedUtilizationPercent' as const, color: 'bg-rose-500', warn: 0.90 },
                  ]).map(({ label, key, color, warn }) => {
                    const value = current[key];
                    const pct = key === 'icuBedUtilizationPercent' ? value : value;
                    const isHigh = pct > warn * 100;
                    return (
                      <div key={key}>
                        <div className="flex items-center justify-between mb-1.5">
                          <p className={`text-xs font-semibold ${text.secondary}`}>{label}</p>
                          <div className="flex items-center gap-2">
                            <DeltaBadge current={value} previous={prevVal(key)} invert />
                            <span className={`text-sm font-bold ${isHigh ? 'text-red-400' : text.primary}`}>
                              {key === 'icuBedUtilizationPercent' ? `${value.toFixed(1)}%` : value.toFixed(0)}
                            </span>
                          </div>
                        </div>
                        <div className="w-full h-2 rounded-full bg-white/5">
                          <div
                            className={`h-full rounded-full transition-all ${isHigh ? 'bg-red-500' : color}`}
                            style={{ width: `${Math.min(key === 'icuBedUtilizationPercent' ? pct : (pct / 150) * 100, 100)}%` }}
                          />
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>

              {/* Safety */}
              <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
                <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                  <div className="flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 text-amber-400" />
                    <h2 className={`text-sm font-bold ${text.primary}`}>Safety Indicators</h2>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-px">
                  {/* Mortality Rate */}
                  <div className="px-5 py-4">
                    <div className="flex items-center justify-between mb-2">
                      <div className="w-8 h-8 rounded-lg bg-red-500/10 flex items-center justify-center">
                        <Heart className="w-4 h-4 text-red-400" />
                      </div>
                      <DeltaBadge current={current.edMortalityRate} previous={prevVal('edMortalityRate')} invert />
                    </div>
                    <p className={`text-2xl font-bold ${current.edMortalityRate > 2 ? 'text-red-400' : current.edMortalityRate > 1 ? 'text-amber-400' : 'text-emerald-400'}`}>
                      {current.edMortalityRate.toFixed(2)}
                      <span className={`text-xs font-normal ml-0.5 ${text.secondary}`}>%</span>
                    </p>
                    <p className={`text-xs mt-0.5 ${text.secondary}`}>ED Mortality Rate</p>
                  </div>

                  {/* LWBS */}
                  <div className="px-5 py-4">
                    <div className="flex items-center justify-between mb-2">
                      <div className="w-8 h-8 rounded-lg bg-amber-500/10 flex items-center justify-center">
                        <AlertTriangle className="w-4 h-4 text-amber-400" />
                      </div>
                      <DeltaBadge current={current.totalLeftWithoutBeingSeen} previous={prevVal('totalLeftWithoutBeingSeen')} invert />
                    </div>
                    <p className={`text-2xl font-bold ${current.totalLeftWithoutBeingSeen > 10 ? 'text-red-400' : current.totalLeftWithoutBeingSeen > 5 ? 'text-amber-400' : 'text-emerald-400'}`}>
                      {current.totalLeftWithoutBeingSeen}
                    </p>
                    <p className={`text-xs mt-0.5 ${text.secondary}`}>Left Without Being Seen</p>
                  </div>

                  {/* Deaths */}
                  <div className="px-5 py-4">
                    <div className="flex items-center justify-between mb-2">
                      <div className="w-8 h-8 rounded-lg bg-slate-500/10 flex items-center justify-center">
                        <Heart className="w-4 h-4 text-slate-400" />
                      </div>
                      <DeltaBadge current={current.totalDeaths} previous={prevVal('totalDeaths')} invert />
                    </div>
                    <p className={`text-2xl font-bold ${text.primary}`}>{current.totalDeaths}</p>
                    <p className={`text-xs mt-0.5 ${text.secondary}`}>Total Deaths</p>
                  </div>

                  {/* Pediatric */}
                  <div className="px-5 py-4">
                    <div className="flex items-center justify-between mb-2">
                      <div className="w-8 h-8 rounded-lg bg-pink-500/10 flex items-center justify-center">
                        <Users className="w-4 h-4 text-pink-400" />
                      </div>
                      <DeltaBadge current={current.pediatricPatients} previous={prevVal('pediatricPatients')} />
                    </div>
                    <p className={`text-2xl font-bold text-pink-400`}>{current.pediatricPatients}</p>
                    <p className={`text-xs mt-0.5 ${text.secondary}`}>Pediatric Patients</p>
                  </div>
                </div>
              </div>
            </div>

          </div>
        )}

      </div>
    </div>
  );
}
