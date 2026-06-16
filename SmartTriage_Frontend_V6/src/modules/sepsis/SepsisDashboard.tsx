/* ═══════════════════════════════════════════════════════════════
   Sepsis Screening Dashboard — Module 8
   Hospital-wide sepsis detection, qSOFA/SIRS tracking & bundle management
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Thermometer, RefreshCw, Loader2, CheckCircle2, Circle,
  Clock, Activity, Play, ClipboardCheck,
  Droplets, Syringe, Pill, FlaskConical, RotateCcw,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useScopedView } from '@/hooks/useScopedView';
import { useAuthStore } from '@/store/authStore';
import { sepsisApi } from '@/api/sepsis';
import { CrossZoneRestrictedPanel } from '@/components/CrossZoneRestrictedPanel';
import type { SepsisScreening } from '@/api/sepsis';
import { format } from 'date-fns';

/* ── Filter modes ── */
type FilterMode = 'all' | 'qsofa_high' | 'bundle_in_progress' | 'completed';

/* ── qSOFA color coding ── */
const qsofaColor = (score: number) => {
  if (score >= 3) return { text: 'text-red-500', bg: 'bg-red-500/10', border: 'border-red-500/20' };
  if (score >= 2) return { text: 'text-amber-500', bg: 'bg-amber-500/10', border: 'border-amber-500/20' };
  return { text: 'text-emerald-500', bg: 'bg-emerald-500/10', border: 'border-emerald-500/20' };
};

/* ── Sepsis status badge ──
   Keys MUST match the backend SepsisStatus enum exactly. Previously they used
   POSSIBLE/PROBABLE/SEPSIS, which never matched SIRS_POSITIVE/SEPSIS_SUSPECTED,
   so a real sepsis-suspected case fell through to a wrong colour. */
