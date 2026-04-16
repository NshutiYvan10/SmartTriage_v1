/* ═══════════════════════════════════════════════════════════════
   Hypoglycemia Management — Module 10
   Unresolved hypoglycemia events with treatment workflow
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  Droplets, AlertTriangle, CheckCircle2, RefreshCw,
  Loader2, Clock, Syringe, FlaskConical, ArrowRight,
  Activity, CircleDot,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { hypoglycemiaApi } from '@/api/hypoglycemia';
import type { HypoglycemiaEvent } from '@/api/hypoglycemia';
import { format } from 'date-fns';

/* ── Severity colour map ─────────────────────────────────── */
const SEVERITY_CONFIG: Record<string, { color: string; bg: string; border: string; label: string }> = {
  SEVERE:   { color: 'text-red-500',    bg: 'bg-red-500/10',    border: 'border-red-500/20',    label: 'SEVERE' },
  MODERATE: { color: 'text-amber-500',  bg: 'bg-amber-500/10',  border: 'border-amber-500/20',  label: 'MODERATE' },
  MILD:     { color: 'text-yellow-500', bg: 'bg-yellow-500/10', border: 'border-yellow-500/20', label: 'MILD' },
};

const TREATMENT_OPTIONS = [
  'IV Dextrose 50% 50ml',
  'Oral glucose gel',
  'Sweet drink',
  'IV D10W infusion',
];

function getGlucoseSeverity(level: number | null): { color: string; bgColor: string; label: string } {
  if (level === null) return { color: 'text-slate-400', bgColor: 'bg-slate-500/10', label: 'N/A' };
  if (level < 2.2) return { color: 'text-red-500', bgColor: 'bg-red-500/10', label: 'SEVERE' };
  if (level < 3.0) return { color: 'text-amber-500', bgColor: 'bg-amber-500/10', label: 'MODERATE' };
  if (level < 4.0) return { color: 'text-yellow-500', bgColor: 'bg-yellow-500/10', label: 'MILD' };
  return { color: 'text-emerald-500', bgColor: 'bg-emerald-500/10', label: 'NORMAL' };
}

type WorkflowStep = 'treat' | 'repeat-glucose' | 'resolve';

