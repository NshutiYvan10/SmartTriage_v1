/* ═══════════════════════════════════════════════════════════════
   Patient Safety Incident Reporting — Module 19

   Two surfaces in one page, split by access:
   - REPORT (every staff role): the blameless-reporting form. Frictionless by
     design — a ward nurse must never be blocked from filing what they saw.
   - REGISTER (oversight: admin / charge nurse / shift lead): the governance
     worklist — the full 5-stage lifecycle per incident (investigate → root
     cause → corrective action → implemented → close), stats, CSV/PDF export.

   Speaks the backend's REAL vocabulary (harm-scale severities, 16 incident
   types, 6 lifecycle statuses). The previous build used enum values that
   didn't exist server-side — most report submissions 400'd, both workflow
   buttons 500'd, and the register stayed empty forever.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  ShieldAlert, Search, Plus, ChevronDown, ChevronUp, Clock,
  AlertTriangle, Loader2, RefreshCw, Eye,
  UserCheck, Shield, AlertCircle, Download, FileText, CheckCircle2, Timer,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { safetyApi, INCIDENT_TYPES, INCIDENT_SEVERITIES, INCIDENT_STATUSES } from '@/api/safety';
import { saveBlob, ApiError } from '@/api/client';
import type { SafetyIncident } from '@/api/safety';
import { format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { useCanSeeAllZones } from '@/hooks/useCanSeeAllZones';
import { ReportIncidentForm } from './ReportIncidentForm';

// ── Severity styling — keys MUST match the backend IncidentSeverity enum
//    (harm scale). Unknown value falls back to the SEVERE look (never
//    downgrade an unrecognised severity to a reassuring colour). ──
const SEVERITY_STYLE: Record<string, { bg: string; border: string; text: string }> = {
  DEATH:         { bg: 'rgba(127,29,29,0.12)', border: '1px solid rgba(127,29,29,0.35)', text: 'text-red-800' },
  SEVERE_HARM:   { bg: 'rgba(239,68,68,0.10)', border: '1px solid rgba(239,68,68,0.25)', text: 'text-red-600' },
  MODERATE_HARM: { bg: 'rgba(245,158,11,0.10)', border: '1px solid rgba(245,158,11,0.25)', text: 'text-amber-600' },
  MILD_HARM:     { bg: 'rgba(234,179,8,0.10)', border: '1px solid rgba(234,179,8,0.25)', text: 'text-yellow-600' },
  NO_HARM:       { bg: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)', text: 'text-blue-600' },
  NEAR_MISS:     { bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)', text: 'text-slate-600' },
};
const SEVERITY_FALLBACK = SEVERITY_STYLE.SEVERE_HARM;

const STATUS_STYLE: Record<string, { bg: string; border: string; text: string }> = {
  REPORTED:                      { bg: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)', text: 'text-red-600' },
  INVESTIGATION_STARTED:         { bg: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)', text: 'text-amber-600' },
  ROOT_CAUSE_IDENTIFIED:         { bg: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)', text: 'text-blue-600' },
  CORRECTIVE_ACTION_PLANNED:     { bg: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)', text: 'text-cyan-600' },
  CORRECTIVE_ACTION_IMPLEMENTED: { bg: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)', text: 'text-emerald-600' },
  CLOSED:                        { bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)', text: 'text-slate-500' },
};
const STATUS_FALLBACK = STATUS_STYLE.REPORTED;

function sevStyle(s: string) { return SEVERITY_STYLE[s] || SEVERITY_FALLBACK; }
function statusStyle(s: string) { return STATUS_STYLE[s] || STATUS_FALLBACK; }
function labelOf(s: string | null | undefined) { return (s || '').replace(/_/g, ' '); }

/** The register's next lifecycle step for an incident (drives the primary button). */
type ActionMode = 'investigate' | 'root-cause' | 'corrective-action' | 'complete-action' | 'close';
function nextAction(i: SafetyIncident): ActionMode | null {
  switch (i.status) {
    case 'REPORTED': return 'investigate';
    case 'INVESTIGATION_STARTED': return 'root-cause';
    case 'ROOT_CAUSE_IDENTIFIED': return 'corrective-action';
    case 'CORRECTIVE_ACTION_PLANNED': return 'complete-action';
    case 'CORRECTIVE_ACTION_IMPLEMENTED': return 'close';
    default: return null;
  }
}
const ACTION_LABEL: Record<ActionMode, string> = {
  'investigate': 'Start Investigation',
  'root-cause': 'Record Root Cause',
  'corrective-action': 'Plan Corrective Action',
  'complete-action': 'Mark Action Implemented',
  'close': 'Close Incident',
};