const STATUS_FALLBACK = { color: 'text-slate-400', bg: 'bg-slate-500/10' };
const STATUS_CONFIG: Record<string, { color: string; bg: string }> = {
  NO_SEPSIS:        { color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
  SIRS_POSITIVE:    { color: 'text-amber-400', bg: 'bg-amber-500/10' },
  SEPSIS_SUSPECTED: { color: 'text-red-400', bg: 'bg-red-500/10' },
  SEVERE_SEPSIS:    { color: 'text-red-500', bg: 'bg-red-500/15' },
  SEPTIC_SHOCK:     { color: 'text-red-600', bg: 'bg-red-500/20' },
};

/* Statuses for which the 1-hour bundle is required (mirrors the backend). */
const BUNDLE_REQUIRED_STATUSES = ['SEPSIS_SUSPECTED', 'SEVERE_SEPSIS', 'SEPTIC_SHOCK'];

/* ── Bundle checklist items ── */
const BUNDLE_ITEMS: { key: keyof SepsisScreening; label: string; icon: typeof Droplets }[] = [
  { key: 'bloodCultureObtained', label: 'Blood Culture', icon: Droplets },
  { key: 'broadSpectrumAntibiotics', label: 'Antibiotics', icon: Pill },
  { key: 'ivCrystalloidBolus', label: 'IV Crystalloid', icon: Syringe },
  { key: 'lactateMeasured', label: 'Lactate', icon: FlaskConical },
  { key: 'vasopressorsIfNeeded', label: 'Vasopressors', icon: Activity },
  { key: 'repeatLactateIfElevated', label: 'Repeat Lactate', icon: RotateCcw },
];

/* ── Elapsed time formatter ── */
function formatElapsed(startIso: string): string {
  const ms = Date.now() - new Date(startIso).getTime();
  if (ms < 0) return '0m';
  const totalMin = Math.floor(ms / 60000);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export function SepsisDashboard() {
  const { glassCard, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const scope = useScopedView();

  const [screenings, setScreenings] = useState<SepsisScreening[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<FilterMode>('all');
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [, setTick] = useState(0);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /* ── Load active screenings ── */
  const loadScreenings = useCallback(async () => {
    if (!hospitalId || scope.mode === 'RESTRICTED') return;
    setLoading(true);
    try {
      // ZONE_SCOPED → pass zone, backend returns only this zone's cases.
      // HOSPITAL_WIDE → omit zone, backend returns every case.
      const data = await sepsisApi.getActive(
        hospitalId,
        scope.mode === 'ZONE_SCOPED' ? scope.zone ?? undefined : undefined,
      );
      setScreenings(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load sepsis screenings:', err);
      setScreenings([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, scope.mode, scope.zone]);

  useEffect(() => { loadScreenings(); }, [loadScreenings]);

  /* ── Timer tick for bundle elapsed ── */
  useEffect(() => {
    tickRef.current = setInterval(() => setTick((t) => t + 1), 30000);
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, []);

  /* ── Filter logic ── */
  const filtered = screenings.filter((s) => {
    switch (filter) {
      case 'qsofa_high': return s.qsofaScore >= 2;
      case 'bundle_in_progress': return s.bundleStartedAt !== null && s.bundleCompletedAt === null;
      case 'completed': return s.bundleCompletedAt !== null;
      default: return true;
    }
  });

  /* ── Counts ── */
  const activeCount = screenings.length;
  const qsofaHighCount = screenings.filter((s) => s.qsofaScore >= 2).length;
  const bundleInProgressCount = screenings.filter((s) => s.bundleStartedAt && !s.bundleCompletedAt).length;

  /* ── Start bundle action ── */
  const handleStartBundle = async (screeningId: string) => {
    setActionLoading(screeningId);
    try {
      await sepsisApi.startBundle(screeningId);
      await loadScreenings();
    } catch (err) {
      console.error('Failed to start bundle:', err);
    } finally {
      setActionLoading(null);
    }
  };

  /* ── Complete a bundle item (B7) ──
   * The backend completes an item (one-way: marks done) at
   * PUT /sepsis/bundle/{id}/item/{item}; there is no "un-complete". Sepsis
   * bundle actions are one-way clinically (you can't un-obtain a blood
   * culture), so a click on an already-done item is a no-op. */
  const handleToggleBundleItem = async (screening: SepsisScreening, key: keyof SepsisScreening) => {
    const current = screening[key] as boolean;
    if (current) return; // already complete — backend has no un-complete
    // Map the SepsisScreening field key → the SepsisBundleItem enum value
    // the backend expects on the path.
    const itemEnum: Partial<Record<keyof SepsisScreening, string>> = {
      bloodCultureObtained: 'BLOOD_CULTURE_OBTAINED',
      broadSpectrumAntibiotics: 'BROAD_SPECTRUM_ANTIBIOTICS',
      ivCrystalloidBolus: 'IV_CRYSTALLOID_BOLUS',
      lactateMeasured: 'LACTATE_MEASURED',
      vasopressorsIfNeeded: 'VASOPRESSORS_IF_NEEDED',
      repeatLactateIfElevated: 'REPEAT_LACTATE_IF_ELEVATED',
    };
    const item = itemEnum[key];
    if (!item) return;
    setActionLoading(`${screening.id}-${key}`);
    try {
      await sepsisApi.completeBundleItem(screening.id, item);
      await loadScreenings();
    } catch (err) {
      console.error('Failed to complete bundle item:', err);
    } finally {
      setActionLoading(null);
    }
  };

  /* ── Bundle progress ── */
  const bundleProgress = (s: SepsisScreening): number => {
    return BUNDLE_ITEMS.filter((item) => s[item.key] === true).length;
  };

  /* ── Bundle timer color ── */
  const bundleTimerColor = (startIso: string): string => {
    const minutes = Math.floor((Date.now() - new Date(startIso).getTime()) / 60000);
    if (minutes >= 60) return 'text-red-500';
    if (minutes >= 45) return 'text-amber-500';
    return 'text-emerald-500';
  };

  // Off-shift clinicians have no zone to scope by → show the
  // restriction card with a clear "pick up a shift" hint. On-shift
  // clinicians fall through and see their zone's cases; admins / CN /
  // shift-lead see the full hospital view.
  // Don't render the restriction panel until the shift fetch resolves —
  // useMyShift starts isLoading=true with no assignment, which would
  // otherwise flash the "you're off shift" card for every user (incl.
  // admins/CN) on first paint.
  if (scope.isLoading) {
    return (
      <div className="min-h-full flex items-center justify-center p-10">
        <div className="w-8 h-8 rounded-full border-2 border-slate-400/40 border-t-slate-500 animate-spin" />
      </div>
    );
  }

  if (scope.mode === 'RESTRICTED') {
    return (
      <CrossZoneRestrictedPanel
        pageTitle="Sepsis Screening"
        zone={null}
        reason="OFF_SHIFT"
      />
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-orange-500/20 flex items-center justify-center">
                  <Thermometer className="w-5 h-5 text-orange-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Sepsis Screening</h1>
                  <p className="text-white/50 text-xs">Active screenings &amp; bundle management</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {qsofaHighCount > 0 && (
                  <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-red-500/20 border border-red-500/30">
                    <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                    <span className="text-red-400 text-xs font-bold">{qsofaHighCount} qSOFA &ge; 2</span>
                  </div>
                )}
                <div className="px-3 py-1.5 rounded-lg bg-white/10">
                  <span className="text-white/70 text-xs font-bold">{activeCount} Active</span>
                </div>
                <button
                  onClick={loadScreenings}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>

          {/* ── Filter Tabs ── */}
          <div
            className="flex gap-1 px-4 py-2"
            style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}
          >
            {([
              ['all', 'All Active'],
              ['qsofa_high', 'qSOFA \u2265 2'],
              ['bundle_in_progress', 'Bundle In Progress'],
              ['completed', 'Completed'],
            ] as [FilterMode, string][]).map(([key, label]) => (
              <button
                key={key}
                onClick={() => setFilter(key)}
                className={`px-4 py-2 text-[11px] font-bold rounded-lg transition-all ${
                  filter === key
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : isDark
                      ? 'text-slate-400 hover:text-white hover:bg-white/5'
                      : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                }`}
              >
                {label}
                {key === 'qsofa_high' && qsofaHighCount > 0 && (
                  <span className="ml-1.5 px-1.5 py-0.5 text-[9px] rounded-full bg-red-500/20 text-red-400">{qsofaHighCount}</span>
                )}
                {key === 'bundle_in_progress' && bundleInProgressCount > 0 && (
                  <span className="ml-1.5 px-1.5 py-0.5 text-[9px] rounded-full bg-amber-500/20 text-amber-400">{bundleInProgressCount}</span>
                )}
              </button>
            ))}
          </div>
        </div>

        {/* ── Content ── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
            <p className={`text-sm font-bold ${text.heading}`}>No active screenings</p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              {filter === 'all'
                ? 'No sepsis screenings are currently active for this hospital'
                : 'No screenings match the selected filter'}
            </p>
          </div>
        ) : (
          <div className="space-y-4">
            {filtered.map((screening, i) => {
              const qc = qsofaColor(screening.qsofaScore);
              const statusCfg = STATUS_CONFIG[screening.sepsisStatus] || STATUS_FALLBACK;
              const progress = bundleProgress(screening);
              const bundleActive = screening.bundleStartedAt && !screening.bundleCompletedAt;

              return (
                <div
                  key={screening.id}
                  className="rounded-2xl overflow-hidden animate-fade-up"
                  style={{ ...glassCard, animationDelay: `${i * 0.04}s` }}
                >
                  {/* ── Card Header ── */}
                  <div className="px-5 py-4">
                    <div className="flex items-start justify-between gap-4">
                      {/* Left: Scores & status */}
                      <div className="flex items-start gap-4 flex-1 min-w-0">
                        {/* qSOFA badge */}
                        <div className={`shrink-0 w-14 h-14 rounded-xl ${qc.bg} border ${qc.border} flex flex-col items-center justify-center`}>
                          <span className={`text-lg font-black ${qc.text}`}>{screening.qsofaScore}</span>
                          <span className={`text-[8px] font-bold uppercase tracking-wider ${qc.text}`}>qSOFA</span>
                        </div>

                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 flex-wrap mb-1.5">
                            {/* Sepsis status badge */}
                            <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${statusCfg.bg} ${statusCfg.color}`}>
                              {screening.sepsisStatus.replace(/_/g, ' ')}
                            </span>
                            {/* SIRS badge */}
                            <span className={`text-[10px] font-bold px-2 py-1 rounded-lg ${isDark ? 'bg-white/5 text-slate-300' : 'bg-slate-100 text-slate-600'}`}>
                              SIRS: {screening.sirsScore}/4
                            </span>
                            {/* Bundle progress */}
                            {screening.bundleStartedAt && (
                              <span className={`text-[10px] font-bold px-2 py-1 rounded-lg ${
                                progress === 6 ? 'bg-emerald-500/10 text-emerald-400' : 'bg-cyan-500/10 text-cyan-400'
                              }`}>
                                Bundle: {progress}/6
                              </span>
                            )}
                          </div>

                          {/* Patient identity — so a multi-patient board shows WHICH patient each card is */}
                          {(screening.patientName || screening.visitNumber) && (
                            <p className={`text-sm font-bold ${text.heading} mb-1`}>
                              {screening.patientName || 'Patient'}
                              {screening.visitNumber && (
                                <span className={`ml-2 text-[10px] font-mono font-normal ${text.muted}`}>{screening.visitNumber}</span>
                              )}
                            </p>
                          )}

                          {/* Screened info */}
                          <div className="flex items-center gap-3 flex-wrap">
                            <p className={`text-xs ${text.body}`}>
                              Screened by <span className={`font-semibold ${text.heading}`}>{screening.screenedByName}</span>
                            </p>
                            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                              <Clock className="w-3 h-3" />
                              {format(new Date(screening.screenedAt), 'dd MMM yyyy HH:mm')}
                            </span>
                          </div>

                          {/* qSOFA criteria */}
                          <div className="flex items-center gap-3 mt-2">
                            <span className={`text-[10px] px-2 py-0.5 rounded ${screening.alteredMentation ? 'bg-red-500/10 text-red-400' : isDark ? 'bg-white/5 text-slate-500' : 'bg-slate-100 text-slate-400'}`}>
                              {screening.alteredMentation ? '\u2713' : '\u2717'} Altered Mentation
                            </span>
                            <span className={`text-[10px] px-2 py-0.5 rounded ${screening.respiratoryRateHigh ? 'bg-red-500/10 text-red-400' : isDark ? 'bg-white/5 text-slate-500' : 'bg-slate-100 text-slate-400'}`}>
                              {screening.respiratoryRateHigh ? '\u2713' : '\u2717'} RR &ge; 22
                            </span>
                            <span className={`text-[10px] px-2 py-0.5 rounded ${screening.systolicBpLow ? 'bg-red-500/10 text-red-400' : isDark ? 'bg-white/5 text-slate-500' : 'bg-slate-100 text-slate-400'}`}>
                              {screening.systolicBpLow ? '\u2713' : '\u2717'} SBP &le; 100
                            </span>
                          </div>

                          {/* Safety banners — data quality + pediatric caveat */}
                          {screening.insufficientData && (
                            <p className="text-[10px] font-semibold text-amber-400 mt-2">
                              ⚠ Insufficient vitals — a negative screen is NOT reassuring.
                              {screening.dataQualityNote ? ` ${screening.dataQualityNote}` : ''}
                            </p>
                          )}
                          {screening.pediatric && screening.pediatricCaveat && (
                            <p className="text-[10px] font-semibold text-fuchsia-300 mt-2 leading-relaxed">
                              ⚠ {screening.pediatricCaveat}
                            </p>
                          )}

                          {/* Infection source & lactate */}
                          {(screening.suspectedInfectionSource || screening.lactateLevel !== null) && (
                            <div className="flex items-center gap-3 mt-2">
                              {screening.suspectedInfectionSource && (
                                <span className={`text-[10px] ${text.muted}`}>
                                  Source: <span className={text.body}>{screening.suspectedInfectionSource}</span>
                                </span>
                              )}
                              {screening.lactateLevel !== null && (
                                <span className={`text-[10px] font-bold ${screening.lactateLevel >= 4 ? 'text-red-400' : screening.lactateLevel >= 2 ? 'text-amber-400' : 'text-emerald-400'}`}>
                                  Lactate: {screening.lactateLevel} mmol/L
                                </span>
                              )}
                            </div>
                          )}
                        </div>
                      </div>

                      {/* Right: Bundle timer */}
                      <div className="shrink-0 text-right">
                        {bundleActive && screening.bundleStartedAt && (
                          <div className="flex flex-col items-end gap-1">
                            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Bundle Timer</span>
                            <div className={`text-xl font-black tabular-nums ${bundleTimerColor(screening.bundleStartedAt)}`}>
                              {formatElapsed(screening.bundleStartedAt)}
                            </div>
                            <span className={`text-[9px] ${text.muted}`}>Target: 1h</span>
                          </div>
                        )}
                        {screening.bundleCompletedAt && (
                          <div className="flex items-center gap-1.5">
                            <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                            <span className="text-[10px] font-bold text-emerald-400">Bundle Complete</span>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* ── Bundle Checklist ── */}
                  {screening.bundleStartedAt && (
                    <div
                      className="px-5 py-3 border-t"
                      style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}
                    >
                      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2">
                        {BUNDLE_ITEMS.map((item) => {
                          const done = screening[item.key] === true;
                          const isUpdating = actionLoading === `${screening.id}-${item.key}`;
                          const Icon = item.icon;

                          return (
                            <button
                              key={item.key}
                              onClick={() => handleToggleBundleItem(screening, item.key)}
                              disabled={isUpdating || screening.bundleCompletedAt !== null}
                              className={`flex items-center gap-2 px-3 py-2.5 rounded-xl text-[11px] font-bold transition-all ${
                                done
                                  ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                                  : isDark
                                    ? 'bg-white/5 text-slate-400 border border-white/5 hover:bg-white/10'
                                    : 'bg-slate-50 text-slate-500 border border-slate-200/50 hover:bg-slate-100'
                              } ${screening.bundleCompletedAt ? 'cursor-default' : 'cursor-pointer'}`}
                            >
                              {isUpdating ? (
                                <Loader2 className="w-3.5 h-3.5 animate-spin shrink-0" />
                              ) : done ? (
                                <CheckCircle2 className="w-3.5 h-3.5 shrink-0" />
                              ) : (
                                <Circle className="w-3.5 h-3.5 shrink-0" />
                              )}
                              <Icon className="w-3 h-3 shrink-0 opacity-60" />
                              <span className="truncate">{item.label}</span>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* ── Action Bar ── bundle is offered for ALL bundle-required
                      statuses (SIRS/lactate-driven sepsis too), not qSOFA>=2 alone. */}
                  {!screening.bundleStartedAt && BUNDLE_REQUIRED_STATUSES.includes(screening.sepsisStatus) && (
                    <div
                      className="px-5 py-3 border-t"
                      style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}
                    >
                      <button
                        onClick={() => handleStartBundle(screening.id)}
                        disabled={actionLoading === screening.id}
                        className="inline-flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-xl bg-orange-500/10 text-orange-400 hover:bg-orange-500/20 transition-colors disabled:opacity-50"
                      >
                        {actionLoading === screening.id ? (
                          <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        ) : (
                          <Play className="w-3.5 h-3.5" />
                        )}
                        Start Sepsis Bundle
                      </button>
                    </div>
                  )}

                  {/* ── Notes ── */}
                  {screening.notes && (
                    <div
                      className="px-5 py-2.5 border-t"
                      style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}
                    >
                      <p className={`text-[11px] ${text.muted}`}>
                        <ClipboardCheck className="w-3 h-3 inline mr-1" />
                        {screening.notes}
                      </p>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
