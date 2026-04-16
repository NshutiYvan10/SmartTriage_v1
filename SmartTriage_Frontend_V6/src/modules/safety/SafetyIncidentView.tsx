/* ═══════════════════════════════════════════════════════════════
   Patient Safety Incident Reporting — Module 19
   Report, investigate, and close safety incidents
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  ShieldAlert, Search, Plus, ChevronDown, ChevronUp, Clock,
  CheckCircle, AlertTriangle, Loader2, RefreshCw, X, Eye,
  FileText, UserCheck, Shield, Activity, AlertCircle, Ban,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { safetyApi } from '@/api/safety';
import type { SafetyIncident, ReportIncidentRequest } from '@/api/safety';
import { format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

// ── Constants ──

const INCIDENT_TYPES = [
  'MEDICATION_ERROR', 'FALL', 'WRONG_PATIENT', 'DEVICE_FAILURE',
  'DELAYED_TREATMENT', 'INFECTION_CONTROL_BREACH', 'DOCUMENTATION_ERROR',
  'COMMUNICATION_FAILURE', 'OTHER',
] as const;

const SEVERITIES = ['CRITICAL', 'MAJOR', 'MODERATE', 'MINOR', 'NEAR_MISS'] as const;

const STATUSES = ['REPORTED', 'UNDER_INVESTIGATION', 'INVESTIGATION_COMPLETE', 'CLOSED'] as const;

const SEVERITY_STYLE: Record<string, { bg: string; border: string; text: string; dot: string }> = {
  CRITICAL:  { bg: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)', text: 'text-red-600', dot: 'bg-red-500' },
  MAJOR:     { bg: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)', text: 'text-amber-600', dot: 'bg-amber-500' },
  MODERATE:  { bg: 'rgba(234,179,8,0.08)', border: '1px solid rgba(234,179,8,0.2)', text: 'text-yellow-600', dot: 'bg-yellow-500' },
  MINOR:     { bg: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)', text: 'text-blue-600', dot: 'bg-blue-500' },
  NEAR_MISS: { bg: 'rgba(148,163,184,0.08)', border: '1px solid rgba(148,163,184,0.2)', text: 'text-slate-500', dot: 'bg-slate-400' },
};

const STATUS_STYLE: Record<string, { bg: string; border: string; text: string }> = {
  REPORTED:                { bg: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)', text: 'text-red-600' },
  UNDER_INVESTIGATION:     { bg: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)', text: 'text-amber-600' },
  INVESTIGATION_COMPLETE:  { bg: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)', text: 'text-blue-600' },
  CLOSED:                  { bg: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.2)', text: 'text-emerald-600' },
};

function getSeverityStyle(s: string) { return SEVERITY_STYLE[s] || SEVERITY_STYLE.MINOR; }
function getStatusStyle(s: string) { return STATUS_STYLE[s] || STATUS_STYLE.REPORTED; }
function formatLabel(s: string) { return s.replace(/_/g, ' '); }

type FilterStatus = 'ALL' | typeof STATUSES[number];
type FilterSeverity = 'ALL' | typeof SEVERITIES[number];
type FilterType = 'ALL' | typeof INCIDENT_TYPES[number];

export function SafetyIncidentView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  // ── Data state ──
  const [incidents, setIncidents] = useState<SafetyIncident[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);

  // ── Filters ──
  const [filterStatus, setFilterStatus] = useState<FilterStatus>('ALL');
  const [filterSeverity, setFilterSeverity] = useState<FilterSeverity>('ALL');
  const [filterType, setFilterType] = useState<FilterType>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  // ── Expanded row ──
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // ── Report form ──
  const [showForm, setShowForm] = useState(false);
  const [formSubmitting, setFormSubmitting] = useState(false);
  const [formData, setFormData] = useState({
    incidentType: 'MEDICATION_ERROR' as string,
    severity: 'MODERATE' as string,
    description: '',
    locationInHospital: '',
    contributingFactors: '',
    immediateActions: '',
    isAnonymous: false,
  });

  // ── Action dialogs ──
  const [actionDialog, setActionDialog] = useState<{
    mode: 'investigate' | 'complete' | 'close';
    incidentId: string;
  } | null>(null);
  const [actionFields, setActionFields] = useState({
    investigatorName: '',
    rootCauseAnalysis: '',
    rootCauseCategory: '',
    correctiveAction: '',
    lessonsLearned: '',
  });
  const [actionSubmitting, setActionSubmitting] = useState(false);

  // ── Load data ──
  const loadIncidents = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const res = await safetyApi.getForHospital(hospitalId, page);
      setIncidents(res.content || []);
      setTotalElements(res.totalElements || 0);
    } catch (err) {
      console.error('[SafetyIncidentView] Load failed:', err);
      setIncidents([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, page]);

  useEffect(() => { loadIncidents(); }, [loadIncidents]);

  // ── Filtering ──
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

  // ── Stats ──
  const stats = {
    open: incidents.filter((i) => i.status === 'REPORTED').length,
    investigating: incidents.filter((i) => i.status === 'UNDER_INVESTIGATION').length,
    critical: incidents.filter((i) => i.severity === 'CRITICAL' && i.status !== 'CLOSED').length,
  };

  // ── Submit new incident ──
  const handleSubmitIncident = async () => {
    if (!formData.description.trim()) return;
    setFormSubmitting(true);
    try {
      const req: ReportIncidentRequest = {
        hospitalId,
        incidentType: formData.incidentType,
        severity: formData.severity,
        incidentDateTime: new Date().toISOString(),
        description: formData.description,
        locationInHospital: formData.locationInHospital || undefined,
        contributingFactors: formData.contributingFactors || undefined,
        immediateActions: formData.immediateActions || undefined,
        reportedByName: formData.isAnonymous ? 'Anonymous' : (user?.fullName || user?.username || 'Staff'),
        reportedByRole: user?.role || undefined,
        isAnonymous: formData.isAnonymous,
      };
      await safetyApi.report(req);
      setShowForm(false);
      setFormData({
        incidentType: 'MEDICATION_ERROR', severity: 'MODERATE', description: '',
        locationInHospital: '', contributingFactors: '', immediateActions: '', isAnonymous: false,
      });
      loadIncidents();
    } catch (err) {
      console.error('[SafetyIncidentView] Report failed:', err);
    } finally {
      setFormSubmitting(false);
    }
  };

  // ── Action handlers ──
  const handleAction = async () => {
    if (!actionDialog) return;
    setActionSubmitting(true);
    try {
      if (actionDialog.mode === 'investigate') {
        await safetyApi.startInvestigation(actionDialog.incidentId, {
          investigatorName: actionFields.investigatorName,
        });
      } else if (actionDialog.mode === 'complete') {
        await safetyApi.completeInvestigation(actionDialog.incidentId, {
          rootCauseAnalysis: actionFields.rootCauseAnalysis,
          rootCauseCategory: actionFields.rootCauseCategory,
          correctiveAction: actionFields.correctiveAction,
        });
      } else if (actionDialog.mode === 'close') {
        await safetyApi.close(actionDialog.incidentId, {
          lessonsLearned: actionFields.lessonsLearned,
        });
      }
      setActionDialog(null);
      setActionFields({ investigatorName: '', rootCauseAnalysis: '', rootCauseCategory: '', correctiveAction: '', lessonsLearned: '' });
      loadIncidents();
    } catch (err) {
      console.error('[SafetyIncidentView] Action failed:', err);
    } finally {
      setActionSubmitting(false);
    }
  };

  const openAction = (mode: 'investigate' | 'complete' | 'close', incidentId: string) => {
    setActionFields({ investigatorName: '', rootCauseAnalysis: '', rootCauseCategory: '', correctiveAction: '', lessonsLearned: '' });
    setActionDialog({ mode, incidentId });
  };

  // ── Helpers ──
  const totalPages = Math.ceil(totalElements / 20);

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                  <ShieldAlert className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Patient Safety Incidents</h1>
                  <p className="text-white/70 text-xs font-medium">Report, investigate and track safety incidents</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {stats.critical > 0 && (
                  <div className="bg-red-500/20 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2 border border-red-400/30">
                    <AlertTriangle className="w-3.5 h-3.5 text-red-300" />
                    <span className="text-xs font-bold text-red-200">{stats.critical} Critical</span>
                  </div>
                )}
                <button
                  onClick={() => setShowForm(true)}
                  className="inline-flex items-center gap-2 px-4 py-2.5 text-xs font-bold text-white bg-red-500/80 hover:bg-red-500 rounded-xl transition-all shadow-md"
                >
                  <Plus className="w-3.5 h-3.5" /> Report Incident
                </button>
                <button
                  onClick={loadIncidents}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Summary Cards ── */}
        <div className="grid grid-cols-3 gap-3">
          {[
            { label: 'Open Incidents', value: stats.open, icon: AlertCircle, color: 'text-red-500', bg: 'rgba(239,68,68,0.1)' },
            { label: 'Under Investigation', value: stats.investigating, icon: Eye, color: 'text-amber-500', bg: 'rgba(245,158,11,0.1)' },
            { label: 'Critical', value: stats.critical, icon: Shield, color: 'text-rose-500', bg: 'rgba(239,68,68,0.1)' },
          ].map((s) => {
            const Icon = s.icon;
            return (
              <div key={s.label} className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: s.bg }}>
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

        {/* ── Filters ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
          <div className="flex flex-col lg:flex-row lg:items-center gap-3">
            {/* Search */}
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
            {/* Status filter */}
            <select
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value as FilterStatus)}
              className={`px-3 py-2.5 rounded-xl text-xs font-bold focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                isDark ? 'text-white' : 'text-slate-800'
              }`}
              style={glassInner}
            >
              <option value="ALL">All Statuses</option>
              {STATUSES.map((s) => <option key={s} value={s}>{formatLabel(s)}</option>)}
            </select>
            {/* Severity filter */}
            <select
              value={filterSeverity}
              onChange={(e) => setFilterSeverity(e.target.value as FilterSeverity)}
              className={`px-3 py-2.5 rounded-xl text-xs font-bold focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                isDark ? 'text-white' : 'text-slate-800'
              }`}
              style={glassInner}
            >
              <option value="ALL">All Severities</option>
              {SEVERITIES.map((s) => <option key={s} value={s}>{formatLabel(s)}</option>)}
            </select>
            {/* Type filter */}
            <select
              value={filterType}
              onChange={(e) => setFilterType(e.target.value as FilterType)}
              className={`px-3 py-2.5 rounded-xl text-xs font-bold focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                isDark ? 'text-white' : 'text-slate-800'
              }`}
              style={glassInner}
            >
              <option value="ALL">All Types</option>
              {INCIDENT_TYPES.map((t) => <option key={t} value={t}>{formatLabel(t)}</option>)}
            </select>
          </div>
        </div>

        {/* ── Incidents List ── */}
        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
            <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(34,197,94,0.1)' }}>
              <CheckCircle className="w-8 h-8 text-emerald-400" />
            </div>
            <p className={`text-sm font-bold ${text.heading}`}>No Incidents Found</p>
            <p className={`text-xs font-medium mt-1 ${text.muted}`}>
              No safety incidents match your current filters.
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {filtered.map((incident, idx) => {
              const sevStyle = getSeverityStyle(incident.severity);
              const statStyle = getStatusStyle(incident.status);
              const isExpanded = expandedId === incident.id;

              return (
                <div
                  key={incident.id}
                  className="rounded-2xl overflow-hidden transition-all animate-fade-up hover:-translate-y-0.5"
                  style={{ ...glassCard, animationDelay: `${0.03 + idx * 0.03}s` } as React.CSSProperties}
                >
                  {/* Main row */}
                  <div
                    className="p-5 cursor-pointer"
                    onClick={() => setExpandedId(isExpanded ? null : incident.id)}
                  >
                    <div className="flex items-start gap-4">
                      {/* Severity dot */}
                      <div className="pt-1 flex-shrink-0">
                        <div className={`w-3 h-3 rounded-full ${sevStyle.dot}`} />
                      </div>

                      {/* Content */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2.5 mb-2 flex-wrap">
                          {/* Incident number */}
                          <span className={`text-sm font-bold ${text.heading}`}>
                            {incident.incidentNumber}
                          </span>
                          {/* Type badge */}
                          <span className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${
                            isDark ? 'bg-white/5 text-slate-300 border border-white/10' : 'bg-slate-100 text-slate-600 border border-slate-200/60'
                          }`}>
                            {formatLabel(incident.incidentType)}
                          </span>
                          {/* Severity badge */}
                          <span
                            className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${sevStyle.text}`}
                            style={{ background: sevStyle.bg, border: sevStyle.border }}
                          >
                            {formatLabel(incident.severity)}
                          </span>
                          {/* Status badge */}
                          <span
                            className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${statStyle.text}`}
                            style={{ background: statStyle.bg, border: statStyle.border }}
                          >
                            {formatLabel(incident.status)}
                          </span>
                          {/* Patient harmed */}
                          {incident.patientHarmed && (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider text-red-600 bg-red-500/10 border border-red-500/20">
                              <Ban className="w-3 h-3" /> Patient Harmed
                            </span>
                          )}
                        </div>

                        <p className={`text-[13px] font-medium leading-relaxed line-clamp-2 ${text.body}`}>
                          {incident.description}
                        </p>

                        <div className="flex items-center gap-4 mt-2">
                          {!incident.isAnonymous && (
                            <span className={`text-[11px] font-medium ${text.muted}`}>
                              Reported by: <span className="font-bold">{incident.reportedByName}</span>
                            </span>
                          )}
                          {incident.isAnonymous && (
                            <span className={`text-[11px] font-medium italic ${text.muted}`}>Anonymous report</span>
                          )}
                          <span className={`text-[10px] font-medium flex items-center gap-1 ${text.muted}`}>
                            <Clock className="w-3 h-3" />
                            {incident.incidentDateTime
                              ? format(new Date(incident.incidentDateTime), 'dd MMM yyyy HH:mm')
                              : format(new Date(incident.createdAt), 'dd MMM yyyy HH:mm')}
                          </span>
                          {incident.locationInHospital && (
                            <span className={`text-[10px] font-medium ${text.muted}`}>
                              Location: {incident.locationInHospital}
                            </span>
                          )}
                        </div>
                      </div>

                      {/* Actions + Expand */}
                      <div className="flex items-center gap-2 flex-shrink-0">
                        {incident.status === 'REPORTED' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); openAction('investigate', incident.id); }}
                            className="inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-amber-500 to-amber-600 rounded-xl shadow-md hover:-translate-y-0.5 transition-all"
                          >
                            <Eye className="w-3 h-3" /> Investigate
                          </button>
                        )}
                        {incident.status === 'UNDER_INVESTIGATION' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); openAction('complete', incident.id); }}
                            className="inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-blue-500 to-blue-600 rounded-xl shadow-md hover:-translate-y-0.5 transition-all"
                          >
                            <FileText className="w-3 h-3" /> Complete
                          </button>
                        )}
                        {incident.status === 'INVESTIGATION_COMPLETE' && (
                          <button
                            onClick={(e) => { e.stopPropagation(); openAction('close', incident.id); }}
                            className="inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-emerald-500 to-emerald-600 rounded-xl shadow-md hover:-translate-y-0.5 transition-all"
                          >
                            <CheckCircle className="w-3 h-3" /> Close
                          </button>
                        )}
                        {isExpanded
                          ? <ChevronUp className={`w-4 h-4 ${text.muted}`} />
                          : <ChevronDown className={`w-4 h-4 ${text.muted}`} />}
                      </div>
                    </div>
                  </div>

                  {/* Expanded details */}
                  {isExpanded && (
                    <div className="px-5 pb-5 pt-0 border-t" style={{ borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)' }}>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-4">
                        {incident.contributingFactors && (
                          <DetailSection title="Contributing Factors" content={incident.contributingFactors} isDark={isDark} text={text} glassInner={glassInner} />
                        )}
                        {incident.immediateActions && (
                          <DetailSection title="Immediate Actions Taken" content={incident.immediateActions} isDark={isDark} text={text} glassInner={glassInner} />
                        )}
                        {incident.rootCauseAnalysis && (
                          <DetailSection title="Root Cause Analysis" content={incident.rootCauseAnalysis} isDark={isDark} text={text} glassInner={glassInner} />
                        )}
                        {incident.correctiveAction && (
                          <DetailSection title="Corrective Action" content={incident.correctiveAction} isDark={isDark} text={text} glassInner={glassInner} />
                        )}
                        {incident.lessonsLearned && (
                          <DetailSection title="Lessons Learned" content={incident.lessonsLearned} isDark={isDark} text={text} glassInner={glassInner} />
                        )}
                        {incident.notes && (
                          <DetailSection title="Notes" content={incident.notes} isDark={isDark} text={text} glassInner={glassInner} />
                        )}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* ── Pagination ── */}
        {totalPages > 1 && (
          <div className="flex items-center justify-center gap-2 pt-2">
            <button
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className={`px-4 py-2 text-xs font-bold rounded-xl transition-all disabled:opacity-40 ${
                isDark ? 'text-white bg-white/5 hover:bg-white/10' : 'text-slate-700 bg-white/60 hover:bg-white/80'
              }`}
            >
              Previous
            </button>
            <span className={`text-xs font-bold ${text.muted}`}>
              Page {page + 1} of {totalPages}
            </span>
            <button
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
              className={`px-4 py-2 text-xs font-bold rounded-xl transition-all disabled:opacity-40 ${
                isDark ? 'text-white bg-white/5 hover:bg-white/10' : 'text-slate-700 bg-white/60 hover:bg-white/80'
              }`}
            >
              Next
            </button>
          </div>
        )}
      </div>

      {/* ═══════════════════════════════════════════════════════════════
         Report New Incident Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {showForm && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !formSubmitting && setShowForm(false)} />
          <div className="relative w-full max-w-lg mx-4 rounded-2xl p-6 shadow-2xl animate-scale-in max-h-[85vh] overflow-y-auto" style={glassCard}>
            {/* Header */}
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-red-500/10">
                  <ShieldAlert className="w-5 h-5 text-red-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Report New Incident</h3>
                  <p className={`text-[11px] ${text.muted}`}>Submit a patient safety incident report</p>
                </div>
              </div>
              <button
                onClick={() => !formSubmitting && setShowForm(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-4">
              {/* Type */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Incident Type *</label>
                <select
                  value={formData.incidentType}
                  onChange={(e) => setFormData((f) => ({ ...f, incidentType: e.target.value }))}
                  className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                    isDark ? 'text-white' : 'text-slate-800'
                  }`}
                  style={glassInner}
                >
                  {INCIDENT_TYPES.map((t) => <option key={t} value={t}>{formatLabel(t)}</option>)}
                </select>
              </div>

              {/* Severity */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Severity *</label>
                <select
                  value={formData.severity}
                  onChange={(e) => setFormData((f) => ({ ...f, severity: e.target.value }))}
                  className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                    isDark ? 'text-white' : 'text-slate-800'
                  }`}
                  style={glassInner}
                >
                  {SEVERITIES.map((s) => <option key={s} value={s}>{formatLabel(s)}</option>)}
                </select>
              </div>

              {/* Description */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Description *</label>
                <textarea
                  value={formData.description}
                  onChange={(e) => setFormData((f) => ({ ...f, description: e.target.value }))}
                  placeholder="Describe the incident in detail..."
                  rows={4}
                  className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>

              {/* Location */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Location in Hospital</label>
                <input
                  type="text"
                  value={formData.locationInHospital}
                  onChange={(e) => setFormData((f) => ({ ...f, locationInHospital: e.target.value }))}
                  placeholder="e.g., Ward 3B, Emergency Bay 2"
                  className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>

              {/* Contributing Factors */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Contributing Factors</label>
                <textarea
                  value={formData.contributingFactors}
                  onChange={(e) => setFormData((f) => ({ ...f, contributingFactors: e.target.value }))}
                  placeholder="What factors contributed to this incident?"
                  rows={2}
                  className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>

              {/* Immediate Actions */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Immediate Actions Taken</label>
                <textarea
                  value={formData.immediateActions}
                  onChange={(e) => setFormData((f) => ({ ...f, immediateActions: e.target.value }))}
                  placeholder="What immediate actions were taken?"
                  rows={2}
                  className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>

              {/* Anonymous toggle */}
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => setFormData((f) => ({ ...f, isAnonymous: !f.isAnonymous }))}
                  className={`relative w-10 h-5 rounded-full transition-colors ${
                    formData.isAnonymous ? 'bg-cyan-500' : isDark ? 'bg-white/15' : 'bg-slate-300'
                  }`}
                >
                  <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${
                    formData.isAnonymous ? 'translate-x-5' : 'translate-x-0.5'
                  }`} />
                </button>
                <span className={`text-xs font-bold ${text.body}`}>Report anonymously</span>
              </div>
            </div>

            {/* Submit */}
            <div className="flex items-center justify-end gap-3 mt-6">
              <button
                onClick={() => !formSubmitting && setShowForm(false)}
                disabled={formSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={handleSubmitIncident}
                disabled={formSubmitting || !formData.description.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-gradient-to-r from-red-500 to-red-600 shadow-red-500/20 hover:shadow-red-500/30 hover:-translate-y-0.5"
              >
                {formSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldAlert className="w-3.5 h-3.5" />}
                {formSubmitting ? 'Submitting...' : 'Submit Report'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════
         Action Dialog — Investigate / Complete / Close
         ═══════════════════════════════════════════════════════════════ */}
      {actionDialog && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !actionSubmitting && setActionDialog(null)} />
          <div className="relative w-full max-w-md mx-4 rounded-2xl p-6 shadow-2xl animate-scale-in" style={glassCard}>
            {/* Header */}
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${
                  actionDialog.mode === 'investigate' ? 'bg-amber-500/10' :
                  actionDialog.mode === 'complete' ? 'bg-blue-500/10' : 'bg-emerald-500/10'
                }`}>
                  {actionDialog.mode === 'investigate' && <Eye className="w-5 h-5 text-amber-500" />}
                  {actionDialog.mode === 'complete' && <FileText className="w-5 h-5 text-blue-500" />}
                  {actionDialog.mode === 'close' && <CheckCircle className="w-5 h-5 text-emerald-500" />}
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>
                    {actionDialog.mode === 'investigate' && 'Start Investigation'}
                    {actionDialog.mode === 'complete' && 'Complete Investigation'}
                    {actionDialog.mode === 'close' && 'Close Incident'}
                  </h3>
                  <p className={`text-[11px] ${text.muted}`}>
                    {actionDialog.mode === 'investigate' && 'Assign an investigator to this incident'}
                    {actionDialog.mode === 'complete' && 'Document root cause and corrective actions'}
                    {actionDialog.mode === 'close' && 'Record lessons learned and close the incident'}
                  </p>
                </div>
              </div>
              <button
                onClick={() => !actionSubmitting && setActionDialog(null)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-4">
              {actionDialog.mode === 'investigate' && (
                <div>
                  <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Investigator Name *</label>
                  <input
                    type="text"
                    value={actionFields.investigatorName}
                    onChange={(e) => setActionFields((f) => ({ ...f, investigatorName: e.target.value }))}
                    placeholder="Name of the assigned investigator"
                    autoFocus
                    className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
              )}

              {actionDialog.mode === 'complete' && (
                <>
                  <div>
                    <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Root Cause Analysis *</label>
                    <textarea
                      value={actionFields.rootCauseAnalysis}
                      onChange={(e) => setActionFields((f) => ({ ...f, rootCauseAnalysis: e.target.value }))}
                      placeholder="What was the root cause of this incident?"
                      rows={3}
                      autoFocus
                      className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                        isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                      }`}
                      style={glassInner}
                    />
                  </div>
                  <div>
                    <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Root Cause Category *</label>
                    <input
                      type="text"
                      value={actionFields.rootCauseCategory}
                      onChange={(e) => setActionFields((f) => ({ ...f, rootCauseCategory: e.target.value }))}
                      placeholder="e.g., Human Error, System Failure, Process Gap"
                      className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                        isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                      }`}
                      style={glassInner}
                    />
                  </div>
                  <div>
                    <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Corrective Action *</label>
                    <textarea
                      value={actionFields.correctiveAction}
                      onChange={(e) => setActionFields((f) => ({ ...f, correctiveAction: e.target.value }))}
                      placeholder="What corrective actions will be taken?"
                      rows={3}
                      className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                        isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                      }`}
                      style={glassInner}
                    />
                  </div>
                </>
              )}

              {actionDialog.mode === 'close' && (
                <div>
                  <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Lessons Learned *</label>
                  <textarea
                    value={actionFields.lessonsLearned}
                    onChange={(e) => setActionFields((f) => ({ ...f, lessonsLearned: e.target.value }))}
                    placeholder="What lessons were learned from this incident?"
                    rows={4}
                    autoFocus
                    className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
              )}
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3 mt-6">
              <button
                onClick={() => !actionSubmitting && setActionDialog(null)}
                disabled={actionSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={handleAction}
                disabled={
                  actionSubmitting ||
                  (actionDialog.mode === 'investigate' && !actionFields.investigatorName.trim()) ||
                  (actionDialog.mode === 'complete' && (!actionFields.rootCauseAnalysis.trim() || !actionFields.rootCauseCategory.trim() || !actionFields.correctiveAction.trim())) ||
                  (actionDialog.mode === 'close' && !actionFields.lessonsLearned.trim())
                }
                className={`inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed hover:-translate-y-0.5 ${
                  actionDialog.mode === 'investigate'
                    ? 'bg-gradient-to-r from-amber-500 to-amber-600 shadow-amber-500/20'
                    : actionDialog.mode === 'complete'
                    ? 'bg-gradient-to-r from-blue-500 to-blue-600 shadow-blue-500/20'
                    : 'bg-gradient-to-r from-emerald-500 to-emerald-600 shadow-emerald-500/20'
                }`}
              >
                {actionSubmitting ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : actionDialog.mode === 'investigate' ? (
                  <UserCheck className="w-3.5 h-3.5" />
                ) : actionDialog.mode === 'complete' ? (
                  <FileText className="w-3.5 h-3.5" />
                ) : (
                  <CheckCircle className="w-3.5 h-3.5" />
                )}
                {actionSubmitting
                  ? 'Processing...'
                  : actionDialog.mode === 'investigate' ? 'Start Investigation'
                  : actionDialog.mode === 'complete' ? 'Complete Investigation'
                  : 'Close Incident'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Detail Section Component ──
function DetailSection({ title, content, isDark, text, glassInner }: {
  title: string; content: string; isDark: boolean;
  text: { heading: string; body: string; muted: string; label: string; accent: string };
  glassInner: React.CSSProperties;
}) {
  return (
    <div className="rounded-xl p-4" style={glassInner}>
      <p className={`text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>{title}</p>
      <p className={`text-xs font-medium leading-relaxed ${text.body}`}>{content}</p>
    </div>
  );
}
