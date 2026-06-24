/* ═══════════════════════════════════════════════════════════════
   Clinical Pathway Panel — per-visit chart entry point

   The natural place a clinician sees engine-recommended pathways for THIS
   patient, activates a protocol, and works its timed step checklist (with live
   overdue indicators) — complete / skip / complete-pathway / abandon. Before
   this panel the pathway module had no controller and no chart entry point at
   all (every call 404'd; activation required hand-typing a Visit UUID elsewhere).
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Route, AlertTriangle, CheckCircle2, Loader2, Clock, Play, Timer,
  SkipForward, Flag, XCircle, RefreshCw, ListChecks,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import {
  pathwayApi, type PathwayActivation, type PathwayProgress, type PathwayRecommendation,
} from '@/api/pathway';
import { subscribeToPathway } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { ApiError } from '@/api/client';
import { format } from 'date-fns';

const URGENCY_STYLE: Record<string, { color: string; bg: string }> = {
  HIGH: { color: 'text-red-500', bg: 'bg-red-500/10' },
  MEDIUM: { color: 'text-amber-500', bg: 'bg-amber-500/10' },
  LOW: { color: 'text-cyan-500', bg: 'bg-cyan-500/10' },
};

/** Live per-step timer from activation start + the step's protocol timeframe.
 *  Only MANDATORY steps flip to "overdue" (red) once past their timeframe — this mirrors
 *  the backend OVERDUE rule (mandatory + past 1x timeframe) and the compliance monitor, so
 *  an optional step past its target never raises a false red alarm. Uses floored elapsed
 *  minutes with a strict > to match the backend boundary EXACTLY (Duration.toMinutes() floors;
 *  status flips at minutesSinceActivation > timeframeMinutes) — label and backend status flip together. */
function stepTimer(
  activatedAt: string, timeframeMinutes: number | null, isMandatory: boolean,
): { text: string; overdue: boolean } | null {
  if (timeframeMinutes == null) return null;
  const elapsedMin = Math.floor((Date.now() - new Date(activatedAt).getTime()) / 60000);
  if (elapsedMin > timeframeMinutes) {
    return isMandatory
      ? { text: `overdue ${elapsedMin - timeframeMinutes}m`, overdue: true }
      : { text: 'target passed', overdue: false };
  }
  return { text: `due in ${timeframeMinutes - elapsedMin}m`, overdue: false };
}

interface PathwayPanelProps {
  visitId: string;
  onChanged?: () => void;
}