const isSevere = (i: SafetyIncident) => i.severity === 'SEVERE_HARM' || i.severity === 'DEATH';
const actionOverdue = (i: SafetyIncident) =>
  i.status === 'CORRECTIVE_ACTION_PLANNED' && !!i.correctiveActionDeadline
  && new Date(i.correctiveActionDeadline).getTime() < Date.now();

type FilterStatus = 'ALL' | string;
type FilterSeverity = 'ALL' | string;
type FilterType = 'ALL' | string;

export function SafetyIncidentView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const access = useCanSeeAllZones();

  // ── Data state ──
  const [incidents, setIncidents] = useState<SafetyIncident[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // ── Filters ──
  const [filterStatus, setFilterStatus] = useState<FilterStatus>('ALL');
  const [filterSeverity, setFilterSeverity] = useState<FilterSeverity>('ALL');
  const [filterType, setFilterType] = useState<FilterType>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  // ── Action dialog state (register workflow) ──
  const [actionDialog, setActionDialog] = useState<{ mode: ActionMode; incident: SafetyIncident } | null>(null);
  const [fields, setFields] = useState({
    investigatorName: '', rootCauseAnalysis: '', rootCauseCategory: '',
    correctiveAction: '', correctiveActionOwner: '', correctiveActionDeadline: '',
    preventiveMeasures: '', lessonsLearned: '',
  });
  const [actionSubmitting, setActionSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const canWorkRegister = access.canSeeAllZones;

  const loadIncidents = useCallback(async () => {
    if (!hospitalId || !access.canSeeAllZones) { setLoading(false); return; }
    setLoading(true);
    try {
      const res = await safetyApi.getForHospital(hospitalId, page);
      setIncidents(res.content || []);
      setTotalElements(res.totalElements || 0);
      setError(null);
    } catch (err) {
      console.error('[SafetyIncidentView] Load failed:', err);
      setIncidents([]);
      setError(err instanceof ApiError ? err.message : 'Failed to load the incident register');
    } finally {
      setLoading(false);
    }
  }, [hospitalId, page, access.canSeeAllZones]);

  useEffect(() => { loadIncidents(); }, [loadIncidents]);

  // ── Exports ──
  const [exporting, setExporting] = useState(false);
  const [downloadingPdfId, setDownloadingPdfId] = useState<string | null>(null);

  const handleExportCsv = async () => {
    if (!hospitalId) return;
    setExporting(true);
    try {
      const to = new Date().toISOString();
      const from = new Date(Date.now() - 90 * 24 * 60 * 60 * 1000).toISOString();
      const { blob, filename } = await safetyApi.exportCsv(hospitalId, from, to);
      saveBlob(blob, filename);
    } catch (err) {
      console.error('[SafetyIncidentView] CSV export failed:', err);
    } finally {
      setExporting(false);
    }
  };

  const handleDownloadPdf = async (id: string) => {
    setDownloadingPdfId(id);
    try {
      const { blob, filename } = await safetyApi.downloadPdf(id);
      saveBlob(blob, filename);
    } catch (err) {
      console.error('[SafetyIncidentView] PDF download failed:', err);
    } finally {
      setDownloadingPdfId(null);
    }
  };

  // ── Filtering (client-side, over the loaded page) ──
  const filtered = incidents
    .filter((i) => filterStatus === 'ALL' || i.status === filterStatus)
    .filter((i) => filterSeverity === 'ALL' || i.severity === filterSeverity)
    .filter((i) => filterType === 'ALL' || i.incidentType === filterType)
    .filter((i) => {
      if (!searchQuery.trim()) return true;
      const q = searchQuery.toLowerCase();
      return (
        i.incidentNumber?.toLowerCase().includes(q) ||
        i.description?.toLowerCase().includes(q) ||
        i.reportedByName?.toLowerCase().includes(q) ||
        i.locationInHospital?.toLowerCase().includes(q)
      );
    });

  // ── Stats (real lifecycle values) ──
  const stats = {
    open: incidents.filter((i) => i.status !== 'CLOSED').length,
    investigating: incidents.filter((i) => i.status === 'INVESTIGATION_STARTED' || i.status === 'ROOT_CAUSE_IDENTIFIED').length,
    severeOpen: incidents.filter((i) => isSevere(i) && i.status !== 'CLOSED').length,
    actionOverdue: incidents.filter(actionOverdue).length,
  };

  // ── Register workflow ──
  const openAction = (mode: ActionMode, incident: SafetyIncident) => {
    setFields({
      investigatorName: '', rootCauseAnalysis: '', rootCauseCategory: '',
      correctiveAction: '', correctiveActionOwner: '', correctiveActionDeadline: '',
      preventiveMeasures: '', lessonsLearned: '',
    });
    setActionError(null);
    setActionDialog({ mode, incident });
  };

  const runAction = async () => {
    if (!actionDialog) return;
    const { mode, incident } = actionDialog;
    setActionSubmitting(true);
    setActionError(null);
    try {
      if (mode === 'investigate') {
        await safetyApi.startInvestigation(incident.id, { investigatorName: fields.investigatorName.trim() });
      } else if (mode === 'root-cause') {
        await safetyApi.recordRootCause(incident.id, {
          rootCauseAnalysis: fields.rootCauseAnalysis.trim(),
          rootCauseCategory: fields.rootCauseCategory.trim() || undefined,
        });
      } else if (mode === 'corrective-action') {
        await safetyApi.planCorrectiveAction(incident.id, {
          correctiveAction: fields.correctiveAction.trim(),
          correctiveActionOwner: fields.correctiveActionOwner.trim() || undefined,
          correctiveActionDeadline: fields.correctiveActionDeadline
            ? new Date(fields.correctiveActionDeadline).toISOString() : undefined,
          preventiveMeasures: fields.preventiveMeasures.trim() || undefined,
        });
      } else if (mode === 'complete-action') {
        await safetyApi.completeCorrectiveAction(incident.id);
      } else if (mode === 'close') {
        await safetyApi.close(incident.id, { lessonsLearned: fields.lessonsLearned.trim() });
      }
      setActionDialog(null);
      loadIncidents();
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Action failed');
    } finally {
      setActionSubmitting(false);
    }
  };

  const actionReady = (() => {
    if (!actionDialog) return false;
    switch (actionDialog.mode) {
      case 'investigate': return !!fields.investigatorName.trim();
      case 'root-cause': return !!fields.rootCauseAnalysis.trim();
      case 'corrective-action': return !!fields.correctiveAction.trim();
      case 'complete-action': return true;
      case 'close': return !!fields.lessonsLearned.trim();
    }
  })();

  const totalPages = Math.ceil(totalElements / 20);

  if (access.isLoading) {
    return (
      <div className="min-h-full flex items-center justify-center p-10">
        <div className="w-8 h-8 rounded-full border-2 border-slate-400/40 border-t-slate-500 animate-spin" />
      </div>
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-4">
                <div className="w-10 h-10 rounded-xl bg-red-500/20 flex items-center justify-center">
                  <ShieldAlert className="w-5 h-5 text-red-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Patient Safety Incidents</h1>
                  <p className="text-white/70 text-xs font-medium">
                    {canWorkRegister
                      ? 'Report, investigate and track safety incidents'
                      : 'Blameless incident reporting — every report improves patient safety'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {canWorkRegister && stats.severeOpen > 0 && (
                  <div className="bg-red-500/20 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2 border border-red-400/30">
                    <AlertTriangle className="w-3.5 h-3.5 text-red-300" />
                    <span className="text-xs font-bold text-red-200">{stats.severeOpen} Severe open</span>
                  </div>
                )}
                <button
                  onClick={() => setShowForm((v) => !v)}
                  className="inline-flex items-center gap-2 px-4 py-2.5 text-xs font-bold text-white bg-red-600 hover:bg-red-700 rounded-xl transition-all shadow-md"
                >
                  <Plus className="w-3.5 h-3.5" /> Report Incident
                </button>
                {canWorkRegister && (
                  <>
                    <button
                      onClick={handleExportCsv}
                      disabled={exporting}
                      className="inline-flex items-center gap-2 px-4 py-2.5 text-xs font-bold text-white bg-white/10 border border-white/15 hover:bg-white/15 rounded-xl transition-all disabled:opacity-50"
                      title="Download the last 90 days of incidents as CSV"
                    >
                      {exporting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />} Export CSV
                    </button>
                    <button
                      onClick={loadIncidents}
                      className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                    >
                      <RefreshCw className="w-4 h-4 text-white" />
                    </button>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* ── Report form (EVERY role can file) ── */}
        {showForm && (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <h3 className={`text-sm font-extrabold tracking-tight mb-3 ${text.heading}`}>Report a safety incident</h3>
            <ReportIncidentForm
              hospitalId={hospitalId}
              onReported={() => { if (canWorkRegister) loadIncidents(); }}
              onCancel={() => setShowForm(false)}
            />
          </div>
        )}

        {/* ── Non-oversight staff: reporting is the whole page ── */}
        {!canWorkRegister && !showForm && (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <ShieldAlert className={`w-10 h-10 mx-auto mb-3 ${text.muted}`} />
            <p className={`text-sm font-bold ${text.heading}`}>See something, report it</p>
            <p className={`text-xs mt-1 max-w-md mx-auto ${text.muted}`}>
              Any staff member can report a safety incident — anonymously if preferred. Reports go to
              the hospital's governance register for investigation and follow-up. The register itself
              is managed by the charge nurse / administration.
            </p>
            <button
              onClick={() => setShowForm(true)}
              className="mt-4 inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white bg-red-600 hover:bg-red-700 rounded-xl transition-all shadow-md"
            >
              <Plus className="w-3.5 h-3.5" /> Report an incident
            </button>
          </div>
        )}

        {/* ── Governance register (oversight only) ── */}
        {canWorkRegister && (
          <>
            {/* Summary cards */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {[
                { label: 'Open', value: stats.open, icon: AlertCircle, color: 'text-red-500', bg: 'rgba(239,68,68,0.1)' },
                { label: 'Investigating', value: stats.investigating, icon: Eye, color: 'text-amber-500', bg: 'rgba(245,158,11,0.1)' },
                { label: 'Severe open', value: stats.severeOpen, icon: Shield, color: 'text-rose-500', bg: 'rgba(239,68,68,0.1)' },
                { label: 'Action overdue', value: stats.actionOverdue, icon: Timer, color: 'text-red-600', bg: 'rgba(220,38,38,0.1)' },
              ].map((s) => {
                const Icon = s.icon;
                return (
                  <div key={s.label} className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
                    <div className="flex items-center gap-3">
                      <div className={`w-9 h-9 rounded-xl flex items-center justify-center ${s.value > 0 && (s.label === 'Severe open' || s.label === 'Action overdue') ? 'animate-pulse' : ''}`} style={{ backgroundColor: s.bg }}>
                        <Icon className={`w-4 h-4 ${s.color}`} />
                      </div>
                      <div>
                        <p className={`text-xl font-black ${s.color}`}>{s.value}</p>
                        <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>{s.label}</p>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Filters */}
            <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
              <div className="flex flex-col lg:flex-row lg:items-center gap-3">
                <div className="relative flex-1">
                  <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Search by incident number, description, reporter..."
                    className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
                <select value={filterStatus} onChange={(e) => setFilterStatus(e.target.value)}
                  className={`px-3 py-2.5 rounded-xl text-xs font-bold focus:outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
                  style={glassInner}>
                  <option value="ALL">All Statuses</option>
                  {INCIDENT_STATUSES.map((s) => <option key={s} value={s}>{labelOf(s)}</option>)}
                </select>
                <select value={filterSeverity} onChange={(e) => setFilterSeverity(e.target.value)}
                  className={`px-3 py-2.5 rounded-xl text-xs font-bold focus:outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
                  style={glassInner}>
                  <option value="ALL">All Severities</option>
                  {INCIDENT_SEVERITIES.map((s) => <option key={s} value={s}>{labelOf(s)}</option>)}
                </select>
                <select value={filterType} onChange={(e) => setFilterType(e.target.value)}
                  className={`px-3 py-2.5 rounded-xl text-xs font-bold focus:outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
                  style={glassInner}>
                  <option value="ALL">All Types</option>
                  {INCIDENT_TYPES.map((t) => <option key={t} value={t}>{labelOf(t)}</option>)}
                </select>
              </div>
            </div>

            {error && (
              <div className="rounded-2xl px-4 py-3 flex items-start gap-2 bg-red-500/10 border border-red-500/20 animate-fade-up">
                <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
                <p className="text-[12px] font-semibold text-red-500">{error}</p>
              </div>
            )}

            {/* Incident list */}
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
              </div>
            ) : filtered.length === 0 ? (
              <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
                <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
                <p className={`text-sm font-bold ${text.heading}`}>No incidents match</p>
                <p className={`text-xs mt-1 ${text.muted}`}>
                  {incidents.length === 0 ? 'The register is empty for this page.' : 'Adjust the filters above.'}
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {filtered.map((inc) => {
                  const sev = sevStyle(inc.severity);
                  const st = statusStyle(inc.status);
                  const expanded = expandedId === inc.id;
                  const next = nextAction(inc);
                  const overdue = actionOverdue(inc);
                  return (
                    <div key={inc.id} className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
                      <button
                        type="button"
                        onClick={() => setExpandedId(expanded ? null : inc.id)}
                        className="w-full text-left p-4"
                      >
                        <div className="flex items-center gap-3 flex-wrap">
                          <span className={`text-xs font-black ${text.heading}`}>{inc.incidentNumber}</span>
                          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg"
                            style={{ background: sev.bg, border: sev.border }}>
                            <span className={sev.text}>{labelOf(inc.severity)}</span>
                          </span>
                          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg"
                            style={{ background: st.bg, border: st.border }}>
                            <span className={st.text}>{labelOf(inc.status)}</span>
                          </span>
                          <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>{labelOf(inc.incidentType)}</span>
                          {inc.isAnonymous && (
                            <span className="text-[9px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-slate-500/10 text-slate-500">ANONYMOUS</span>
                          )}
                          {inc.visitNumber && (
                            <span className={`text-[10px] ${text.muted}`}>Visit {inc.visitNumber}</span>
                          )}
                          {overdue && (
                            <span className="text-[10px] font-bold px-2 py-0.5 rounded-lg bg-red-600/15 text-red-600 animate-pulse inline-flex items-center gap-1">
                              <Timer className="w-3 h-3" /> ACTION OVERDUE
                            </span>
                          )}
                          <span className={`ml-auto text-[10px] flex items-center gap-1 ${text.muted}`}>
                            <Clock className="w-3 h-3" />
                            {inc.incidentDateTime ? format(new Date(inc.incidentDateTime), 'dd MMM yyyy HH:mm') : '—'}
                            {expanded ? <ChevronUp className="w-3.5 h-3.5 ml-1" /> : <ChevronDown className="w-3.5 h-3.5 ml-1" />}
                          </span>
                        </div>
                        <p className={`text-xs mt-2 ${text.body} ${expanded ? '' : 'line-clamp-2'}`}>{inc.description}</p>
                      </button>

                      {expanded && (
                        <div className="px-4 pb-4 space-y-3" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}>
                          {/* Detail grid */}
                          <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1.5 pt-3">
                            <Detail label="Reported by" value={`${inc.reportedByName}${inc.reportedByRole ? ` (${labelOf(inc.reportedByRole)})` : ''}${inc.reportedAt ? ` · ${format(new Date(inc.reportedAt), 'dd MMM HH:mm')}` : ''}`} text={text} />
                            <Detail label="Location" value={inc.locationInHospital} text={text} />
                            <Detail label="Patient harmed" value={inc.patientHarmed == null ? null : inc.patientHarmed ? 'Yes' : 'No'} text={text} />
                            <Detail label="Contributing factors" value={inc.contributingFactors} text={text} />
                            <Detail label="Immediate actions" value={inc.immediateActions} text={text} />
                            <Detail label="Investigator" value={inc.investigatorName ? `${inc.investigatorName}${inc.investigationStartedAt ? ` · since ${format(new Date(inc.investigationStartedAt), 'dd MMM HH:mm')}` : ''}` : null} text={text} />
                            <Detail label="Root cause" value={inc.rootCauseAnalysis ? `${inc.rootCauseAnalysis}${inc.rootCauseCategory ? ` [${inc.rootCauseCategory}]` : ''}` : null} text={text} />
                            <Detail label="Corrective action" value={inc.correctiveAction ? `${inc.correctiveAction}${inc.correctiveActionOwner ? ` · owner: ${inc.correctiveActionOwner}` : ''}${inc.correctiveActionDeadline ? ` · due ${format(new Date(inc.correctiveActionDeadline), 'dd MMM yyyy')}` : ''}${inc.correctiveActionCompletedAt ? ` · implemented ${format(new Date(inc.correctiveActionCompletedAt), 'dd MMM')}` : ''}` : null} text={text} />
                            <Detail label="Preventive measures" value={inc.preventiveMeasures} text={text} />
                            <Detail label="Lessons learned" value={inc.lessonsLearned} text={text} />
                            {inc.closedAt && (
                              <Detail label="Closed" value={`${format(new Date(inc.closedAt), 'dd MMM yyyy HH:mm')}${inc.closedByName ? ` by ${inc.closedByName}` : ''}`} text={text} />
                            )}
                          </div>

                          {/* Workflow actions */}
                          <div className="flex items-center gap-2 flex-wrap">
                            {next && (
                              <button
                                onClick={() => openAction(next, inc)}
                                className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-600 hover:bg-cyan-500/20 transition-colors"
                              >
                                <UserCheck className="w-3.5 h-3.5" /> {ACTION_LABEL[next]}
                              </button>
                            )}
                            {inc.status !== 'CLOSED' && next !== 'close' && (
                              <button
                                onClick={() => openAction('close', inc)}
                                title={isSevere(inc) ? 'Severe incidents need a root cause + corrective action before closing' : 'Close with lessons learned'}
                                className={`inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-xl transition-colors ${text.muted} hover:bg-white/5`}
                              >
                                <CheckCircle2 className="w-3.5 h-3.5" /> Close…
                              </button>
                            )}
                            <button
                              onClick={() => handleDownloadPdf(inc.id)}
                              disabled={downloadingPdfId === inc.id}
                              className={`inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-xl transition-colors ${text.muted} hover:bg-white/5`}
                            >
                              {downloadingPdfId === inc.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <FileText className="w-3.5 h-3.5" />}
                              PDF
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="flex items-center justify-center gap-2">
                <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}
                  className={`px-3 py-1.5 text-[11px] font-bold rounded-lg disabled:opacity-40 ${text.body}`} style={glassInner}>
                  Previous
                </button>
                <span className={`text-[11px] ${text.muted}`}>Page {page + 1} of {totalPages}</span>
                <button disabled={page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)}
                  className={`px-3 py-1.5 text-[11px] font-bold rounded-lg disabled:opacity-40 ${text.body}`} style={glassInner}>
                  Next
                </button>
              </div>
            )}
          </>
        )}

        {/* ── Workflow action dialog ── */}
        {actionDialog && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => !actionSubmitting && setActionDialog(null)}>
            <div className="rounded-2xl p-5 w-full max-w-lg" style={glassCard} onClick={(e) => e.stopPropagation()}>
              <h3 className={`text-sm font-extrabold tracking-tight mb-1 ${text.heading}`}>
                {ACTION_LABEL[actionDialog.mode]} — {actionDialog.incident.incidentNumber}
              </h3>
              <p className={`text-[11px] mb-3 ${text.muted}`}>{labelOf(actionDialog.incident.incidentType)} · {labelOf(actionDialog.incident.severity)}</p>

              <div className="space-y-2">
                {actionDialog.mode === 'investigate' && (
                  <input type="text" value={fields.investigatorName}
                    onChange={(e) => setFields((f) => ({ ...f, investigatorName: e.target.value }))}
                    placeholder="Investigator name (required)" style={glassInner}
                    className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`} />
                )}
                {actionDialog.mode === 'root-cause' && (
                  <>
                    <textarea value={fields.rootCauseAnalysis} rows={3}
                      onChange={(e) => setFields((f) => ({ ...f, rootCauseAnalysis: e.target.value }))}
                      placeholder="Root cause analysis (required) — what underlying system factors caused this?"
                      style={glassInner} className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none resize-none ${text.body}`} />
                    <input type="text" value={fields.rootCauseCategory}
                      onChange={(e) => setFields((f) => ({ ...f, rootCauseCategory: e.target.value }))}
                      placeholder="Category (e.g. process, training, equipment, communication)" style={glassInner}
                      className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`} />
                  </>
                )}
                {actionDialog.mode === 'corrective-action' && (
                  <>
                    <textarea value={fields.correctiveAction} rows={2}
                      onChange={(e) => setFields((f) => ({ ...f, correctiveAction: e.target.value }))}
                      placeholder="Corrective action (required)" style={glassInner}
                      className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none resize-none ${text.body}`} />
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                      <input type="text" value={fields.correctiveActionOwner}
                        onChange={(e) => setFields((f) => ({ ...f, correctiveActionOwner: e.target.value }))}
                        placeholder="Action owner" style={glassInner}
                        className={`px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`} />
                      <input type="date" value={fields.correctiveActionDeadline}
                        onChange={(e) => setFields((f) => ({ ...f, correctiveActionDeadline: e.target.value }))}
                        title="Deadline — the follow-up monitor escalates when it lapses"
                        style={glassInner} className={`px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`} />
                    </div>
                    <textarea value={fields.preventiveMeasures} rows={2}
                      onChange={(e) => setFields((f) => ({ ...f, preventiveMeasures: e.target.value }))}
                      placeholder="Preventive measures (optional)" style={glassInner}
                      className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none resize-none ${text.body}`} />
                  </>
                )}
                {actionDialog.mode === 'complete-action' && (
                  <p className={`text-xs ${text.body}`}>
                    Confirm the corrective action has been implemented
                    {actionDialog.incident.correctiveAction ? <>: <span className="font-bold">{actionDialog.incident.correctiveAction}</span></> : '.'}
                  </p>
                )}
                {actionDialog.mode === 'close' && (
                  <>
                    {isSevere(actionDialog.incident) && (!actionDialog.incident.rootCauseAnalysis || !actionDialog.incident.correctiveAction) && (
                      <div className="flex items-start gap-2 rounded-xl px-3 py-2.5 bg-amber-500/10 border border-amber-500/20">
                        <AlertTriangle className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
                        <p className="text-[11px] font-semibold text-amber-600">
                          This is a {labelOf(actionDialog.incident.severity)} incident — the register requires a completed
                          root-cause analysis and a corrective action before it can be closed.
                        </p>
                      </div>
                    )}
                    <textarea value={fields.lessonsLearned} rows={3}
                      onChange={(e) => setFields((f) => ({ ...f, lessonsLearned: e.target.value }))}
                      placeholder="Lessons learned (required) — what should the department take away?"
                      style={glassInner} className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none resize-none ${text.body}`} />
                  </>
                )}
              </div>

              {actionError && (
                <div className="mt-2 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
                  <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
                  <p className="text-[11px] font-semibold text-red-500">{actionError}</p>
                </div>
              )}

              <div className="flex items-center gap-2 mt-4">
                <button onClick={runAction} disabled={!actionReady || actionSubmitting}
                  className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-600 hover:bg-cyan-500/20 transition-colors disabled:opacity-40">
                  {actionSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                  Confirm
                </button>
                <button onClick={() => setActionDialog(null)} disabled={actionSubmitting}
                  className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors hover:bg-white/5 ${text.muted}`}>
                  Cancel
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function Detail({ label, value, text }: { label: string; value: string | null | undefined; text: { muted: string; body: string } }) {
  if (!value) return null;
  return (
    <div>
      <span className={`text-[9px] font-bold uppercase tracking-wider block ${text.muted}`}>{label}</span>
      <span className={`text-[11px] ${text.body}`}>{value}</span>
    </div>
  );
}