export function HypoglycemiaView() {
  const { glassCard, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [events, setEvents] = useState<HypoglycemiaEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  /* Workflow modal state */
  const [activeEventId, setActiveEventId] = useState<string | null>(null);
  const [workflowStep, setWorkflowStep] = useState<WorkflowStep | null>(null);
  const [selectedTreatment, setSelectedTreatment] = useState<string>('');
  const [repeatGlucose, setRepeatGlucose] = useState<string>('');

  /* ── Data loading ──────────────────────────────────────── */
  const loadEvents = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const data = await hypoglycemiaApi.getUnresolved(hospitalId);
      setEvents(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load hypoglycemia events:', err);
      setEvents([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadEvents(); }, [loadEvents]);

  /* ── Actions ───────────────────────────────────────────── */
  const handleRecordTreatment = async (id: string) => {
    if (!selectedTreatment) return;
    setActionLoading(id);
    try {
      await hypoglycemiaApi.recordTreatment(id, { treatmentGiven: selectedTreatment });
      setActiveEventId(null);
      setWorkflowStep(null);
      setSelectedTreatment('');
      loadEvents();
    } catch (err) {
      console.error('Failed to record treatment:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleRecordRepeatGlucose = async (id: string) => {
    const value = parseFloat(repeatGlucose);
    if (isNaN(value) || value <= 0) return;
    setActionLoading(id);
    try {
      await hypoglycemiaApi.recordRepeatGlucose(id, { repeatGlucoseLevel: value });
      setActiveEventId(null);
      setWorkflowStep(null);
      setRepeatGlucose('');
      loadEvents();
    } catch (err) {
      console.error('Failed to record repeat glucose:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleResolve = async (id: string) => {
    setActionLoading(id);
    try {
      await hypoglycemiaApi.resolve(id);
      loadEvents();
    } catch (err) {
      console.error('Failed to resolve event:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const openWorkflow = (eventId: string, step: WorkflowStep) => {
    setActiveEventId(eventId);
    setWorkflowStep(step);
    setSelectedTreatment('');
    setRepeatGlucose('');
  };

  const closeWorkflow = () => {
    setActiveEventId(null);
    setWorkflowStep(null);
    setSelectedTreatment('');
    setRepeatGlucose('');
  };

  /* ── Determine next workflow step for an event ─────────── */
  const getNextStep = (evt: HypoglycemiaEvent): WorkflowStep | null => {
    if (!evt.treatmentGiven) return 'treat';
    if (evt.repeatGlucoseLevel === null) return 'repeat-glucose';
    return 'resolve';
  };

  const unresolvedCount = events.length;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ─────────────────────────────────────── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-purple-500/20 flex items-center justify-center">
                  <Droplets className="w-5 h-5 text-purple-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Hypoglycemia Management</h1>
                  <p className="text-white/50 text-xs">Monitor and treat low blood glucose events</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {unresolvedCount > 0 && (
                  <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-red-500/20 border border-red-500/30">
                    <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                    <span className="text-red-400 text-xs font-bold">{unresolvedCount} Unresolved</span>
                  </div>
                )}
                <button onClick={loadEvents} className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Event List ─────────────────────────────────── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : events.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
            <p className={`text-sm font-bold ${text.heading}`}>No unresolved events</p>
            <p className={`text-xs mt-1 ${text.muted}`}>All hypoglycemia events have been resolved</p>
          </div>
        ) : (
          <div className="space-y-3">
            {events.map((evt, i) => {
              const sev = SEVERITY_CONFIG[evt.severity] || SEVERITY_CONFIG.MILD;
              const glucoseInfo = getGlucoseSeverity(evt.glucoseLevel);
              const nextStep = getNextStep(evt);
              const isActive = activeEventId === evt.id;

              return (
                <div
                  key={evt.id}
                  className="rounded-2xl overflow-hidden transition-all animate-fade-up"
                  style={{ ...glassCard, animationDelay: `${i * 0.03}s` }}
                >
                  <div className="p-4">
                    <div className="flex items-start gap-4">
                      {/* Glucose level badge */}
                      <div className={`w-14 h-14 rounded-xl ${glucoseInfo.bgColor} flex flex-col items-center justify-center shrink-0`}>
                        <span className={`text-lg font-bold ${glucoseInfo.color}`}>
                          {evt.glucoseLevel !== null ? evt.glucoseLevel.toFixed(1) : '—'}
                        </span>
                        <span className={`text-[8px] font-bold uppercase ${glucoseInfo.color}`}>mmol/L</span>
                      </div>

                      <div className="flex-1 min-w-0">
                        {/* Badges row */}
                        <div className="flex flex-wrap items-center gap-2 mb-1.5">
                          <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg border ${sev.bg} ${sev.color} ${sev.border}`}>
                            {sev.label}
                          </span>
                          <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                            {evt.triggerReason?.replace(/_/g, ' ') || 'Unknown trigger'}
                          </span>
                          <span className={`ml-auto text-[10px] flex items-center gap-1 ${text.muted}`}>
                            <Clock className="w-3 h-3" />
                            {evt.detectedAt ? format(new Date(evt.detectedAt), 'dd MMM yyyy HH:mm') : '—'}
                          </span>
                        </div>

                        {/* Treatment status */}
                        <div className="flex flex-wrap items-center gap-3 mt-2">
                          {evt.treatmentGiven ? (
                            <div className="flex items-center gap-1.5">
                              <Syringe className="w-3.5 h-3.5 text-emerald-500" />
                              <span className={`text-xs ${text.body}`}>{evt.treatmentGiven}</span>
                              {evt.treatmentGivenAt && (
                                <span className={`text-[10px] ${text.muted}`}>
                                  at {format(new Date(evt.treatmentGivenAt), 'HH:mm')}
                                </span>
                              )}
                              {evt.treatmentGivenByName && (
                                <span className={`text-[10px] ${text.muted}`}>
                                  by {evt.treatmentGivenByName}
                                </span>
                              )}
                            </div>
                          ) : (
                            <div className="flex items-center gap-1.5">
                              <AlertTriangle className="w-3.5 h-3.5 text-amber-500" />
                              <span className="text-xs text-amber-500 font-medium">Awaiting treatment</span>
                            </div>
                          )}
                        </div>

                        {/* Repeat glucose comparison */}
                        {evt.treatmentGiven && evt.repeatGlucoseLevel !== null && (
                          <div className="flex items-center gap-2 mt-2 px-3 py-1.5 rounded-lg bg-slate-500/5">
                            <FlaskConical className="w-3.5 h-3.5 text-cyan-500" />
                            <span className={`text-xs ${text.body}`}>
                              Before: <strong className={glucoseInfo.color}>{evt.glucoseLevel?.toFixed(1) ?? '—'}</strong>
                            </span>
                            <ArrowRight className="w-3 h-3 text-slate-400" />
                            <span className={`text-xs ${text.body}`}>
                              After: <strong className={getGlucoseSeverity(evt.repeatGlucoseLevel).color}>
                                {evt.repeatGlucoseLevel.toFixed(1)}
                              </strong>
                            </span>
                            {evt.repeatGlucoseAt && (
                              <span className={`text-[10px] ${text.muted}`}>
                                at {format(new Date(evt.repeatGlucoseAt), 'HH:mm')}
                              </span>
                            )}
                          </div>
                        )}

                        {evt.notes && (
                          <p className={`text-xs mt-2 ${text.muted}`}>{evt.notes}</p>
                        )}

                        {/* Action buttons */}
                        {!isActive && nextStep && (
                          <div className="flex items-center gap-2 mt-3">
                            {nextStep === 'treat' && (
                              <button
                                onClick={() => openWorkflow(evt.id, 'treat')}
                                disabled={actionLoading === evt.id}
                                className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-500 hover:bg-purple-500/20 transition-colors"
                              >
                                <Syringe className="w-3.5 h-3.5" /> Record Treatment
                              </button>
                            )}
                            {nextStep === 'repeat-glucose' && (
                              <button
                                onClick={() => openWorkflow(evt.id, 'repeat-glucose')}
                                disabled={actionLoading === evt.id}
                                className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors"
                              >
                                <FlaskConical className="w-3.5 h-3.5" /> Record Repeat Glucose
                              </button>
                            )}
                            {nextStep === 'resolve' && (
                              <button
                                onClick={() => handleResolve(evt.id)}
                                disabled={actionLoading === evt.id}
                                className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors"
                              >
                                {actionLoading === evt.id ? (
                                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                ) : (
                                  <CheckCircle2 className="w-3.5 h-3.5" />
                                )}
                                Resolve
                              </button>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>

                  {/* ── Inline Workflow Panel ─────────────── */}
                  {isActive && workflowStep === 'treat' && (
                    <div
                      className="px-4 pb-4 pt-2"
                      style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}
                    >
                      <p className={`text-xs font-bold mb-3 ${text.heading}`}>Select Treatment</p>
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                        {TREATMENT_OPTIONS.map((opt) => (
                          <button
                            key={opt}
                            onClick={() => setSelectedTreatment(opt)}
                            className={`px-4 py-2.5 text-xs font-medium rounded-xl border transition-all text-left ${
                              selectedTreatment === opt
                                ? 'bg-purple-500/15 border-purple-500/40 text-purple-400'
                                : isDark
                                  ? 'border-white/10 text-slate-300 hover:border-purple-500/30 hover:bg-purple-500/5'
                                  : 'border-slate-200 text-slate-600 hover:border-purple-400/30 hover:bg-purple-50'
                            }`}
                          >
                            <Syringe className="w-3.5 h-3.5 inline mr-2" />
                            {opt}
                          </button>
                        ))}
                      </div>
                      <div className="flex items-center gap-2 mt-3">
                        <button
                          onClick={() => handleRecordTreatment(evt.id)}
                          disabled={!selectedTreatment || actionLoading === evt.id}
                          className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-500 hover:bg-purple-500/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                          {actionLoading === evt.id ? (
                            <Loader2 className="w-3.5 h-3.5 animate-spin" />
                          ) : (
                            <CheckCircle2 className="w-3.5 h-3.5" />
                          )}
                          Confirm Treatment
                        </button>
                        <button
                          onClick={closeWorkflow}
                          className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${
                            isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-100'
                          }`}
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  )}

                  {isActive && workflowStep === 'repeat-glucose' && (
                    <div
                      className="px-4 pb-4 pt-2"
                      style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}
                    >
                      <p className={`text-xs font-bold mb-3 ${text.heading}`}>Record Repeat Glucose Level</p>
                      <div className="flex items-center gap-3">
                        <div className="relative">
                          <input
                            type="number"
                            step="0.1"
                            min="0"
                            max="30"
                            value={repeatGlucose}
                            onChange={(e) => setRepeatGlucose(e.target.value)}
                            placeholder="e.g. 4.2"
                            className={`w-32 px-3 py-2 text-sm rounded-xl border outline-none transition-colors ${
                              isDark
                                ? 'bg-white/5 border-white/10 text-white placeholder:text-slate-500 focus:border-cyan-500/40'
                                : 'bg-white border-slate-200 text-slate-800 placeholder:text-slate-400 focus:border-cyan-500'
                            }`}
                          />
                          <span className={`absolute right-3 top-1/2 -translate-y-1/2 text-[10px] ${text.muted}`}>mmol/L</span>
                        </div>
                        <button
                          onClick={() => handleRecordRepeatGlucose(evt.id)}
                          disabled={!repeatGlucose || parseFloat(repeatGlucose) <= 0 || actionLoading === evt.id}
                          className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                          {actionLoading === evt.id ? (
                            <Loader2 className="w-3.5 h-3.5 animate-spin" />
                          ) : (
                            <FlaskConical className="w-3.5 h-3.5" />
                          )}
                          Save
                        </button>
                        <button
                          onClick={closeWorkflow}
                          className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${
                            isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-100'
                          }`}
                        >
                          Cancel
                        </button>
                      </div>
                      {repeatGlucose && !isNaN(parseFloat(repeatGlucose)) && (
                        <div className="flex items-center gap-2 mt-2">
                          <span className={`text-[10px] ${text.muted}`}>Preview:</span>
                          <span className={`text-xs font-bold ${getGlucoseSeverity(parseFloat(repeatGlucose)).color}`}>
                            {parseFloat(repeatGlucose).toFixed(1)} mmol/L
                          </span>
                          <span className={`text-[10px] px-1.5 py-0.5 rounded ${getGlucoseSeverity(parseFloat(repeatGlucose)).bgColor} ${getGlucoseSeverity(parseFloat(repeatGlucose)).color}`}>
                            {getGlucoseSeverity(parseFloat(repeatGlucose)).label}
                          </span>
                        </div>
                      )}
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
