/* ═══════════════════════════════════════════════════════════════
   Handover Reports — Module 20
   Generate, view, and acknowledge clinical handover reports
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  ClipboardCheck, ChevronDown, ChevronUp, Clock, CheckCircle,
  Loader2, RefreshCw, X, Plus, Printer, UserCheck,
  FileText, Activity, AlertTriangle, Stethoscope,
  HeartPulse, FlaskConical, Pill, ListTodo, ClipboardList,
  MessageSquare, Timer,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { handoverApi } from '@/api/handover';
import type { HandoverReport } from '@/api/handover';
import { format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { useCanSeeAllZones } from '@/hooks/useCanSeeAllZones';
import { CrossZoneRestrictedPanel } from '@/components/CrossZoneRestrictedPanel';

// ── Constants ──

const REPORT_TYPES = [
  { value: 'SHIFT_HANDOVER', label: 'Shift Handover', color: 'text-cyan-600', bg: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' },
  { value: 'WARD_HANDOVER', label: 'Ward Handover', color: 'text-indigo-600', bg: 'rgba(99,102,241,0.08)', border: '1px solid rgba(99,102,241,0.2)' },
  { value: 'ICU_HANDOVER', label: 'ICU Handover', color: 'text-red-600', bg: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' },
  { value: 'DISCHARGE', label: 'Discharge', color: 'text-emerald-600', bg: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.2)' },
] as const;

function getReportTypeStyle(type: string) {
  return REPORT_TYPES.find((r) => r.value === type) || REPORT_TYPES[0];
}

function formatLabel(s: string) { return s.replace(/_/g, ' '); }

// ── Report section config ──
const REPORT_SECTIONS: { key: keyof HandoverReport; label: string; icon: typeof FileText }[] = [
  { key: 'patientSummary', label: 'Patient Summary', icon: FileText },
  { key: 'presentingComplaint', label: 'Presenting Complaint', icon: Stethoscope },
  { key: 'triageSummary', label: 'Triage Summary', icon: Activity },
  { key: 'vitalSignsTrend', label: 'Vitals Trend', icon: HeartPulse },
  { key: 'investigationsResults', label: 'Investigations & Results', icon: FlaskConical },
  { key: 'diagnosisSummary', label: 'Diagnosis Summary', icon: ClipboardList },
  { key: 'treatmentSummary', label: 'Treatment Summary', icon: Pill },
  { key: 'activeClinicalAlerts', label: 'Active Clinical Alerts', icon: AlertTriangle },
  { key: 'outstandingTasks', label: 'Outstanding Tasks', icon: ListTodo },
  { key: 'planOfCare', label: 'Plan of Care', icon: ClipboardCheck },
  { key: 'edTimeline', label: 'ED Timeline', icon: Timer },
];

export function HandoverView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const access = useCanSeeAllZones();

  // ── Data state ──
  const [reports, setReports] = useState<HandoverReport[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);

  // ── Expanded row ──
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // ── Generate form ──
  const [showGenerateForm, setShowGenerateForm] = useState(false);
  const [generateVisitId, setGenerateVisitId] = useState('');
  const [generateReportType, setGenerateReportType] = useState('SHIFT_HANDOVER');
  const [generating, setGenerating] = useState(false);

  // ── Acknowledge dialog ──
  const [ackDialog, setAckDialog] = useState<{ reportId: string } | null>(null);
  const [ackName, setAckName] = useState('');
  const [ackSubmitting, setAckSubmitting] = useState(false);

  // ── Filter ──
  const [filterType, setFilterType] = useState<string>('ALL');

  // ── Load data ──
  const loadReports = useCallback(async () => {
    if (!hospitalId || !access.canSeeAllZones) return;
    setLoading(true);
    try {
      // B6 — /shift returns an array of this shift's reports (not a Page).
      const res = await handoverApi.getForHospital(hospitalId);
      setReports(res || []);
      setTotalElements((res || []).length);
    } catch (err) {
      console.error('[HandoverView] Load failed:', err);
      setReports([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, access.canSeeAllZones]);

  useEffect(() => { loadReports(); }, [loadReports]);

  // ── Filtering ──
  const filtered = reports.filter((r) => {
    if (filterType !== 'ALL' && r.reportType !== filterType) return false;
    return true;
  });

  // ── Stats ──
  const stats = {
    total: reports.length,
    pending: reports.filter((r) => !r.isAcknowledged).length,
    acknowledged: reports.filter((r) => r.isAcknowledged).length,
  };

  // ── Generate handover ──
  const handleGenerate = async () => {
    if (!generateVisitId.trim()) return;
    setGenerating(true);
    try {
      await handoverApi.generate(generateVisitId.trim(), generateReportType);
      setShowGenerateForm(false);
      setGenerateVisitId('');
      setGenerateReportType('SHIFT_HANDOVER');
      loadReports();
    } catch (err) {
      console.error('[HandoverView] Generate failed:', err);
    } finally {
      setGenerating(false);
    }
  };

  // ── Acknowledge ──
  const handleAcknowledge = async () => {
    if (!ackDialog || !ackName.trim()) return;
    setAckSubmitting(true);
    try {
      await handoverApi.acknowledge(ackDialog.reportId, ackName.trim());
      setAckDialog(null);
      setAckName('');
      loadReports();
    } catch (err) {
      console.error('[HandoverView] Acknowledge failed:', err);
    } finally {
      setAckSubmitting(false);
    }
  };

  // ── Print hint ──
  const handlePrint = () => {
    window.print();
  };

  const totalPages = Math.ceil(totalElements / 20);

  // Don't render the restriction panel until the shift fetch resolves —
  // otherwise the "lead/admin only" card flashes for every user on first paint.
  if (access.isLoading) {
    return (
      <div className="min-h-full flex items-center justify-center p-10">
        <div className="w-8 h-8 rounded-full border-2 border-slate-400/40 border-t-slate-500 animate-spin" />
      </div>
    );
  }

  if (!access.canSeeAllZones) {
    return (
      <CrossZoneRestrictedPanel
        pageTitle="Handover Reports"
        zone={access.zone ?? null}
        reason={access.reason === 'OFF_SHIFT' ? 'OFF_SHIFT' : 'ZONE_SCOPED'}
      />
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                  <ClipboardCheck className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Handover Reports</h1>
                  <p className="text-white/70 text-xs font-medium">Clinical handover and discharge documentation</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {stats.pending > 0 && (
                  <div className="bg-amber-500/20 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2 border border-amber-400/30">
                    <Clock className="w-3.5 h-3.5 text-amber-300" />
                    <span className="text-xs font-bold text-amber-200">{stats.pending} Pending</span>
                  </div>
                )}
                <button
                  onClick={handlePrint}
                  className="inline-flex items-center gap-2 px-3 py-2.5 text-xs font-bold text-white/80 bg-white/10 hover:bg-white/20 rounded-xl transition-all"
                >
                  <Printer className="w-3.5 h-3.5" /> Print
                </button>
                <button
                  onClick={() => setShowGenerateForm(true)}
                  className="inline-flex items-center gap-2 px-4 py-2.5 text-xs font-bold text-white bg-cyan-500/80 hover:bg-cyan-500 rounded-xl transition-all shadow-md"
                >
                  <Plus className="w-3.5 h-3.5" /> Generate Handover
                </button>
                <button
                  onClick={loadReports}
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
            { label: 'Total Reports', value: stats.total, icon: FileText, color: 'text-cyan-500', bg: 'rgba(6,182,212,0.1)' },
            { label: 'Pending Acknowledgement', value: stats.pending, icon: Clock, color: 'text-amber-500', bg: 'rgba(245,158,11,0.1)' },
            { label: 'Acknowledged', value: stats.acknowledged, icon: CheckCircle, color: 'text-emerald-500', bg: 'rgba(34,197,94,0.1)' },
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
          <div className="flex items-center gap-2">
            <button
              onClick={() => setFilterType('ALL')}
              className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold rounded-lg transition-all ${
                filterType === 'ALL'
                  ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                  : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
              }`}
            >
              All Types
            </button>
            {REPORT_TYPES.map((rt) => (
              <button
                key={rt.value}
                onClick={() => setFilterType(rt.value)}
                className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold rounded-lg transition-all ${
                  filterType === rt.value
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                }`}
              >
                {rt.label}
              </button>
            ))}
          </div>
        </div>

        {/* ── Reports List ── */}
        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
            <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(6,182,212,0.1)' }}>
              <ClipboardCheck className="w-8 h-8 text-cyan-400" />
            </div>
            <p className={`text-sm font-bold ${text.heading}`}>No Handover Reports</p>
            <p className={`text-xs font-medium mt-1 ${text.muted}`}>
              No handover reports match your current filters. Generate a new one to get started.
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {filtered.map((report, idx) => {
              const typeStyle = getReportTypeStyle(report.reportType);
              const isExpanded = expandedId === report.id;

              return (
                <div
                  key={report.id}
                  className="rounded-2xl overflow-hidden transition-all animate-fade-up hover:-translate-y-0.5"
                  style={{ ...glassCard, animationDelay: `${0.03 + idx * 0.03}s` } as React.CSSProperties}
                >
                  {/* Main row */}
                  <div
                    className="p-5 cursor-pointer"
                    onClick={() => setExpandedId(isExpanded ? null : report.id)}
                  >
                    <div className="flex items-center gap-4">
                      {/* Acknowledged status indicator */}
                      <div className="flex-shrink-0">
                        {report.isAcknowledged ? (
                          <div className="w-10 h-10 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(34,197,94,0.1)' }}>
                            <CheckCircle className="w-5 h-5 text-emerald-500" />
                          </div>
                        ) : (
                          <div className="w-10 h-10 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(245,158,11,0.1)' }}>
                            <Clock className="w-5 h-5 text-amber-500" />
                          </div>
                        )}
                      </div>

                      {/* Content */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2.5 mb-1.5 flex-wrap">
                          {/* Report type badge */}
                          <span
                            className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${typeStyle.color}`}
                            style={{ background: typeStyle.bg, border: typeStyle.border }}
                          >
                            {typeStyle.label}
                          </span>
                          {/* Acknowledged badge */}
                          {report.isAcknowledged ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider text-emerald-600"
                              style={{ background: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.2)' }}
                            >
                              <CheckCircle className="w-3 h-3" /> Acknowledged
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider text-amber-600"
                              style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
                            >
                              <Clock className="w-3 h-3" /> Pending
                            </span>
                          )}
                        </div>

                        <div className="flex items-center gap-4 mt-1">
                          <span className={`text-[11px] font-medium ${text.body}`}>
                            Generated by: <span className="font-bold">{report.generatedByName}</span>
                          </span>
                          {report.receivedByName && (
                            <span className={`text-[11px] font-medium ${text.body}`}>
                              Received by: <span className="font-bold">{report.receivedByName}</span>
                            </span>
                          )}
                          <span className={`text-[10px] font-medium flex items-center gap-1 ${text.muted}`}>
                            <Clock className="w-3 h-3" />
                            {report.generatedAt
                              ? format(new Date(report.generatedAt), 'dd MMM yyyy HH:mm')
                              : format(new Date(report.createdAt), 'dd MMM yyyy HH:mm')}
                          </span>
                        </div>

                        {/* Patient summary preview */}
                        {report.patientSummary && !isExpanded && (
                          <p className={`text-xs font-medium mt-2 line-clamp-1 ${text.muted}`}>
                            {report.patientSummary}
                          </p>
                        )}
                      </div>

                      {/* Actions */}
                      <div className="flex items-center gap-2 flex-shrink-0">
                        {!report.isAcknowledged && (
                          <button
                            onClick={(e) => { e.stopPropagation(); setAckDialog({ reportId: report.id }); setAckName(''); }}
                            className="inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold text-white bg-gradient-to-r from-emerald-500 to-emerald-600 rounded-xl shadow-md hover:-translate-y-0.5 transition-all"
                          >
                            <UserCheck className="w-3 h-3" /> Acknowledge
                          </button>
                        )}
                        {isExpanded
                          ? <ChevronUp className={`w-4 h-4 ${text.muted}`} />
                          : <ChevronDown className={`w-4 h-4 ${text.muted}`} />}
                      </div>
                    </div>
                  </div>

                  {/* Expanded sections */}
                  {isExpanded && (
                    <div className="px-5 pb-5 pt-0 border-t print:break-inside-avoid" style={{ borderColor: isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.05)' }}>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mt-4">
                        {REPORT_SECTIONS.map((section) => {
                          const value = report[section.key];
                          if (!value || typeof value !== 'string') return null;
                          const Icon = section.icon;
                          return (
                            <div key={section.key} className="rounded-xl p-4" style={glassInner}>
                              <div className="flex items-center gap-2 mb-2">
                                <Icon className={`w-3.5 h-3.5 ${text.accent}`} />
                                <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>{section.label}</p>
                              </div>
                              <p className={`text-xs font-medium leading-relaxed whitespace-pre-wrap ${text.body}`}>{value}</p>
                            </div>
                          );
                        })}
                      </div>

                      {/* Notes */}
                      {report.notes && (
                        <div className="rounded-xl p-4 mt-3" style={glassInner}>
                          <div className="flex items-center gap-2 mb-2">
                            <MessageSquare className={`w-3.5 h-3.5 ${text.accent}`} />
                            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Notes</p>
                          </div>
                          <p className={`text-xs font-medium leading-relaxed whitespace-pre-wrap ${text.body}`}>{report.notes}</p>
                        </div>
                      )}
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

        {/* ── Print-friendly hint ── */}
        <div className="rounded-2xl p-3 animate-fade-up print:hidden" style={glassCard}>
          <div className="flex items-center gap-2.5">
            <Printer className={`w-4 h-4 ${text.muted}`} />
            <p className={`text-[11px] font-medium ${text.muted}`}>
              Expand a report and use the Print button or Ctrl+P for a print-friendly view of the handover details.
            </p>
          </div>
        </div>
      </div>

      {/* ═══════════════════════════════════════════════════════════════
         Generate Handover Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {showGenerateForm && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !generating && setShowGenerateForm(false)} />
          <div className="relative w-full max-w-md mx-4 rounded-2xl p-6 shadow-2xl animate-scale-in" style={glassCard}>
            {/* Header */}
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-cyan-500/10">
                  <ClipboardCheck className="w-5 h-5 text-cyan-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Generate Handover Report</h3>
                  <p className={`text-[11px] ${text.muted}`}>Create a new clinical handover document</p>
                </div>
              </div>
              <button
                onClick={() => !generating && setShowGenerateForm(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-4">
              {/* Visit ID */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Visit ID *</label>
                <input
                  type="text"
                  value={generateVisitId}
                  onChange={(e) => setGenerateVisitId(e.target.value)}
                  placeholder="Enter the patient visit ID"
                  autoFocus
                  className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>

              {/* Report Type */}
              <div>
                <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>Report Type *</label>
                <div className="grid grid-cols-2 gap-2">
                  {REPORT_TYPES.map((rt) => (
                    <button
                      key={rt.value}
                      onClick={() => setGenerateReportType(rt.value)}
                      className={`px-3 py-2.5 rounded-xl text-xs font-bold transition-all text-left ${
                        generateReportType === rt.value
                          ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                          : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-600 hover:bg-white/60'
                      }`}
                      style={generateReportType !== rt.value ? glassInner : undefined}
                    >
                      {rt.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3 mt-6">
              <button
                onClick={() => !generating && setShowGenerateForm(false)}
                disabled={generating}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={handleGenerate}
                disabled={generating || !generateVisitId.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-gradient-to-r from-cyan-500 to-cyan-600 shadow-cyan-500/20 hover:shadow-cyan-500/30 hover:-translate-y-0.5"
              >
                {generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ClipboardCheck className="w-3.5 h-3.5" />}
                {generating ? 'Generating...' : 'Generate Report'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ═══════════════════════════════════════════════════════════════
         Acknowledge Dialog
         ═══════════════════════════════════════════════════════════════ */}
      {ackDialog && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !ackSubmitting && setAckDialog(null)} />
          <div className="relative w-full max-w-md mx-4 rounded-2xl p-6 shadow-2xl animate-scale-in" style={glassCard}>
            {/* Header */}
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-emerald-500/10">
                  <UserCheck className="w-5 h-5 text-emerald-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>Acknowledge Handover</h3>
                  <p className={`text-[11px] ${text.muted}`}>Confirm receipt of this handover report</p>
                </div>
              </div>
              <button
                onClick={() => !ackSubmitting && setAckDialog(null)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div>
              <label className={`block text-[11px] font-bold uppercase tracking-wider mb-1.5 ${text.muted}`}>
                <UserCheck className="w-3 h-3 inline mr-1" />
                Receiving Clinician Name *
              </label>
              <input
                type="text"
                value={ackName}
                onChange={(e) => setAckName(e.target.value)}
                placeholder="Enter your full name"
                autoFocus
                className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3 mt-6">
              <button
                onClick={() => !ackSubmitting && setAckDialog(null)}
                disabled={ackSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={handleAcknowledge}
                disabled={ackSubmitting || !ackName.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed bg-gradient-to-r from-emerald-500 to-emerald-600 shadow-emerald-500/20 hover:shadow-emerald-500/30 hover:-translate-y-0.5"
              >
                {ackSubmitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle className="w-3.5 h-3.5" />}
                {ackSubmitting ? 'Acknowledging...' : 'Acknowledge'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
