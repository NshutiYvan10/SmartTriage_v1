/* ═══════════════════════════════════════════════════════════════
   Hypoglycemia Management — Module 10
   Unresolved hypoglycemia events with treatment workflow
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Droplets, AlertTriangle, CheckCircle2, RefreshCw,
  Loader2, Clock, Syringe, FlaskConical, ArrowRight,
  Activity, CircleDot, ChevronRight,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';
import { chartPath } from '@/lib/chartNav';
import { useAuthStore } from '@/store/authStore';
import { hypoglycemiaApi } from '@/api/hypoglycemia';
import { subscribeToHypoglycemia } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { ApiError } from '@/api/client';
import type { HypoglycemiaEvent } from '@/api/hypoglycemia';
import { format } from 'date-fns';

/* ── Severity colour map — keys MUST match the backend HypoglycemiaSeverity enum
   (NORMAL/MILD/MODERATE/SEVERE/PENDING_CHECK). An unknown value falls back to
   SEVERE (red) — NEVER downgrade an unrecognised band to a low-urgency colour. ── */
const SEVERITY_FALLBACK = { color: 'text-red-500', bg: 'bg-red-500/10', border: 'border-red-500/20', label: 'CHECK' };
const SEVERITY_CONFIG: Record<string, { color: string; bg: string; border: string; label: string }> = {
  SEVERE:        { color: 'text-red-600',     bg: 'bg-red-500/15',    border: 'border-red-500/30',    label: 'SEVERE' },
  MODERATE:      { color: 'text-red-400',     bg: 'bg-red-500/10',    border: 'border-red-500/20',    label: 'MODERATE' },
  MILD:          { color: 'text-amber-500',   bg: 'bg-amber-500/10',  border: 'border-amber-500/20',  label: 'MILD' },
  NORMAL:        { color: 'text-emerald-500', bg: 'bg-emerald-500/10', border: 'border-emerald-500/20', label: 'NORMAL' },
  PENDING_CHECK: { color: 'text-amber-500',   bg: 'bg-amber-500/10',  border: 'border-amber-500/20',  label: 'CHECK PENDING' },
};

const TREATMENT_OPTIONS = [
  'IV Dextrose 50% 50ml',
  'Oral glucose gel',
  'Sweet drink',
  'IV D10W infusion',
];

function getGlucoseSeverity(level: number | null | undefined): { color: string; bgColor: string; label: string } {
  // Loose null check — backend omits null fields (non_null serialization) so an
  // un-valued reading arrives as `undefined`; `=== null` would fall through to NORMAL.
  if (level == null) return { color: 'text-slate-400', bgColor: 'bg-slate-500/10', label: 'N/A' };
  if (level < 2.2) return { color: 'text-red-500', bgColor: 'bg-red-500/10', label: 'SEVERE' };
  if (level < 3.0) return { color: 'text-amber-500', bgColor: 'bg-amber-500/10', label: 'MODERATE' };
  if (level < 4.0) return { color: 'text-yellow-500', bgColor: 'bg-yellow-500/10', label: 'MILD' };
  return { color: 'text-emerald-500', bgColor: 'bg-emerald-500/10', label: 'NORMAL' };
}

type WorkflowStep = 'treat' | 'repeat-glucose' | 'resolve';

