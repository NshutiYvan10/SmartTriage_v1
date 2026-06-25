/* ═══════════════════════════════════════════════════════════════
   Clinical Pathways — Module 15: Evidence-based care protocols
   Library browsing, activation, step-by-step completion & tracking
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  Route, CheckCircle, Clock, AlertTriangle, Search,
  Loader2, RefreshCw, ChevronRight, ChevronDown,
  X, MessageSquare, BookOpen, ListChecks, Play,
  SkipForward, Flag, XCircle, Stethoscope, Zap, Baby,
  Bug, Wind, Brain, Droplets, Heart, Siren,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { pathwayApi } from '@/api/pathway';
import type {
  ClinicalPathway, PathwayStep, PathwayActivation, PathwayProgress, PathwayRecommendation,
} from '@/api/pathway';
import { ApiError } from '@/api/client';
import { format, formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

// Live per-step countdown from activation start + the step's protocol timeframe. Only
// MANDATORY steps flip "overdue" (red) once past their timeframe — mirroring the backend
// OVERDUE rule (mandatory + past 1x timeframe) so optional steps never raise a false alarm.
// Uses floored elapsed minutes with a strict > to match the backend boundary EXACTLY
// (Duration.toMinutes() floors; status flips at minutesSinceActivation > timeframeMinutes),
// so the red label and the backend OVERDUE status flip at the same instant.
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

// ── Category badge config — keys MUST match the backend PathwayCategory enum
//    {MALARIA, TRAUMA, RESPIRATORY, CARDIAC, NEUROLOGICAL, OBSTETRIC, PEDIATRIC,
//     INFECTIOUS_DISEASE, SURGICAL, POISONING, BURNS, SNAKEBITE, OTHER}. ──
const CATEGORY_FALLBACK = {
  bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)',
  text: 'text-slate-600', icon: Stethoscope,
};
const CATEGORY_STYLE: Record<string, { bg: string; border: string; text: string; icon: typeof Heart }> = {
  MALARIA: { bg: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)', text: 'text-red-600', icon: Bug },
  TRAUMA: { bg: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)', text: 'text-amber-600', icon: Siren },
  RESPIRATORY: { bg: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)', text: 'text-blue-600', icon: Wind },
  CARDIAC: { bg: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)', text: 'text-red-600', icon: Heart },
  NEUROLOGICAL: { bg: 'rgba(168,85,247,0.08)', border: '1px solid rgba(168,85,247,0.2)', text: 'text-purple-600', icon: Brain },
  OBSTETRIC: { bg: 'rgba(236,72,153,0.08)', border: '1px solid rgba(236,72,153,0.2)', text: 'text-pink-600', icon: Baby },
  PEDIATRIC: { bg: 'rgba(236,72,153,0.08)', border: '1px solid rgba(236,72,153,0.2)', text: 'text-pink-600', icon: Baby },
  INFECTIOUS_DISEASE: { bg: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)', text: 'text-red-600', icon: Bug },
  SURGICAL: { bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)', text: 'text-slate-600', icon: Stethoscope },
  POISONING: { bg: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.2)', text: 'text-emerald-600', icon: Zap },
  BURNS: { bg: 'rgba(249,115,22,0.08)', border: '1px solid rgba(249,115,22,0.2)', text: 'text-orange-600', icon: Zap },
  SNAKEBITE: { bg: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)', text: 'text-emerald-600', icon: Bug },
  OTHER: { bg: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)', text: 'text-cyan-600', icon: Droplets },
};

function getCategoryStyle(category: string) {
  return CATEGORY_STYLE[category] || CATEGORY_FALLBACK;
}

type MainView = 'library' | 'active';

export function ClinicalPathwaysView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  // ── Data state ──
  const [pathways, setPathways] = useState<ClinicalPathway[]>([]);
  const [activations, setActivations] = useState<PathwayActivation[]>([]);
  // Per-activation progress (full step list + live status) — the activation header
  // endpoint carries NO steps, so the checklist is fetched here keyed by activation id.
  const [progressMap, setProgressMap] = useState<Record<string, PathwayProgress>>({});
  // Activation ids whose progress() fetch FAILED — kept distinct from "loaded with 0 steps"
  // so a failed-to-load checklist is treated as UNKNOWN (block completion, show retry),
  // never silently as "all steps satisfied" (fail-safe).
  const [progressErrors, setProgressErrors] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [, setTick] = useState(0); // drives live step countdowns between fetches

  // ── View state ──
  const [mainView, setMainView] = useState<MainView>('library');
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedPathwayId, setExpandedPathwayId] = useState<string | null>(null);
  const [pathwaySteps, setPathwaySteps] = useState<Record<string, PathwayStep[]>>({});
  const [expandedActivationId, setExpandedActivationId] = useState<string | null>(null);

  // ── Activation dialog ──
  const [activateDialogOpen, setActivateDialogOpen] = useState(false);
  const [activatePathwayId, setActivatePathwayId] = useState<string | null>(null);
  const [activatePathwayName, setActivatePathwayName] = useState('');
  const [activateVisitId, setActivateVisitId] = useState('');
  const [activateSubmitting, setActivateSubmitting] = useState(false);

  // ── Skip dialog ──
  const [skipDialogOpen, setSkipDialogOpen] = useState(false);
  const [skipActivationId, setSkipActivationId] = useState<string | null>(null);
  const [skipStepId, setSkipStepId] = useState<string | null>(null);
  const [skipStepTitle, setSkipStepTitle] = useState('');
  const [skipReason, setSkipReason] = useState('');
  const [skipSubmitting, setSkipSubmitting] = useState(false);

  // ── Abandon dialog ──
  const [abandonDialogOpen, setAbandonDialogOpen] = useState(false);
  const [abandonActivationId, setAbandonActivationId] = useState<string | null>(null);
  const [abandonReason, setAbandonReason] = useState('');
  const [abandonSubmitting, setAbandonSubmitting] = useState(false);

  // ── Active visit search for active pathways ──
  const [activeVisitIdInput, setActiveVisitIdInput] = useState('');
  const [activeVisitSearched, setActiveVisitSearched] = useState(false);

  const errMsg = (err: unknown, fallback: string) =>
    err instanceof ApiError ? err.message : err instanceof Error ? err.message : fallback;

  // ── Data loading ──
  const loadLibrary = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await pathwayApi.getAll();
      setPathways(data || []);
    } catch (err) {
      console.error('[ClinicalPathwaysView] Failed to load pathways:', err);
      setPathways([]);
      setError(errMsg(err, 'Failed to load the pathway library.'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadActivePathways = useCallback(async (visitId: string) => {
    if (!visitId.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const data = await pathwayApi.getActive(visitId.trim());
      const acts = data || [];
      setActivations(acts);
      // Activation headers carry no steps — fetch each activation's live progress checklist.
      // A per-activation failure is recorded (not swallowed) so the UI can block completion
      // and offer a retry rather than presenting an unknown checklist as complete-able.
      const entries = await Promise.all(
        acts.map(async (a) => {
          try { return [a.id, await pathwayApi.progress(a.id), false] as const; }
          catch { return [a.id, null, true] as const; }
        }),
      );
      const map: Record<string, PathwayProgress> = {};
      const errs = new Set<string>();
      for (const [id, p, failed] of entries) {
        if (p) map[id] = p;
        if (failed) errs.add(id);
      }
      setProgressMap(map);
      setProgressErrors(errs);
      setActiveVisitSearched(true);
    } catch (err) {
      console.error('[ClinicalPathwaysView] Failed to load active pathways:', err);
      setActivations([]);
      setProgressMap({});
      setProgressErrors(new Set());
      setActiveVisitSearched(true);
      setError(errMsg(err, 'Failed to load active pathways for this visit.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (mainView === 'library') loadLibrary();
  }, [mainView, loadLibrary]);

  // Live tick so per-step countdowns advance between server fetches.
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 30000);
    return () => clearInterval(t);
  }, []);

  // ── Load steps for a pathway ──
  const togglePathwaySteps = useCallback(async (pathwayId: string) => {
    if (expandedPathwayId === pathwayId) {
      setExpandedPathwayId(null);
      return;
    }
    setExpandedPathwayId(pathwayId);
    if (!pathwaySteps[pathwayId]) {
      try {
        const steps = await pathwayApi.getSteps(pathwayId);
        setPathwaySteps((prev) => ({ ...prev, [pathwayId]: steps || [] }));
      } catch (err) {
        console.error('[ClinicalPathwaysView] Failed to load steps:', err);
      }
    }
  }, [expandedPathwayId, pathwaySteps]);

  // ── Refresh one activation's step checklist (after a step complete/skip) ──
  const refreshProgress = useCallback(async (activationId: string) => {
    try {
      const updated = await pathwayApi.progress(activationId);
      setProgressMap((prev) => ({ ...prev, [activationId]: updated }));
      setProgressErrors((prev) => {
        if (!prev.has(activationId)) return prev;
        const next = new Set(prev); next.delete(activationId); return next;
      });
    } catch (err) {
      console.error(err);
      setProgressErrors((prev) => new Set(prev).add(activationId));
      setError(errMsg(err, 'Failed to refresh pathway progress.'));
    }
  }, []);

  // ── Filtered library ──
  const filteredPathways = pathways.filter((p) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return (
      p.pathwayName.toLowerCase().includes(q) ||
      p.category?.toLowerCase().includes(q) ||
      p.description?.toLowerCase().includes(q) ||
      p.pathwayCode?.toLowerCase().includes(q)
    );
  });

  // ── Actions ──
  const openActivateDialog = (pathwayId: string, pathwayName: string) => {
    setActivatePathwayId(pathwayId);
    setActivatePathwayName(pathwayName);
    setActivateVisitId('');
    setActivateDialogOpen(true);
  };

  const submitActivate = async () => {
    if (!activatePathwayId || !activateVisitId.trim()) return;
    setActivateSubmitting(true);
    setError(null);
    try {
      await pathwayApi.activate({ visitId: activateVisitId.trim(), pathwayId: activatePathwayId });
      setActivateDialogOpen(false);
      // Switch to active view and search for this visit
      setMainView('active');
      setActiveVisitIdInput(activateVisitId.trim());
      loadActivePathways(activateVisitId.trim());
    } catch (err) { console.error(err); setError(errMsg(err, 'Failed to activate pathway.')); }
    finally { setActivateSubmitting(false); }
  };

  const handleCompleteStep = async (activationId: string, stepId: string) => {
    setActionLoading(`${activationId}-${stepId}`);
    setError(null);
    try {
      await pathwayApi.completeStep(activationId, stepId);
      await refreshProgress(activationId);
    } catch (err) { console.error(err); setError(errMsg(err, 'Failed to complete step.')); }
    finally { setActionLoading(null); }
  };

  const openSkipDialog = (activationId: string, stepId: string, stepTitle: string) => {
    setSkipActivationId(activationId);
    setSkipStepId(stepId);
    setSkipStepTitle(stepTitle);
    setSkipReason('');
    setSkipDialogOpen(true);
  };

  const submitSkip = async () => {
    if (!skipActivationId || !skipStepId || !skipReason.trim()) return;
    setSkipSubmitting(true);
    setError(null);
    try {
      await pathwayApi.skipStep(skipActivationId, skipStepId, { reason: skipReason });
      setSkipDialogOpen(false);
      await refreshProgress(skipActivationId);
    } catch (err) { console.error(err); setError(errMsg(err, 'Failed to skip step.')); }
    finally { setSkipSubmitting(false); }
  };

  const handleCompletePathway = async (activationId: string) => {
    setActionLoading(activationId);
    setError(null);
    try {
      await pathwayApi.completePathway(activationId);
      if (activeVisitIdInput.trim()) loadActivePathways(activeVisitIdInput.trim());
    } catch (err) { console.error(err); setError(errMsg(err, 'Failed to complete pathway.')); }
    finally { setActionLoading(null); }
  };

  const openAbandonDialog = (activationId: string) => {
    setAbandonActivationId(activationId);
    setAbandonReason('');
    setAbandonDialogOpen(true);
  };

  const submitAbandon = async () => {
    if (!abandonActivationId || !abandonReason.trim()) return;
    setAbandonSubmitting(true);
    setError(null);
    try {
      await pathwayApi.abandonPathway(abandonActivationId, abandonReason);
      setAbandonDialogOpen(false);
      if (activeVisitIdInput.trim()) loadActivePathways(activeVisitIdInput.trim());
    } catch (err) { console.error(err); setError(errMsg(err, 'Failed to abandon pathway.')); }
    finally { setAbandonSubmitting(false); }
  };

  // ── Derive a step-count summary from an activation's live progress (no phantom steps) ──
  const summarize = (activationId: string) => {
    const p = progressMap[activationId];
    if (!p) return { total: 0, completed: 0, percent: 0, pendingMandatory: 0, overdue: 0 };
    const completed = p.completedSteps + p.skippedSteps;
    const pendingMandatory = p.steps.filter(
      (s) => s.isMandatory && (s.status === 'PENDING' || s.status === 'OVERDUE')).length;
    const overdue = p.steps.filter((s) => s.status === 'OVERDUE').length;
    return { total: p.totalSteps, completed, percent: Math.round(p.completionPercentage), pendingMandatory, overdue };
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="flex items-center justify-between w-full">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <Route className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white">Clinical Pathways</h1>
                  <p className="text-sm text-white/50">Evidence-based care protocols & step-by-step guidance</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <BookOpen className="w-3.5 h-3.5 text-white/80" />
                  <span className="text-xs font-semibold text-white/90">{pathways.length} Protocols</span>
                </div>
                <button
                  onClick={() => mainView === 'library' ? loadLibrary() : (activeVisitIdInput.trim() && loadActivePathways(activeVisitIdInput.trim()))}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── View Toggle & Search ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3">
            <div className="flex items-center gap-2">
              {([
                { key: 'library' as MainView, label: 'Pathway Library', icon: BookOpen },
                { key: 'active' as MainView, label: 'Active Pathways', icon: ListChecks },
              ]).map((tab) => {
                const Icon = tab.icon;
                return (
                  <button
                    key={tab.key}
                    onClick={() => setMainView(tab.key)}
                    className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-[11px] font-bold rounded-xl transition-all border ${
                      mainView === tab.key
                        ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md border-transparent'
                        : `${text.body} hover:bg-white/5 border-transparent`
                    }`}
                  >
                    <Icon className="w-3.5 h-3.5" />
                    {tab.label}
                  </button>
                );
              })}
            </div>
            {mainView === 'library' && (
              <div className="relative flex-1">
                <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Search pathways by name, category, or code..."
                  className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all placeholder-slate-400 ${text.body}`}
                  style={glassInner}
                />
              </div>
            )}
            {mainView === 'active' && (
              <div className="flex-1 flex gap-2">
                <div className="relative flex-1">
                  <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="text"
                    value={activeVisitIdInput}
                    onChange={(e) => setActiveVisitIdInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && loadActivePathways(activeVisitIdInput)}
                    placeholder="Enter Visit ID to view active pathways..."
                    className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all placeholder-slate-400 ${text.body}`}
                    style={glassInner}
                  />
                </div>
                <button
                  onClick={() => loadActivePathways(activeVisitIdInput)}
                  className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:shadow-lg rounded-xl transition-all shadow-md"
                >
                  <Search className="w-3.5 h-3.5" /> Search
                </button>
              </div>
            )}
          </div>
        </div>

        {/* ── Error banner (no longer swallowed) ── */}
        {error && (
          <div className="rounded-2xl px-4 py-3 flex items-start gap-2.5 animate-fade-up bg-red-500/10 border border-red-500/20">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[12px] font-semibold text-red-500">{error}</p>
          </div>
        )}

        {/* ═══════════════════════════════════════════════════════════
           LIBRARY VIEW
           ═══════════════════════════════════════════════════════════ */}
        {mainView === 'library' && (
          <>
            {loading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
              </div>
            ) : filteredPathways.length === 0 ? (
              <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.1)' }}>
                  <BookOpen className="w-8 h-8 text-slate-400" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>No pathways found</p>
                <p className={`text-xs font-medium mt-1 ${text.muted}`}>
                  {searchQuery ? 'Try adjusting your search terms' : 'No clinical pathways have been configured'}
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {filteredPathways.map((pathway, idx) => {
                  const catStyle = getCategoryStyle(pathway.category);
                  const CatIcon = catStyle.icon;
                  const isExpanded = expandedPathwayId === pathway.id;
                  const steps = pathwaySteps[pathway.id] || [];

                  return (
                    <div
                      key={pathway.id}
                      className="rounded-2xl overflow-hidden transition-all animate-fade-up hover:-translate-y-0.5"
                      style={{
                        ...glassCard,
                        animationDelay: `${0.05 + idx * 0.03}s`,
                      } as React.CSSProperties}
                    >
                      {/* Pathway header */}
                      <div className="p-5">
                        <div className="flex items-start gap-4">
                          <div
                            className="w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0"
                            style={{ backgroundColor: catStyle.bg }}
                          >
                            <CatIcon className={`w-5 h-5 ${catStyle.text}`} />
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2.5 mb-1 flex-wrap">
                              <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>
                                {pathway.pathwayName}
                              </h3>
                              <span
                                className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${catStyle.text}`}
                                style={{ background: catStyle.bg, border: catStyle.border }}
                              >
                                {pathway.category?.replace(/_/g, ' ')}
                              </span>
                            </div>
                            {pathway.description && (
                              <p className={`text-[12px] font-medium leading-relaxed mb-2 ${text.body}`}>
                                {pathway.description}
                              </p>
                            )}
                            <div className="flex items-center gap-3 flex-wrap">
                              <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                                {pathway.pathwayCode}
                              </span>
                              {pathway.targetPopulation && (
                                <span className={`text-[10px] font-medium ${text.accent}`}>
                                  {pathway.targetPopulation}
                                </span>
                              )}
                              {pathway.sourceGuideline && (
                                <span className={`text-[10px] font-medium flex items-center gap-1 ${text.muted}`}>
                                  <BookOpen className="w-3 h-3" /> {pathway.sourceGuideline}
                                </span>
                              )}
                              {pathway.protocolVersion && (
                                <span className={`text-[10px] font-medium ${text.muted}`}>
                                  v{pathway.protocolVersion}
                                </span>
                              )}
                            </div>
                          </div>

                          {/* Actions */}
                          <div className="flex-shrink-0 flex flex-col gap-2 pt-1">
                            <button
                              onClick={() => openActivateDialog(pathway.id, pathway.pathwayName)}
                              className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all shadow-md shadow-slate-800/15"
                            >
                              <Play className="w-3.5 h-3.5" /> Activate
                            </button>
                            <button
                              onClick={() => togglePathwaySteps(pathway.id)}
                              className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                                isDark ? 'text-slate-400 bg-white/5 hover:bg-white/10 border border-white/10' : 'text-slate-500 bg-white/60 hover:bg-white/80 border border-slate-200/60'
                              }`}
                            >
                              {isExpanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                              {isExpanded ? 'Hide Steps' : 'View Steps'}
                            </button>
                          </div>
                        </div>
                      </div>

                      {/* Expanded steps */}
                      {isExpanded && (
                        <div className="px-5 pb-5">
                          <div
                            className="rounded-xl p-4"
                            style={{
                              background: isDark ? 'rgba(8,47,73,0.25)' : 'rgba(248,250,252,0.5)',
                              border: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)',
                            }}
                          >
                            <div className="flex items-center gap-2 mb-3">
                              <ListChecks className={`w-4 h-4 ${text.accent}`} />
                              <span className={`text-[11px] font-bold uppercase tracking-wider ${text.muted}`}>
                                Protocol Steps ({steps.length})
                              </span>
                            </div>
                            {steps.length === 0 ? (
                              <div className="flex items-center justify-center py-4">
                                <Loader2 className="w-5 h-5 animate-spin text-cyan-500" />
                              </div>
                            ) : (
                              <div className="space-y-2">
                                {steps.sort((a, b) => a.stepOrder - b.stepOrder).map((step) => (
                                  <div
                                    key={step.id}
                                    className="flex items-start gap-3 p-3 rounded-xl transition-all"
                                    style={glassInner}
                                  >
                                    <div
                                      className={`w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0 text-[10px] font-bold ${
                                        isDark ? 'bg-cyan-500/10 text-cyan-400' : 'bg-cyan-50 text-cyan-600'
                                      }`}
                                    >
                                      {step.stepOrder}
                                    </div>
                                    <div className="flex-1 min-w-0">
                                      <div className="flex items-center gap-2">
                                        <p className={`text-[12px] font-bold ${text.heading}`}>{step.stepTitle}</p>
                                        {step.isMandatory && (
                                          <span className="text-[8px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-500/10 text-red-500 border border-red-500/15">
                                            Required
                                          </span>
                                        )}
                                        {step.timeframeMinutes != null && (
                                          <span className={`text-[10px] font-medium flex items-center gap-1 ${text.muted}`}>
                                            <Clock className="w-3 h-3" /> {step.timeframeMinutes}m
                                          </span>
                                        )}
                                      </div>
                                      <p className={`text-[11px] font-medium mt-0.5 ${text.body}`}>{step.stepDescription}</p>
                                      {step.category && (
                                        <span className={`text-[9px] font-bold uppercase tracking-wider mt-1 inline-block ${text.muted}`}>
                                          {step.category}
                                        </span>
                                      )}
                                    </div>
                                  </div>
                                ))}
                              </div>
                            )}
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}

        {/* ═══════════════════════════════════════════════════════════
           ACTIVE PATHWAYS VIEW
           ═══════════════════════════════════════════════════════════ */}
        {mainView === 'active' && (
          <>
            {loading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
              </div>
            ) : !activeVisitSearched ? (
              <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(6,182,212,0.1)' }}>
                  <Search className="w-8 h-8 text-cyan-400" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>Enter a Visit ID</p>
                <p className={`text-xs font-medium mt-1 ${text.muted}`}>
                  Search for active clinical pathways by visit
                </p>
              </div>
            ) : activations.length === 0 ? (
              <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.1)' }}>
                  <Route className="w-8 h-8 text-slate-400" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>No active pathways</p>
                <p className={`text-xs font-medium mt-1 ${text.muted}`}>
                  No clinical pathways are currently active for this visit
                </p>
              </div>
            ) : (
              <div className="space-y-4">
                {activations.map((activation, idx) => {
                  const p = progressMap[activation.id];
                  const summary = summarize(activation.id);
                  const isExpanded = expandedActivationId === activation.id;
                  const isCompleted = activation.status === 'COMPLETED';
                  const isAbandoned = activation.status === 'ABANDONED';
                  const isActive = activation.status === 'ACTIVE';
                  // A pathway may be completed once no mandatory step is still outstanding —
                  // matching the backend rule (optional steps need not be done first). Requires a
                  // LOADED checklist: if progress failed to load (!p), the mandatory state is
                  // UNKNOWN, so block completion rather than presenting it as done (fail-safe).
                  const canComplete = !!p && summary.pendingMandatory === 0;
                  const progressFailed = !p && progressErrors.has(activation.id);

                  return (
                    <div
                      key={activation.id}
                      className={`rounded-2xl overflow-hidden transition-all animate-fade-up ${isCompleted ? 'opacity-70' : 'hover:-translate-y-0.5'}`}
                      style={{
                        ...glassCard,
                        ...(isAbandoned ? { border: '1px solid rgba(239,68,68,0.2)' } : {}),
                        animationDelay: `${0.05 + idx * 0.03}s`,
                      } as React.CSSProperties}
                    >
                      {/* Activation header */}
                      <div className="p-5">
                        <div className="flex items-start gap-4">
                          <div
                            className={`w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0 ${
                              isCompleted
                                ? 'bg-emerald-500/10'
                                : isAbandoned
                                  ? 'bg-red-500/10'
                                  : 'bg-cyan-500/10'
                            }`}
                          >
                            {isCompleted
                              ? <CheckCircle className="w-5 h-5 text-emerald-500" />
                              : isAbandoned
                                ? <XCircle className="w-5 h-5 text-red-500" />
                                : <Route className="w-5 h-5 text-cyan-500" />}
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2.5 mb-1 flex-wrap">
                              <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>
                                {activation.pathwayName}
                              </h3>
                              <span
                                className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${
                                  isCompleted
                                    ? 'bg-emerald-500/10 text-emerald-600 border border-emerald-500/20'
                                    : isAbandoned
                                      ? 'bg-red-500/10 text-red-600 border border-red-500/20'
                                      : 'bg-cyan-500/10 text-cyan-600 border border-cyan-500/20'
                                }`}
                              >
                                {activation.status}
                              </span>
                              {isActive && summary.overdue > 0 && (
                                <span className="inline-flex items-center gap-1 px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider bg-red-600/15 text-red-600 border border-red-600/25 animate-pulse">
                                  <AlertTriangle className="w-3 h-3" /> {summary.overdue} overdue
                                </span>
                              )}
                            </div>

                            {/* Meta */}
                            <div className="flex items-center gap-3 flex-wrap mb-3">
                              <span className={`text-[11px] font-medium ${text.muted}`}>
                                Activated by {activation.activatedByName}
                              </span>
                              <span className={`text-[11px] font-medium flex items-center gap-1 ${text.muted}`}>
                                <Clock className="w-3 h-3" />
                                {formatDistanceToNow(new Date(activation.activatedAt), { addSuffix: true })}
                              </span>
                              {activation.completedAt && (
                                <span className={`text-[11px] font-medium flex items-center gap-1 ${isDark ? 'text-emerald-400' : 'text-emerald-600'}`}>
                                  <CheckCircle className="w-3 h-3" />
                                  Completed {format(new Date(activation.completedAt), 'dd MMM yyyy HH:mm')}
                                </span>
                              )}
                            </div>

                            {/* Progress bar — or an explicit "unavailable" state if the checklist failed to load */}
                            {progressFailed ? (
                              <div className="flex items-center gap-2 text-[11px] font-bold text-red-500">
                                <AlertTriangle className="w-3.5 h-3.5" />
                                Step status unavailable — refresh
                              </div>
                            ) : (
                              <div className="flex items-center gap-3">
                                <div className={`flex-1 h-2.5 rounded-full overflow-hidden ${isDark ? 'bg-white/10' : 'bg-slate-100'}`}>
                                  <div
                                    className={`h-full rounded-full transition-all duration-500 ${
                                      isCompleted ? 'bg-emerald-500' : isAbandoned ? 'bg-red-400' : 'bg-gradient-to-r from-cyan-500 to-cyan-400'
                                    }`}
                                    style={{ width: `${summary.percent}%` }}
                                  />
                                </div>
                                <span className={`text-[11px] font-bold whitespace-nowrap ${text.heading}`}>
                                  {summary.completed}/{summary.total} steps ({summary.percent}%)
                                </span>
                              </div>
                            )}
                          </div>

                          {/* Actions */}
                          <div className="flex-shrink-0 flex flex-col gap-2 pt-1">
                            <button
                              onClick={() => setExpandedActivationId(isExpanded ? null : activation.id)}
                              className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                                isDark ? 'text-slate-400 bg-white/5 hover:bg-white/10 border border-white/10' : 'text-slate-500 bg-white/60 hover:bg-white/80 border border-slate-200/60'
                              }`}
                            >
                              {isExpanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                              {isExpanded ? 'Collapse' : 'Steps'}
                            </button>
                            {isActive && (
                              <button
                                onClick={() => handleCompletePathway(activation.id)}
                                disabled={actionLoading === activation.id || !canComplete}
                                title={canComplete ? 'Complete pathway'
                                  : progressFailed ? 'Step checklist failed to load — refresh before completing'
                                  : `${summary.pendingMandatory} mandatory step(s) outstanding`}
                                className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all shadow-md shadow-cyan-600/15 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:translate-y-0"
                              >
                                {actionLoading === activation.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Flag className="w-3.5 h-3.5" />}
                                Complete{!canComplete && !progressFailed ? ` (${summary.pendingMandatory})` : ''}
                              </button>
                            )}
                            {isActive && (
                              <button
                                onClick={() => openAbandonDialog(activation.id)}
                                className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                                  isDark ? 'text-red-400 bg-red-500/5 hover:bg-red-500/10 border border-red-500/15' : 'text-red-500 bg-red-50 hover:bg-red-100 border border-red-200/60'
                                }`}
                              >
                                <XCircle className="w-3.5 h-3.5" /> Abandon
                              </button>
                            )}
                          </div>
                        </div>
                      </div>

                      {/* Expanded step checklist — driven by live per-activation progress */}
                      {isExpanded && (
                        <div className="px-5 pb-5">
                          <div
                            className="rounded-xl p-4"
                            style={{
                              background: isDark ? 'rgba(8,47,73,0.25)' : 'rgba(248,250,252,0.5)',
                              border: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)',
                            }}
                          >
                            <div className="flex items-center gap-2 mb-3">
                              <ListChecks className={`w-4 h-4 ${text.accent}`} />
                              <span className={`text-[11px] font-bold uppercase tracking-wider ${text.muted}`}>
                                Step Checklist
                              </span>
                            </div>
                            {!p ? (
                              progressFailed ? (
                                <div className="flex items-center justify-between gap-3 py-2">
                                  <p className="text-[11px] font-semibold text-red-500 flex items-center gap-1.5">
                                    <AlertTriangle className="w-3.5 h-3.5" /> Couldn't load this pathway's step checklist.
                                  </p>
                                  <button
                                    onClick={() => refreshProgress(activation.id)}
                                    className="inline-flex items-center gap-1 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors"
                                  >
                                    <RefreshCw className="w-3 h-3" /> Retry
                                  </button>
                                </div>
                              ) : (
                                <div className="flex items-center justify-center py-4">
                                  <Loader2 className="w-5 h-5 animate-spin text-cyan-500" />
                                </div>
                              )
                            ) : p.steps.length === 0 ? (
                              <p className={`text-[11px] font-medium ${text.muted}`}>This pathway has no steps configured.</p>
                            ) : (
                            <div className="space-y-2">
                              {p.steps.map((step) => {
                                const isDone = step.status === 'COMPLETED';
                                const isSkipped = step.status === 'SKIPPED';
                                const isOverdue = step.status === 'OVERDUE';
                                const stepLoading = actionLoading === `${activation.id}-${step.stepId}`;
                                const timer = (!isDone && !isSkipped)
                                  ? stepTimer(activation.activatedAt, step.timeframeMinutes, step.isMandatory) : null;

                                return (
                                  <div
                                    key={step.stepId}
                                    className={`flex items-start gap-3 p-3 rounded-xl transition-all ${
                                      isDone || isSkipped ? 'opacity-70' : ''
                                    }`}
                                    style={glassInner}
                                  >
                                    {/* Status indicator */}
                                    <div className="flex-shrink-0 pt-0.5">
                                      {isDone ? (
                                        <div className="w-7 h-7 rounded-lg bg-emerald-500/10 flex items-center justify-center">
                                          <CheckCircle className="w-4 h-4 text-emerald-500" />
                                        </div>
                                      ) : isSkipped ? (
                                        <div className="w-7 h-7 rounded-lg bg-amber-500/10 flex items-center justify-center">
                                          <SkipForward className="w-4 h-4 text-amber-500" />
                                        </div>
                                      ) : isOverdue ? (
                                        <div className="w-7 h-7 rounded-lg bg-red-500/10 flex items-center justify-center">
                                          <AlertTriangle className="w-4 h-4 text-red-500" />
                                        </div>
                                      ) : (
                                        <div className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'bg-white/10' : 'bg-slate-100'}`}>
                                          <div className={`w-3 h-3 rounded-full border-2 ${isDark ? 'border-slate-500' : 'border-slate-300'}`} />
                                        </div>
                                      )}
                                    </div>

                                    {/* Content */}
                                    <div className="flex-1 min-w-0">
                                      <div className="flex items-center gap-2 flex-wrap">
                                        <p className={`text-[12px] font-bold ${isDone || isSkipped ? 'line-through' : ''} ${text.heading}`}>
                                          {step.stepOrder}. {step.stepTitle}
                                        </p>
                                        {step.isMandatory && (
                                          <span className="text-[8px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-500/10 text-red-500 border border-red-500/15">
                                            Required
                                          </span>
                                        )}
                                        {timer && (
                                          <span className={`text-[9px] font-bold inline-flex items-center gap-0.5 ${timer.overdue ? 'text-red-600' : 'text-amber-500'}`}>
                                            <Clock className="w-2.5 h-2.5" /> {timer.text}
                                          </span>
                                        )}
                                      </div>
                                      {isDone && step.completedByName && (
                                        <p className={`text-[10px] font-medium mt-0.5 ${text.muted}`}>
                                          Completed by {step.completedByName}
                                          {step.completedAt && ` ${formatDistanceToNow(new Date(step.completedAt), { addSuffix: true })}`}
                                          {step.timeToCompleteMinutes != null && ` (${step.timeToCompleteMinutes}m)`}
                                        </p>
                                      )}
                                      {isSkipped && step.skipReason && (
                                        <p className="text-[10px] font-medium mt-0.5 text-amber-500">
                                          Skipped: {step.skipReason}
                                        </p>
                                      )}
                                    </div>

                                    {/* Step actions */}
                                    {isActive && !isDone && !isSkipped && (
                                      <div className="flex-shrink-0 flex gap-2">
                                        <button
                                          onClick={() => handleCompleteStep(activation.id, step.stepId)}
                                          disabled={stepLoading}
                                          className="inline-flex items-center gap-1 px-3 py-2 text-[10px] font-bold text-white bg-cyan-600 hover:bg-cyan-700 rounded-xl transition-all hover:shadow-md hover:-translate-y-0.5 disabled:opacity-50"
                                        >
                                          {stepLoading ? <Loader2 className="w-3 h-3 animate-spin" /> : <CheckCircle className="w-3 h-3" />}
                                          Done
                                        </button>
                                        <button
                                          onClick={() => openSkipDialog(activation.id, step.stepId, step.stepTitle)}
                                          className={`inline-flex items-center gap-1 px-3 py-2 text-[10px] font-bold rounded-lg transition-all ${
                                            isDark ? 'text-amber-400 bg-amber-500/5 hover:bg-amber-500/10 border border-amber-500/15' : 'text-amber-600 bg-amber-50 hover:bg-amber-100 border border-amber-200/60'
                                          }`}
                                        >
                                          <SkipForward className="w-3 h-3" /> Skip
                                        </button>
                                      </div>
                                    )}
                                  </div>
                                );
                              })}
                            </div>
                            )}
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </>
        )}
      </div>

      {/* ═══════════════════════════════════════════════════════════════
         Activate Pathway Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {activateDialogOpen && (
        <div
          className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
          style={{ background: 'var(--modal-backdrop)' }}
          onClick={() => !activateSubmitting && setActivateDialogOpen(false)}
        >
          <div
            className="relative w-full max-w-md rounded-2xl overflow-hidden p-6 shadow-2xl animate-scale-in"
            style={glassCard}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-cyan-500/10">
                  <Play className="w-5 h-5 text-cyan-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Activate Pathway</h3>
                  <p className={`text-[11px] ${text.muted}`}>{activatePathwayName}</p>
                </div>
              </div>
              <button
                onClick={() => !activateSubmitting && setActivateDialogOpen(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="mb-5">
              <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>
                Visit ID *
              </label>
              <input
                type="text"
                value={activateVisitId}
                onChange={(e) => setActivateVisitId(e.target.value)}
                placeholder="Enter the visit ID..."
                autoFocus
                className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => !activateSubmitting && setActivateDialogOpen(false)}
                disabled={activateSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={submitActivate}
                disabled={activateSubmitting || !activateVisitId.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-cyan-600 hover:bg-cyan-700 shadow-cyan-600/20 hover:shadow-cyan-600/30 hover:-translate-y-0.5"
              >
                {activateSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
                {activateSubmitting ? 'Activating...' : 'Activate Pathway'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════
         Skip Step Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {skipDialogOpen && (
        <div
          className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
          style={{ background: 'var(--modal-backdrop)' }}
          onClick={() => !skipSubmitting && setSkipDialogOpen(false)}
        >
          <div
            className="relative w-full max-w-md rounded-2xl overflow-hidden p-6 shadow-2xl animate-scale-in"
            style={glassCard}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-amber-500/10">
                  <SkipForward className="w-5 h-5 text-amber-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Skip Step</h3>
                  <p className={`text-[11px] ${text.muted}`}>{skipStepTitle}</p>
                </div>
              </div>
              <button
                onClick={() => !skipSubmitting && setSkipDialogOpen(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="mb-5">
              <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>
                <MessageSquare className="w-3 h-3 inline mr-1" /> Reason *
              </label>
              <textarea
                value={skipReason}
                onChange={(e) => setSkipReason(e.target.value)}
                placeholder="Why is this step being skipped?"
                rows={3}
                autoFocus
                className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => !skipSubmitting && setSkipDialogOpen(false)}
                disabled={skipSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={submitSkip}
                disabled={skipSubmitting || !skipReason.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-gradient-to-r from-amber-500 to-amber-600 shadow-amber-500/20 hover:shadow-amber-500/30 hover:-translate-y-0.5"
              >
                {skipSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <SkipForward className="w-3.5 h-3.5" />}
                {skipSubmitting ? 'Skipping...' : 'Skip Step'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════
         Abandon Pathway Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {abandonDialogOpen && (
        <div
          className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
          style={{ background: 'var(--modal-backdrop)' }}
          onClick={() => !abandonSubmitting && setAbandonDialogOpen(false)}
        >
          <div
            className="relative w-full max-w-md rounded-2xl overflow-hidden p-6 shadow-2xl animate-scale-in"
            style={glassCard}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-red-500/10">
                  <XCircle className="w-5 h-5 text-red-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Abandon Pathway</h3>
                  <p className={`text-[11px] ${text.muted}`}>This action cannot be undone</p>
                </div>
              </div>
              <button
                onClick={() => !abandonSubmitting && setAbandonDialogOpen(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="mb-5">
              <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>
                <MessageSquare className="w-3 h-3 inline mr-1" /> Reason *
              </label>
              <textarea
                value={abandonReason}
                onChange={(e) => setAbandonReason(e.target.value)}
                placeholder="Why is this pathway being abandoned?"
                rows={3}
                autoFocus
                className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => !abandonSubmitting && setAbandonDialogOpen(false)}
                disabled={abandonSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={submitAbandon}
                disabled={abandonSubmitting || !abandonReason.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-gradient-to-r from-red-500 to-red-600 shadow-red-500/20 hover:shadow-red-500/30 hover:-translate-y-0.5"
              >
                {abandonSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                {abandonSubmitting ? 'Abandoning...' : 'Abandon Pathway'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
