/* ═══════════════════════════════════════════════════════════════
   Fast-Track Dashboard — Module 9
   Stroke & MI/ACS fast-track activation, timing & outcome tracking
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Zap, RefreshCw, Loader2, CheckCircle2, Clock, AlertTriangle,
  Brain, Heart, Activity, Scan, Pill, ArrowRight,
  CircleDot, FileCheck,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useCanSeeAllZones } from '@/hooks/useCanSeeAllZones';
import { useAuthStore } from '@/store/authStore';
import { fasttrackApi } from '@/api/fasttrack';
import { CrossZoneRestrictedPanel } from '@/components/CrossZoneRestrictedPanel';
import type { FastTrackActivation } from '@/api/fasttrack';
import { format } from 'date-fns';

/* ── Tab type ── */
type TabMode = 'STROKE' | 'MI_ACS';

/* ── Status config ── */
const STATUS_CONFIG: Record<string, { color: string; bg: string; border: string }> = {
  ACTIVATED:    { color: 'text-orange-400', bg: 'bg-orange-500/10', border: 'border-orange-500/20' },
  IN_PROGRESS:  { color: 'text-cyan-400', bg: 'bg-cyan-500/10', border: 'border-cyan-500/20' },
  COMPLETED:    { color: 'text-emerald-400', bg: 'bg-emerald-500/10', border: 'border-emerald-500/20' },
};

/* ── Elapsed time from ISO string ── */
function elapsedMinutes(startIso: string): number {
  return Math.max(0, Math.floor((Date.now() - new Date(startIso).getTime()) / 60000));
}