export function PathwayPanel({ visitId, onChanged }: PathwayPanelProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';
  const wsGen = useWebSocketGeneration();

  const [recs, setRecs] = useState<PathwayRecommendation[]>([]);
  const [activations, setActivations] = useState<PathwayActivation[]>([]);
  const [progress, setProgress] = useState<Record<string, PathwayProgress>>({});
  // Activation ids whose progress() fetch FAILED — a missing checklist is treated as UNKNOWN
  // (block completion, show retry), never silently as "all steps satisfied" (fail-safe).
  const [progressErrors, setProgressErrors] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, setTick] = useState(0);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [skipFor, setSkipFor] = useState<string | null>(null); // `${activationId}:${stepId}`
  const [skipReason, setSkipReason] = useState('');
  const [abandonFor, setAbandonFor] = useState<string | null>(null);
  const [abandonReason, setAbandonReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [recList, active] = await Promise.all([
        pathwayApi.recommend(visitId).catch(() => [] as PathwayRecommendation[]),
        pathwayApi.getActive(visitId),
      ]);
      setRecs(Array.isArray(recList) ? recList : []);
      const acts = Array.isArray(active) ? active : [];
      setActivations(acts);
      const progressEntries = await Promise.all(
        acts.map(async (a) => {
          try { return [a.id, await pathwayApi.progress(a.id), false] as const; }
          catch { return [a.id, null, true] as const; }
        }),
      );
      const map: Record<string, PathwayProgress> = {};
      const errs = new Set<string>();
      for (const [id, p, failed] of progressEntries) {
        if (p) map[id] = p;
        if (failed) errs.add(id);
      }
      setProgress(map);
      setProgressErrors(errs);
      setError(null);
    } catch (err) {
      console.error('Failed to load pathways:', err);
      setActivations([]);
      setProgressErrors(new Set());
      setError(err instanceof ApiError ? err.message : 'Failed to load clinical pathways');
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    tickRef.current = setInterval(() => setTick((t) => t + 1), 30000);
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, []);
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToPathway(hospitalId, (event: { visitId?: string }) => {
      if (event?.visitId === visitId) load();
    });
    return () => unsub();
  }, [hospitalId, visitId, load, wsGen]);

  const fail = (err: unknown, fallback: string) =>
    setError(err instanceof ApiError ? err.message : err instanceof Error ? err.message : fallback);

  const run = async (fn: () => Promise<unknown>, fallback: string, after?: () => void) => {
    setBusy(true); setError(null);
    try { await fn(); after?.(); await load(); onChanged?.(); }
    catch (err) { fail(err, fallback); }
    finally { setBusy(false); }
  };

  const activate = (pathwayId: string) =>
    run(() => pathwayApi.activate({ visitId, pathwayId }), 'Failed to activate pathway');
  const completeStep = (activationId: string, stepId: string) =>
    run(() => pathwayApi.completeStep(activationId, stepId), 'Failed to complete step');
  const submitSkip = (activationId: string, stepId: string) => {
    if (!skipReason.trim()) return;
    run(() => pathwayApi.skipStep(activationId, stepId, { reason: skipReason.trim() }),
      'Failed to skip step', () => { setSkipFor(null); setSkipReason(''); });
  };
  const completePathway = (activationId: string) =>
    run(() => pathwayApi.completePathway(activationId), 'Failed to complete pathway');
  const submitAbandon = (activationId: string) => {
    if (!abandonReason.trim()) return;
    run(() => pathwayApi.abandonPathway(activationId, abandonReason.trim()),
      'Failed to abandon pathway', () => { setAbandonFor(null); setAbandonReason(''); });
  };

  const activePathwayIds = new Set(activations.map((a) => a.pathwayId));
  const freshRecs = recs.filter((r) => !activePathwayIds.has(r.pathwayId));

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/15 flex items-center justify-center shrink-0">
              <Route className="w-5 h-5 text-cyan-500" />
            </div>
            <div>
              <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Clinical Pathways</h3>
              <p className={`text-xs ${text.muted}`}>Protocol checklists for this patient — recommended, activatable, and time-tracked.</p>
            </div>
          </div>
          <button onClick={load} disabled={loading} title="Refresh"
            className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${isDark ? 'bg-white/5 hover:bg-white/10' : 'bg-slate-100 hover:bg-slate-200'}`}>
            <RefreshCw className={`w-4 h-4 ${text.muted} ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
        {error && (
          <div className="mt-3 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[11px] font-semibold text-red-500">{error}</p>
          </div>
        )}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-cyan-500" /></div>
      ) : (
        <>
          {/* Recommendations (advisory) */}
          {freshRecs.length > 0 && (
            <div className="rounded-2xl p-5" style={glassCard}>
              <h4 className={`text-xs font-bold uppercase tracking-wider mb-3 ${text.muted}`}>Recommended for this patient</h4>
              <div className="space-y-2">
                {freshRecs.map((r) => {
                  const u = URGENCY_STYLE[r.urgency] || URGENCY_STYLE.MEDIUM;
                  return (
                    <div key={r.pathwayId} className="flex items-start gap-3 p-3 rounded-xl" style={glassInner}>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className={`text-[12px] font-bold ${text.heading}`}>{r.pathwayName}</span>
                          <span className={`text-[9px] font-bold uppercase px-2 py-0.5 rounded ${u.bg} ${u.color}`}>{r.urgency}</span>
                          <span className={`text-[10px] ${text.muted}`}>{Math.round((r.confidence ?? 0) * 100)}%</span>
                        </div>
                        <p className={`text-[11px] mt-0.5 ${text.body}`}>{r.reason}</p>
                      </div>
                      <button onClick={() => activate(r.pathwayId)} disabled={busy}
                        className="inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors disabled:opacity-50 shrink-0">
                        <Play className="w-3.5 h-3.5" />Activate
                      </button>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {activations.length === 0 && freshRecs.length === 0 && !error && (
            <div className="rounded-2xl p-8 text-center" style={glassCard}>
              <CheckCircle2 className={`w-10 h-10 mx-auto mb-3 ${text.muted}`} />
              <p className={`text-sm font-bold ${text.heading}`}>No pathways recommended or active</p>
              <p className={`text-xs mt-1 ${text.muted}`}>The recommendation engine found no protocol triggers for this patient's triage/complaint.</p>
            </div>
          )}

          {/* Active activations */}
          {activations.map((a) => {
            const p = progress[a.id];
            const progressFailed = !p && progressErrors.has(a.id);
            const isActive = a.status === 'ACTIVE';
            const pendingMandatory = p ? p.steps.filter((s) => s.isMandatory && (s.status === 'PENDING' || s.status === 'OVERDUE')).length : 0;
            const overdueCount = p ? p.steps.filter((s) => s.status === 'OVERDUE').length : 0;
            return (
              <div key={a.id} className="rounded-2xl overflow-hidden" style={glassCard}>
                <div className={`px-5 py-3 flex items-center gap-3 flex-wrap ${overdueCount > 0 ? 'bg-red-500/10' : 'bg-cyan-500/5'}`}>
                  <Route className={`w-5 h-5 ${overdueCount > 0 ? 'text-red-500' : 'text-cyan-500'}`} />
                  <span className={`text-sm font-black ${text.heading}`}>{a.pathwayName}</span>
                  <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg ${a.status === 'COMPLETED' ? 'bg-emerald-500/15 text-emerald-500' : a.status === 'ABANDONED' ? 'bg-red-500/15 text-red-500' : 'bg-cyan-500/15 text-cyan-500'}`}>{a.status}</span>
                  {overdueCount > 0 && (
                    <span className="ml-auto text-[10px] font-bold px-2 py-0.5 rounded-lg bg-red-600/20 text-red-600 inline-flex items-center gap-1 animate-pulse">
                      <Timer className="w-3 h-3" />{overdueCount} step(s) overdue
                    </span>
                  )}
                </div>
                <div className="px-5 py-4">
                  <div className="flex items-center gap-3 flex-wrap text-[10px] mb-3">
                    <span className={text.muted}>Activated {format(new Date(a.activatedAt), 'dd MMM HH:mm')}{a.activatedByName ? ` by ${a.activatedByName}` : ''}</span>
                    {p && <span className={text.muted}>· {p.completedSteps + p.skippedSteps}/{p.totalSteps} steps ({Math.round(p.completionPercentage)}%)</span>}
                  </div>

                  {/* Step checklist failed to load — surface it with a retry, never a silent blank */}
                  {progressFailed && (
                    <div className="flex items-center justify-between gap-3 mb-3 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
                      <p className="text-[11px] font-semibold text-red-500 flex items-center gap-1.5">
                        <AlertTriangle className="w-3.5 h-3.5" /> Couldn't load this pathway's step checklist.
                      </p>
                      <button onClick={() => load()} disabled={busy}
                        className="inline-flex items-center gap-1 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 disabled:opacity-50">
                        <RefreshCw className="w-3 h-3" /> Retry
                      </button>
                    </div>
                  )}

                  {/* Step checklist */}
                  {p && (
                    <div className="space-y-1.5 mb-3">
                      {p.steps.map((s) => {
                        const done = s.status === 'COMPLETED';
                        const skipped = s.status === 'SKIPPED';
                        const timer = (!done && !skipped) ? stepTimer(a.activatedAt, s.timeframeMinutes, s.isMandatory) : null;
                        return (
                          <div key={s.stepId} className="flex items-start gap-2 p-2.5 rounded-xl" style={glassInner}>
                            <div className="shrink-0 pt-0.5">
                              {done ? <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                                : skipped ? <SkipForward className="w-4 h-4 text-amber-500" />
                                : <ListChecks className={`w-4 h-4 ${s.status === 'OVERDUE' ? 'text-red-500' : text.muted}`} />}
                            </div>
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 flex-wrap">
                                <span className={`text-[11px] font-bold ${done || skipped ? 'line-through opacity-70' : ''} ${text.heading}`}>{s.stepOrder}. {s.stepTitle}</span>
                                {s.isMandatory && <span className="text-[8px] font-bold uppercase px-1.5 py-0.5 rounded bg-red-500/10 text-red-500">Required</span>}
                                {timer && <span className={`text-[9px] font-bold inline-flex items-center gap-0.5 ${timer.overdue ? 'text-red-600' : 'text-amber-500'}`}><Clock className="w-2.5 h-2.5" />{timer.text}</span>}
                              </div>
                              {done && s.completedByName && <p className={`text-[10px] ${text.muted}`}>by {s.completedByName}{s.timeToCompleteMinutes != null ? ` (${s.timeToCompleteMinutes}m)` : ''}</p>}
                              {skipped && s.skipReason && <p className="text-[10px] text-amber-500">Skipped: {s.skipReason}</p>}
                            </div>
                            {isActive && !done && !skipped && (
                              <div className="shrink-0 flex gap-1.5">
                                <button onClick={() => completeStep(a.id, s.stepId)} disabled={busy}
                                  className="px-2.5 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 disabled:opacity-50">Done</button>
                                <button onClick={() => { setSkipFor(`${a.id}:${s.stepId}`); setSkipReason(''); }}
                                  className="px-2.5 py-1.5 text-[10px] font-bold rounded-lg bg-amber-500/10 text-amber-500 hover:bg-amber-500/20">Skip</button>
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {/* Inline skip reason */}
                  {skipFor && skipFor.startsWith(`${a.id}:`) && (
                    <div className="flex items-center gap-2 mb-3 flex-wrap">
                      <input value={skipReason} onChange={(e) => setSkipReason(e.target.value)} placeholder="Skip reason (required)"
                        className={`flex-1 min-w-[12rem] px-3 py-2 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner} />
                      <button onClick={() => submitSkip(a.id, skipFor.split(':')[1])} disabled={!skipReason.trim() || busy}
                        className="px-3 py-2 text-[11px] font-bold rounded-xl bg-amber-500/10 text-amber-500 hover:bg-amber-500/20 disabled:opacity-50">Skip step</button>
                      <button onClick={() => { setSkipFor(null); setSkipReason(''); }}
                        className={`px-3 py-2 text-[11px] font-bold rounded-xl hover:bg-white/5 ${text.muted}`}>Cancel</button>
                    </div>
                  )}

                  {/* Lifecycle actions */}
                  {isActive && (
                    <div className="flex items-center gap-2 flex-wrap">
                      <button onClick={() => completePathway(a.id)} disabled={busy || !p || pendingMandatory > 0}
                        title={!p ? 'Step checklist failed to load — refresh before completing'
                          : pendingMandatory > 0 ? `${pendingMandatory} mandatory step(s) outstanding` : 'Complete pathway'}
                        className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed">
                        <Flag className="w-3.5 h-3.5" />Complete{p && pendingMandatory > 0 ? ` (${pendingMandatory} left)` : ''}
                      </button>
                      {abandonFor !== a.id ? (
                        <button onClick={() => { setAbandonFor(a.id); setAbandonReason(''); }}
                          className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-red-500/10 text-red-500 hover:bg-red-500/20 transition-colors">
                          <XCircle className="w-3.5 h-3.5" />Abandon
                        </button>
                      ) : (
                        <div className="flex items-center gap-2 flex-wrap">
                          <input value={abandonReason} onChange={(e) => setAbandonReason(e.target.value)} placeholder="Abandon reason (required)"
                            className={`w-56 px-3 py-2 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner} />
                          <button onClick={() => submitAbandon(a.id)} disabled={!abandonReason.trim() || busy}
                            className="px-3 py-2 text-[11px] font-bold rounded-xl bg-red-500/10 text-red-500 hover:bg-red-500/20 disabled:opacity-50">Confirm</button>
                          <button onClick={() => { setAbandonFor(null); setAbandonReason(''); }}
                            className={`px-3 py-2 text-[11px] font-bold rounded-xl hover:bg-white/5 ${text.muted}`}>Cancel</button>
                        </div>
                      )}
                    </div>
                  )}
                  {a.status === 'ABANDONED' && a.deviationReason && (
                    <p className={`text-[10px] ${text.muted}`}>Abandoned: {a.deviationReason}</p>
                  )}
                </div>
              </div>
            );
          })}
        </>
      )}
    </div>
  );
}