export function HypoglycemiaView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const wsGen = useWebSocketGeneration();
  const [events, setEvents] = useState<HypoglycemiaEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  /* Workflow modal state */
  const [activeEventId, setActiveEventId] = useState<string | null>(null);
  const [workflowStep, setWorkflowStep] = useState<WorkflowStep | null>(null);
  const [selectedTreatment, setSelectedTreatment] = useState<string>('');
  const [repeatGlucose, setRepeatGlucose] = useState<string>('');
  const [repeatUnit, setRepeatUnit] = useState<'MMOL_L' | 'MG_DL'>('MMOL_L');

  /* ── Data loading ──────────────────────────────────────── */
  const loadEvents = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const data = await hypoglycemiaApi.getUnresolved(hospitalId);
      setEvents(Array.isArray(data) ? data : []);
      setError(null);
    } catch (err) {
      // Surface the failure — never render a green "all clear" empty state when
      // the load actually failed (that would mask a real outage as resolved).
      console.error('Failed to load hypoglycemia events:', err);
      setEvents([]);
      setError(err instanceof ApiError ? err.message : 'Failed to load hypoglycemia events');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadEvents(); }, [loadEvents]);

  /* ── Live refresh — dedicated hypoglycemia topic; re-subscribes on reconnect. ── */
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToHypoglycemia(hospitalId, () => { loadEvents(); });
    return () => unsub();
  }, [hospitalId, loadEvents, wsGen]);

  const reportError = (err: unknown, fallback: string) => {
    setError(err instanceof ApiError ? err.message : err instanceof Error ? err.message : fallback);
    console.error(fallback, err);
  };

  /* ── Actions ───────────────────────────────────────────── */
  const handleRecordTreatment = async (id: string) => {
    if (!selectedTreatment) return;
    setActionLoading(id);
    setError(null);
    try {
      await hypoglycemiaApi.recordTreatment(id, { treatment: selectedTreatment });
      setActiveEventId(null);
      setWorkflowStep(null);
      setSelectedTreatment('');
      loadEvents();
    } catch (err) {
      reportError(err, 'Failed to record treatment');
    } finally {
      setActionLoading(null);
    }
  };

  const handleRecordRepeatGlucose = async (id: string) => {
    const value = parseFloat(repeatGlucose);
    if (isNaN(value) || value <= 0) return;
    setActionLoading(id);
    setError(null);
    try {
      await hypoglycemiaApi.recordRepeatGlucose(id, { glucoseLevel: value, unit: repeatUnit });
      setActiveEventId(null);
      setWorkflowStep(null);
      setRepeatGlucose('');
      setRepeatUnit('MMOL_L');
      loadEvents();
    } catch (err) {
      reportError(err, 'Failed to record repeat glucose');
    } finally {
      setActionLoading(null);
    }
  };

  const handleResolve = async (id: string) => {
    setActionLoading(id);
    setError(null);
    try {
      await hypoglycemiaApi.resolve(id);
      loadEvents();
    } catch (err) {
      reportError(err, 'Failed to resolve event');
    } finally {
      setActionLoading(null);
    }
  };

  const openWorkflow = (eventId: string, step: WorkflowStep) => {
    setActiveEventId(eventId);
    setWorkflowStep(step);
    setSelectedTreatment('');
    setRepeatGlucose('');
    setRepeatUnit('MMOL_L');
  };

  const closeWorkflow = () => {
    setActiveEventId(null);
    setWorkflowStep(null);
    setSelectedTreatment('');
    setRepeatGlucose('');
    setRepeatUnit('MMOL_L');
  };

  /* ── Determine next workflow step for an event ─────────── */
  const getNextStep = (evt: HypoglycemiaEvent): WorkflowStep | null => {
    if (!evt.treatmentGiven) return 'treat';
    // Loose null — backend omits null fields, so an un-recorded repeat arrives as
    // `undefined`; `=== null` would skip straight to resolve and let a clinician
    // close the event WITHOUT the mandatory post-treatment glucose recheck.
    if (evt.repeatGlucoseLevel == null) return 'repeat-glucose';
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
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <Droplets className="w-5 h-5 text-cyan-300" />
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

        {error && (
          <div className="rounded-2xl px-4 py-3 flex items-start gap-2 bg-red-500/10 border border-red-500/20 animate-fade-up">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[12px] font-semibold text-red-500">{error}</p>
          </div>
        )}

        {/* ── Event List ─────────────────────────────────── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : events.length === 0 && !error ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
            <p className={`text-sm font-bold ${text.heading}`}>No unresolved events</p>
            <p className={`text-xs mt-1 ${text.muted}`}>All hypoglycemia events have been resolved</p>
          </div>
        ) : events.length === 0 ? null : (
          <div className="space-y-3">
            {events.map((evt, i) => {
              const sev = SEVERITY_CONFIG[evt.severity] || SEVERITY_FALLBACK;
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
                          {evt.glucoseLevel != null ? evt.glucoseLevel.toFixed(1) : '—'}
                        </span>
                        <span className={`text-[8px] font-bold uppercase ${glucoseInfo.color}`}>mmol/L</span>
                      </div>

                      <div className="flex-1 min-w-0">
                        {/* Patient identity — a hospital-wide board MUST always say WHO is
                            hypoglycemic and WHERE; click-through opens that patient's chart. */}
                        <div className="flex items-start justify-between gap-2 mb-1">
                          <button
                            type="button"
                            onClick={() => navigate(chartPath(evt.visitId))}
                            className="group flex items-center gap-1.5 min-w-0 text-left hover:opacity-80 transition-opacity"
                            title="Open patient chart"
                          >
                            <PatientContextLine
                              patientName={evt.patientName}
                              zone={evt.currentZone ? evt.currentZone.replace(/_/g, ' ') : null}
                              bedLabel={evt.currentBedLabel}
                              visitNumber={evt.visitNumber}
                              className={`text-sm ${text.heading}`}
                            />
                            {evt.neonatal && (
                              <span className="ml-1 inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-fuchsia-600 shrink-0" style={{ background: 'rgba(217,70,239,0.08)', border: '1px solid rgba(217,70,239,0.2)' }}>NEONATAL</span>
                            )}
                            <ChevronRight className={`w-4 h-4 shrink-0 ${text.muted} group-hover:translate-x-0.5 transition-transform`} />
                          </button>
                        </div>
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
                        {evt.treatmentGiven && evt.repeatGlucoseLevel != null && (
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
                      style={{ borderTop: borderStyle }}
                    >
                      <p className={`text-xs font-bold mb-3 ${text.heading}`}>Select Treatment</p>
                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                        {TREATMENT_OPTIONS.map((opt) => (
                          <button
                            key={opt}
                            onClick={() => setSelectedTreatment(opt)}
                            style={selectedTreatment === opt ? undefined : glassInner}
                            className={`px-4 py-2.5 text-xs font-medium rounded-xl border transition-all text-left ${
                              selectedTreatment === opt
                                ? 'bg-purple-500/15 border-purple-500/40 text-purple-400'
                                : `${text.body} hover:border-purple-500/30 hover:bg-purple-500/5`
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
                          className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${text.body} hover:bg-white/5`}
                        >
                          Cancel
                        </button>
                      </div>
                    </div>
                  )}

                  {isActive && workflowStep === 'repeat-glucose' && (
                    <div
                      className="px-4 pb-4 pt-2"
                      style={{ borderTop: borderStyle }}
                    >
                      <p className={`text-xs font-bold mb-3 ${text.heading}`}>Record Repeat Glucose Level</p>
                      <div className="flex items-center gap-3 flex-wrap">
                        <input
                          type="number"
                          step={repeatUnit === 'MG_DL' ? '1' : '0.1'}
                          min="0"
                          max={repeatUnit === 'MG_DL' ? '600' : '30'}
                          value={repeatGlucose}
                          onChange={(e) => setRepeatGlucose(e.target.value)}
                          placeholder={repeatUnit === 'MG_DL' ? 'e.g. 75' : 'e.g. 4.2'}
                          style={glassInner}
                          className={`w-28 px-3 py-2 text-sm rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder:text-slate-400 ${text.heading}`}
                        />
                        {/* Unit toggle — a mg/dL glucometer reading is converted server-side */}
                        <div className="inline-flex rounded-xl p-0.5" style={glassInner}>
                          {(['MMOL_L', 'MG_DL'] as const).map((u) => (
                            <button
                              key={u}
                              type="button"
                              onClick={() => setRepeatUnit(u)}
                              className={`px-2.5 py-1.5 text-[10px] font-bold rounded-lg transition-colors ${
                                repeatUnit === u
                                  ? 'bg-cyan-500/20 text-cyan-500'
                                  : `${text.body} hover:text-cyan-400`
                              }`}
                            >
                              {u === 'MMOL_L' ? 'mmol/L' : 'mg/dL'}
                            </button>
                          ))}
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
                          className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${text.body} hover:bg-white/5`}
                        >
                          Cancel
                        </button>
                      </div>
                      {repeatGlucose && !isNaN(parseFloat(repeatGlucose)) && (() => {
                        const mmol = repeatUnit === 'MG_DL' ? parseFloat(repeatGlucose) / 18 : parseFloat(repeatGlucose);
                        const info = getGlucoseSeverity(mmol);
                        return (
                          <div className="flex items-center gap-2 mt-2">
                            <span className={`text-[10px] ${text.muted}`}>Preview:</span>
                            <span className={`text-xs font-bold ${info.color}`}>{mmol.toFixed(1)} mmol/L</span>
                            {repeatUnit === 'MG_DL' && (
                              <span className={`text-[10px] ${text.muted}`}>({parseFloat(repeatGlucose).toFixed(0)} mg/dL)</span>
                            )}
                            <span className={`text-[10px] px-1.5 py-0.5 rounded ${info.bgColor} ${info.color}`}>{info.label}</span>
                          </div>
                        );
                      })()}
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