function formatElapsed(startIso: string): string {
  const mins = elapsedMinutes(startIso);
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

/* ── Door-to timer color based on target ── */
function timerColor(startIso: string, targetMin: number): string {
  const mins = elapsedMinutes(startIso);
  if (mins >= targetMin) return 'text-red-500';
  if (mins >= targetMin * 0.75) return 'text-amber-500';
  return 'text-emerald-500';
}

export function FastTrackDashboard() {
  const { glassCard, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const access = useCanSeeAllZones();

  const [activations, setActivations] = useState<FastTrackActivation[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<TabMode>('STROKE');
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [, setTick] = useState(0);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /* ── ECG dialog state ── */
  const [ecgDialog, setEcgDialog] = useState<{ id: string } | null>(null);
  const [ecgResult, setEcgResult] = useState('');
  const [ecgStElevation, setEcgStElevation] = useState(false);

  /* ── CT dialog state ── */
  const [ctDialog, setCtDialog] = useState<{ id: string } | null>(null);
  const [ctResult, setCtResult] = useState('');
  const [ctHemorrhagic, setCtHemorrhagic] = useState(false);

  /* ── Load active activations ── */
  const loadActivations = useCallback(async () => {
    if (!hospitalId || !access.canSeeAllZones) return;
    setLoading(true);
    try {
      const data = await fasttrackApi.getActive(hospitalId);
      setActivations(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load fast-track activations:', err);
      setActivations([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, access.canSeeAllZones]);

  useEffect(() => { loadActivations(); }, [loadActivations]);

  /* ── Timer tick ── */
  useEffect(() => {
    tickRef.current = setInterval(() => setTick((t) => t + 1), 15000);
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, []);

  /* ── Filtered by tab ── */
  const filtered = activations.filter((a) => a.fastTrackType === tab);
  const strokeCount = activations.filter((a) => a.fastTrackType === 'STROKE').length;
  const miCount = activations.filter((a) => a.fastTrackType === 'MI_ACS').length;

  /* ── Record ECG ── */
  const handleRecordEcg = async () => {
    if (!ecgDialog) return;
    setActionLoading(ecgDialog.id);
    try {
      await fasttrackApi.recordEcg(ecgDialog.id, {
        ecgCompletedAt: new Date().toISOString(),
        ecgResult,
        stElevation: ecgStElevation,
      });
      setEcgDialog(null);
      setEcgResult('');
      setEcgStElevation(false);
      await loadActivations();
    } catch (err) {
      console.error('Failed to record ECG:', err);
    } finally {
      setActionLoading(null);
    }
  };

  /* ── Record CT ── */
  const handleRecordCt = async () => {
    if (!ctDialog) return;
    setActionLoading(ctDialog.id);
    try {
      await fasttrackApi.recordCt(ctDialog.id, {
        ctCompletedAt: new Date().toISOString(),
        ctResult,
        isHemorrhagic: ctHemorrhagic,
      });
      setCtDialog(null);
      setCtResult('');
      setCtHemorrhagic(false);
      await loadActivations();
    } catch (err) {
      console.error('Failed to record CT:', err);
    } finally {
      setActionLoading(null);
    }
  };

  /* ── Complete activation ── */
  const handleComplete = async (id: string, outcome: string) => {
    setActionLoading(id);
    try {
      await fasttrackApi.complete(id, { outcome });
      await loadActivations();
    } catch (err) {
      console.error('Failed to complete fast-track:', err);
    } finally {
      setActionLoading(null);
    }
  };

  if (!access.canSeeAllZones) {
    return (
      <CrossZoneRestrictedPanel
        pageTitle="Fast-Track Activations"
        zone={access.zone ?? null}
        reason={access.reason === 'OFF_SHIFT' ? 'OFF_SHIFT' : 'ZONE_SCOPED'}
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
                <div className="w-10 h-10 rounded-xl bg-yellow-500/20 flex items-center justify-center">
                  <Zap className="w-5 h-5 text-yellow-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Fast-Track Activations</h1>
                  <p className="text-white/50 text-xs">Stroke &amp; MI/ACS time-critical pathways</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="px-3 py-1.5 rounded-lg bg-white/10">
                  <span className="text-white/70 text-xs font-bold">{activations.length} Active</span>
                </div>
                <button
                  onClick={loadActivations}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>

          {/* ── Tabs ── */}
          <div
            className="flex gap-1 px-4 py-2"
            style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}
          >
            {([
              ['STROKE', 'Stroke', strokeCount, Brain],
              ['MI_ACS', 'MI / ACS', miCount, Heart],
            ] as [TabMode, string, number, typeof Brain][]).map(([key, label, count, Icon]) => (
              <button
                key={key}
                onClick={() => setTab(key)}
                className={`flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-lg transition-all ${
                  tab === key
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : isDark
                      ? 'text-slate-400 hover:text-white hover:bg-white/5'
                      : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                }`}
              >
                <Icon className="w-3.5 h-3.5" />
                {label}
                {count > 0 && (
                  <span className={`ml-1 px-1.5 py-0.5 text-[9px] rounded-full ${
                    tab === key ? 'bg-white/20 text-white' : 'bg-cyan-500/15 text-cyan-400'
                  }`}>
                    {count}
                  </span>
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
            <p className={`text-sm font-bold ${text.heading}`}>No active {tab === 'STROKE' ? 'stroke' : 'MI/ACS'} fast-tracks</p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              All time-critical pathways are clear for this category
            </p>
          </div>
        ) : (
          <div className="space-y-4">
            {filtered.map((activation, i) => {
              const statusCfg = STATUS_CONFIG[activation.status] || STATUS_CONFIG.ACTIVATED;
              const isStroke = activation.fastTrackType === 'STROKE';

              return (
                <div
                  key={activation.id}
                  className="rounded-2xl overflow-hidden animate-fade-up"
                  style={{ ...glassCard, animationDelay: `${i * 0.04}s` }}
                >
                  <div className="px-5 py-4">
                    <div className="flex items-start justify-between gap-4">
                      {/* Left side */}
                      <div className="flex items-start gap-4 flex-1 min-w-0">
                        {/* Type icon */}
                        <div className={`shrink-0 w-12 h-12 rounded-xl flex items-center justify-center ${
                          isStroke ? 'bg-purple-500/10 border border-purple-500/20' : 'bg-red-500/10 border border-red-500/20'
                        }`}>
                          {isStroke ? (
                            <Brain className="w-6 h-6 text-purple-400" />
                          ) : (
                            <Heart className="w-6 h-6 text-red-400" />
                          )}
                        </div>

                        <div className="flex-1 min-w-0">
                          {/* Status & type badges */}
                          <div className="flex items-center gap-2 flex-wrap mb-2">
                            <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg border ${statusCfg.bg} ${statusCfg.color} ${statusCfg.border}`}>
                              {activation.status.replace(/_/g, ' ')}
                            </span>
                            <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${
                              isStroke ? 'bg-purple-500/10 text-purple-400' : 'bg-red-500/10 text-red-400'
                            }`}>
                              {isStroke ? 'STROKE' : 'MI / ACS'}
                            </span>
                            {/* Hemorrhagic flag for stroke */}
                            {isStroke && activation.isHemorrhagic === true && (
                              <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg bg-red-500/15 text-red-500 border border-red-500/20">
                                <AlertTriangle className="w-3 h-3 inline mr-1" />
                                Hemorrhagic
                              </span>
                            )}
                            {/* ST Elevation flag for MI */}
                            {!isStroke && activation.stElevation === true && (
                              <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg bg-red-500/15 text-red-500 border border-red-500/20">
                                <AlertTriangle className="w-3 h-3 inline mr-1" />
                                ST Elevation
                              </span>
                            )}
                          </div>

                          {/* Activated info */}
                          <div className="flex items-center gap-3 flex-wrap mb-2">
                            <p className={`text-xs ${text.body}`}>
                              Activated by <span className={`font-semibold ${text.heading}`}>{activation.activatedByName}</span>
                            </p>
                            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                              <Clock className="w-3 h-3" />
                              {format(new Date(activation.activatedAt), 'dd MMM yyyy HH:mm')}
                            </span>
                          </div>

                          {/* ── Stroke-specific details ── */}
                          {isStroke && (
                            <div className="flex items-center gap-3 flex-wrap">
                              {/* BE-FAST */}
                              {activation.beFastScore && (
                                <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg ${isDark ? 'bg-white/5' : 'bg-slate-50'}`}>
                                  <span className={`text-[10px] font-bold ${text.muted}`}>BE-FAST:</span>
                                  <span className={`text-[10px] font-bold ${text.heading}`}>{activation.beFastScore}</span>
                                </div>
                              )}
                              {/* NIHSS */}
                              {activation.nihssScore !== null && (
                                <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg ${isDark ? 'bg-white/5' : 'bg-slate-50'}`}>
                                  <span className={`text-[10px] font-bold ${text.muted}`}>NIHSS:</span>
                                  <span className={`text-[10px] font-bold ${
                                    activation.nihssScore >= 21 ? 'text-red-500'
                                      : activation.nihssScore >= 16 ? 'text-orange-500'
                                      : activation.nihssScore >= 5 ? 'text-amber-500'
                                      : 'text-emerald-500'
                                  }`}>
                                    {activation.nihssScore}
                                  </span>
                                </div>
                              )}
                              {/* CT status */}
                              <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg ${
                                activation.ctCompletedAt
                                  ? 'bg-emerald-500/10'
                                  : activation.ctOrderedAt
                                    ? 'bg-amber-500/10'
                                    : isDark ? 'bg-white/5' : 'bg-slate-50'
                              }`}>
                                <Scan className="w-3 h-3 opacity-60" />
                                <span className={`text-[10px] font-bold ${
                                  activation.ctCompletedAt ? 'text-emerald-400' : activation.ctOrderedAt ? 'text-amber-400' : text.muted
                                }`}>
                                  CT: {activation.ctCompletedAt ? 'Done' : activation.ctOrderedAt ? 'Ordered' : 'Pending'}
                                </span>
                              </div>
                              {/* CT result */}
                              {activation.ctResult && (
                                <span className={`text-[10px] ${text.body}`}>Result: {activation.ctResult}</span>
                              )}
                              {/* Door-to-CT recorded */}
                              {activation.doorToCtMinutes !== null && (
                                <div className={`flex items-center gap-1 px-2.5 py-1 rounded-lg ${
                                  activation.doorToCtMinutes <= 25 ? 'bg-emerald-500/10' : 'bg-red-500/10'
                                }`}>
                                  <span className={`text-[10px] font-bold ${activation.doorToCtMinutes <= 25 ? 'text-emerald-400' : 'text-red-400'}`}>
                                    Door-to-CT: {activation.doorToCtMinutes}min
                                  </span>
                                </div>
                              )}
                            </div>
                          )}

                          {/* ── MI/ACS-specific details ── */}
                          {!isStroke && (
                            <div className="flex items-center gap-3 flex-wrap">
                              {/* ECG status */}
                              <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg ${
                                activation.ecgCompletedAt
                                  ? 'bg-emerald-500/10'
                                  : activation.ecgOrderedAt
                                    ? 'bg-amber-500/10'
                                    : isDark ? 'bg-white/5' : 'bg-slate-50'
                              }`}>
                                <Activity className="w-3 h-3 opacity-60" />
                                <span className={`text-[10px] font-bold ${
                                  activation.ecgCompletedAt ? 'text-emerald-400' : activation.ecgOrderedAt ? 'text-amber-400' : text.muted
                                }`}>
                                  ECG: {activation.ecgCompletedAt ? 'Done' : activation.ecgOrderedAt ? 'Ordered' : 'Pending'}
                                </span>
                              </div>
                              {/* ECG result */}
                              {activation.ecgResult && (
                                <span className={`text-[10px] ${text.body}`}>ECG: {activation.ecgResult}</span>
                              )}
                              {/* Troponin */}
                              {activation.troponinResult !== null && (
                                <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg ${isDark ? 'bg-white/5' : 'bg-slate-50'}`}>
                                  <CircleDot className="w-3 h-3 opacity-60" />
                                  <span className={`text-[10px] font-bold ${text.heading}`}>Troponin: {activation.troponinResult}</span>
                                </div>
                              )}
                              {/* Aspirin */}
                              <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg ${
                                activation.aspirinGiven ? 'bg-emerald-500/10' : isDark ? 'bg-white/5' : 'bg-slate-50'
                              }`}>
                                <Pill className="w-3 h-3 opacity-60" />
                                <span className={`text-[10px] font-bold ${activation.aspirinGiven ? 'text-emerald-400' : text.muted}`}>
                                  Aspirin: {activation.aspirinGiven ? 'Given' : 'Pending'}
                                </span>
                              </div>
                              {/* Door-to-ECG recorded */}
                              {activation.doorToEcgMinutes !== null && (
                                <div className={`flex items-center gap-1 px-2.5 py-1 rounded-lg ${
                                  activation.doorToEcgMinutes <= 10 ? 'bg-emerald-500/10' : 'bg-red-500/10'
                                }`}>
                                  <span className={`text-[10px] font-bold ${activation.doorToEcgMinutes <= 10 ? 'text-emerald-400' : 'text-red-400'}`}>
                                    Door-to-ECG: {activation.doorToEcgMinutes}min
                                  </span>
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      </div>

                      {/* Right: Door-to timer */}
                      <div className="shrink-0 text-right">
                        {isStroke && !activation.ctCompletedAt && (
                          <div className="flex flex-col items-end gap-1">
                            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Door-to-CT</span>
                            <div className={`text-xl font-black tabular-nums ${timerColor(activation.activatedAt, 25)}`}>
                              {formatElapsed(activation.activatedAt)}
                            </div>
                            <span className={`text-[9px] ${text.muted}`}>Target: 25min</span>
                          </div>
                        )}
                        {!isStroke && !activation.ecgCompletedAt && (
                          <div className="flex flex-col items-end gap-1">
                            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Door-to-ECG</span>
                            <div className={`text-xl font-black tabular-nums ${timerColor(activation.activatedAt, 10)}`}>
                              {formatElapsed(activation.activatedAt)}
                            </div>
                            <span className={`text-[9px] ${text.muted}`}>Target: 10min</span>
                          </div>
                        )}
                        {activation.status === 'COMPLETED' && (
                          <div className="flex items-center gap-1.5">
                            <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                            <span className="text-[10px] font-bold text-emerald-400">Completed</span>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* ── Action Bar ── */}
                  {activation.status !== 'COMPLETED' && (
                    <div
                      className="px-5 py-3 border-t flex items-center gap-2 flex-wrap"
                      style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}
                    >
                      {/* Stroke: Record CT */}
                      {isStroke && !activation.ctCompletedAt && (
                        <button
                          onClick={() => { setCtDialog({ id: activation.id }); setCtResult(''); setCtHemorrhagic(false); }}
                          className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-400 hover:bg-purple-500/20 transition-colors"
                        >
                          <Scan className="w-3.5 h-3.5" />
                          Record CT Result
                        </button>
                      )}
                      {/* MI: Record ECG */}
                      {!isStroke && !activation.ecgCompletedAt && (
                        <button
                          onClick={() => { setEcgDialog({ id: activation.id }); setEcgResult(''); setEcgStElevation(false); }}
                          className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors"
                        >
                          <Activity className="w-3.5 h-3.5" />
                          Record ECG Result
                        </button>
                      )}
                      {/* Complete */}
                      <button
                        onClick={() => handleComplete(activation.id, isStroke ? 'STROKE_MANAGED' : 'ACS_MANAGED')}
                        disabled={actionLoading === activation.id}
                        className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20 transition-colors disabled:opacity-50"
                      >
                        {actionLoading === activation.id ? (
                          <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        ) : (
                          <FileCheck className="w-3.5 h-3.5" />
                        )}
                        Complete Fast-Track
                      </button>
                    </div>
                  )}

                  {/* ── Outcome ── */}
                  {activation.outcome && (
                    <div
                      className="px-5 py-2.5 border-t"
                      style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}
                    >
                      <p className={`text-[11px] ${text.muted}`}>
                        <ArrowRight className="w-3 h-3 inline mr-1" />
                        Outcome: <span className={text.heading}>{activation.outcome.replace(/_/g, ' ')}</span>
                        {activation.completedAt && (
                          <span className="ml-2">
                            at {format(new Date(activation.completedAt), 'dd MMM HH:mm')}
                          </span>
                        )}
                      </p>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* ── ECG Dialog ── */}
        {ecgDialog && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm animate-fade-in">
            <div className="w-full max-w-md mx-4 rounded-2xl overflow-hidden" style={glassCard}>
              <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
                <h2 className="text-sm font-bold text-white flex items-center gap-2">
                  <Activity className="w-4 h-4 text-red-400" />
                  Record ECG Result
                </h2>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className={`text-[11px] font-bold block mb-1.5 ${text.label}`}>ECG Result</label>
                  <input
                    type="text"
                    value={ecgResult}
                    onChange={(e) => setEcgResult(e.target.value)}
                    placeholder="e.g., Normal sinus rhythm, STEMI..."
                    className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none transition-all ${
                      isDark
                        ? 'bg-white/5 text-white border border-white/10 focus:border-cyan-500/50 placeholder:text-slate-500'
                        : 'bg-white text-slate-800 border border-slate-200 focus:border-cyan-500 placeholder:text-slate-400'
                    }`}
                  />
                </div>
                <label className="flex items-center gap-3 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={ecgStElevation}
                    onChange={(e) => setEcgStElevation(e.target.checked)}
                    className="w-4 h-4 rounded border-slate-300 text-red-500 focus:ring-red-500"
                  />
                  <span className={`text-xs font-bold ${text.heading}`}>ST Elevation Present</span>
                  <AlertTriangle className="w-3.5 h-3.5 text-red-400" />
                </label>
                <div className="flex items-center justify-end gap-2 pt-2">
                  <button
                    onClick={() => setEcgDialog(null)}
                    className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${
                      isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-100'
                    }`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleRecordEcg}
                    disabled={!ecgResult.trim() || actionLoading !== null}
                    className="px-5 py-2 text-[11px] font-bold rounded-xl bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors disabled:opacity-50"
                  >
                    {actionLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : 'Save ECG'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── CT Dialog ── */}
        {ctDialog && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm animate-fade-in">
            <div className="w-full max-w-md mx-4 rounded-2xl overflow-hidden" style={glassCard}>
              <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
                <h2 className="text-sm font-bold text-white flex items-center gap-2">
                  <Scan className="w-4 h-4 text-purple-400" />
                  Record CT Result
                </h2>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className={`text-[11px] font-bold block mb-1.5 ${text.label}`}>CT Result</label>
                  <input
                    type="text"
                    value={ctResult}
                    onChange={(e) => setCtResult(e.target.value)}
                    placeholder="e.g., Large vessel occlusion, No acute findings..."
                    className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none transition-all ${
                      isDark
                        ? 'bg-white/5 text-white border border-white/10 focus:border-cyan-500/50 placeholder:text-slate-500'
                        : 'bg-white text-slate-800 border border-slate-200 focus:border-cyan-500 placeholder:text-slate-400'
                    }`}
                  />
                </div>
                <label className="flex items-center gap-3 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={ctHemorrhagic}
                    onChange={(e) => setCtHemorrhagic(e.target.checked)}
                    className="w-4 h-4 rounded border-slate-300 text-red-500 focus:ring-red-500"
                  />
                  <span className={`text-xs font-bold ${text.heading}`}>Hemorrhagic Stroke</span>
                  <AlertTriangle className="w-3.5 h-3.5 text-red-400" />
                </label>
                <div className="flex items-center justify-end gap-2 pt-2">
                  <button
                    onClick={() => setCtDialog(null)}
                    className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${
                      isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-100'
                    }`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleRecordCt}
                    disabled={!ctResult.trim() || actionLoading !== null}
                    className="px-5 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-400 hover:bg-purple-500/20 transition-colors disabled:opacity-50"
                  >
                    {actionLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : 'Save CT'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

