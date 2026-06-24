/* ===================================================================
   MoH Reporting — Module 23
   Ministry of Health de-identified aggregate reporting with compliance
   =================================================================== */

import { useState, useEffect, useCallback } from 'react';
import {
  FileBarChart, Plus, Send, CheckCircle2, XCircle, ChevronDown,
  ChevronRight, Loader2, RefreshCw, Calendar, Clock, Users,
  Activity, Heart, Baby, Bug, Building2, ArrowRight, AlertTriangle,
  BarChart3, ShieldCheck, Download, Globe,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { mohReportApi } from '@/api/mohreport';
import type { MohReport } from '@/api/mohreport';
import { hospitalApi } from '@/api/hospitals';
import type { HospitalResponse } from '@/api/types';
import { format } from 'date-fns';

/** Sentinel scope value for the national (cross-hospital) rollup. */
const NATIONAL = 'NATIONAL';

/* -- Constants ---------------------------------------------------- */

/* Values MUST match the backend MohReportType enum exactly — a mismatch makes
   the Generate request fail to deserialize (HTTP 400). */
const REPORT_TYPES = [
  { value: 'DAILY_SUMMARY', label: 'Daily Summary' },
  { value: 'WEEKLY_SURVEILLANCE', label: 'Weekly Surveillance' },
  { value: 'MONTHLY_STATISTICS', label: 'Monthly Statistics' },
  { value: 'QUARTERLY_REVIEW', label: 'Quarterly Review' },
  { value: 'ANNUAL_REPORT', label: 'Annual Report' },
  { value: 'OUTBREAK_NOTIFICATION', label: 'Outbreak Notification' },
  { value: 'MORTALITY_REVIEW', label: 'Mortality Review' },
] as const;

const REPORT_TYPE_CONFIG: Record<string, { color: string; bg: string }> = {
  DAILY_SUMMARY:         { color: 'text-blue-600',    bg: 'rgba(59,130,246,0.10)' },
  WEEKLY_SURVEILLANCE:   { color: 'text-cyan-600',    bg: 'rgba(6,182,212,0.10)' },
  MONTHLY_STATISTICS:    { color: 'text-indigo-600',  bg: 'rgba(99,102,241,0.10)' },
  QUARTERLY_REVIEW:      { color: 'text-violet-600',  bg: 'rgba(139,92,246,0.10)' },
  ANNUAL_REPORT:         { color: 'text-emerald-600', bg: 'rgba(34,197,94,0.10)' },
  OUTBREAK_NOTIFICATION: { color: 'text-amber-600',   bg: 'rgba(245,158,11,0.10)' },
  MORTALITY_REVIEW:      { color: 'text-red-600',     bg: 'rgba(239,68,68,0.10)' },
};

const STATUS_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  DRAFT:     { color: 'text-slate-600',   bg: 'rgba(100,116,139,0.10)', label: 'Draft' },
  SUBMITTED: { color: 'text-blue-600',    bg: 'rgba(59,130,246,0.10)',  label: 'Submitted' },
  ACCEPTED:  { color: 'text-emerald-600', bg: 'rgba(34,197,94,0.10)',   label: 'Accepted' },
  REJECTED:  { color: 'text-red-600',     bg: 'rgba(239,68,68,0.10)',   label: 'Rejected' },
};

const STATUS_PIPELINE = ['DRAFT', 'SUBMITTED', 'ACCEPTED'];

function getTypeLabel(type: string): string {
  return REPORT_TYPES.find((t) => t.value === type)?.label || type;
}

/* ================================================================= */

export function MohReportView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = user?.role === 'SUPER_ADMIN';
  const userHospitalId = user?.hospitalId || '';

  /* Scope of the page: 'NATIONAL' (cross-hospital rollup) or a single hospitalId.
     SUPER_ADMIN may switch between National and any hospital; everyone else is
     pinned to their own hospital. */
  const [selectedScope, setSelectedScope] = useState<string>(NATIONAL);
  const scope = isSuperAdmin ? selectedScope : userHospitalId;
  const isNational = scope === NATIONAL;
  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);

  /* -- State ------------------------------------------------------ */
  const [reports, setReports] = useState<MohReport[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);

  /* Generate form */
  const [formType, setFormType] = useState('MONTHLY_STATISTICS');
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');

  /* Reject modal */
  const [rejectTarget, setRejectTarget] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  /* -- Data loading ----------------------------------------------- */
  const loadReports = useCallback(async () => {
    if (!scope) return; // non-super-admin whose hospital isn't loaded yet
    setLoading(true);
    try {
      const res = scope === NATIONAL
        ? await mohReportApi.getNational(page)
        : await mohReportApi.getForHospital(scope, page);
      setReports(res.content);
      setTotalElements(res.totalElements);
    } catch {
      /* network error — keep existing data */
    } finally {
      setLoading(false);
    }
  }, [scope, page]);

  useEffect(() => {
    loadReports();
  }, [loadReports]);

  /* SUPER_ADMIN: load the hospital list so they can scope to a single hospital. */
  useEffect(() => {
    if (!isSuperAdmin) return;
    hospitalApi.getAll(0, 200)
      .then((res) => setHospitals(res.content.filter((h) => h.active)))
      .catch(() => { /* keep National-only if the list fails */ });
  }, [isSuperAdmin]);

  /* Reset to page 0 whenever the scope changes. */
  useEffect(() => {
    setPage(0);
  }, [scope]);

  /* -- Actions ---------------------------------------------------- */
  const handleGenerate = useCallback(async () => {
    if (!scope || !periodStart || !periodEnd) return;
    setActionLoading('generate');
    try {
      if (scope === NATIONAL) {
        await mohReportApi.generateNational({ reportType: formType, periodStart, periodEnd });
      } else {
        await mohReportApi.generate({ hospitalId: scope, reportType: formType, periodStart, periodEnd });
      }
      setShowForm(false);
      setPeriodStart('');
      setPeriodEnd('');
      await loadReports();
    } catch {
      /* handled */
    } finally {
      setActionLoading(null);
    }
  }, [scope, formType, periodStart, periodEnd, loadReports]);

  const handleSubmit = useCallback(async (id: string) => {
    setActionLoading(id);
    try {
      await mohReportApi.submit(id);
      await loadReports();
    } catch { /* */ } finally { setActionLoading(null); }
  }, [loadReports]);

  const handleAccept = useCallback(async (id: string) => {
    setActionLoading(id);
    try {
      await mohReportApi.accept(id);
      await loadReports();
    } catch { /* */ } finally { setActionLoading(null); }
  }, [loadReports]);

  const handleReject = useCallback(async () => {
    if (!rejectTarget || !rejectReason) return;
    setActionLoading(rejectTarget);
    try {
      await mohReportApi.reject(rejectTarget, rejectReason);
      setRejectTarget(null);
      setRejectReason('');
      await loadReports();
    } catch { /* */ } finally { setActionLoading(null); }
  }, [rejectTarget, rejectReason, loadReports]);

  const handleDownloadPdf = useCallback(async (id: string) => {
    setActionLoading(`pdf-${id}`);
    try {
      await mohReportApi.downloadPdf(id);
    } catch { /* handled */ } finally { setActionLoading(null); }
  }, []);

  /* -- Input styling helper --------------------------------------- */
  const inputStyle = {
    background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.7)',
    border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.5)',
    boxShadow: isDark ? '0 1px 4px rgba(0,0,0,0.2)' : '0 1px 4px rgba(0,0,0,0.03), inset 0 1px 0 rgba(255,255,255,0.8)',
  };

  const totalPages = Math.ceil(totalElements / 20);

  /* =============================================================== */
  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* -- Header Banner ---------------------------------------- */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-gradient-to-br from-blue-500/30 to-indigo-500/30 rounded-2xl flex items-center justify-center shadow-lg border border-blue-400/20">
                  <FileBarChart className="w-6 h-6 text-blue-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Ministry of Health Reports</h1>
                  <p className="text-white/70 text-xs font-medium">De-identified aggregate reporting for Rwanda MoH compliance</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <button
                  onClick={() => setShowForm(!showForm)}
                  className="flex items-center gap-2 px-4 py-2 bg-white/15 hover:bg-white/25 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all duration-300 border border-white/10"
                >
                  <Plus className="w-3.5 h-3.5" />
                  Generate Report
                </button>
                <button
                  onClick={loadReports}
                  disabled={loading}
                  className="flex items-center gap-2 px-3 py-2 bg-white/10 hover:bg-white/20 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all duration-300 border border-white/10"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
                </button>
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <div className="w-2 h-2 bg-blue-400 rounded-full animate-pulse" />
                  <span className="text-xs font-semibold text-white/90">Module 23</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* -- Scope selector (SUPER_ADMIN only) -------------------- */}
        {isSuperAdmin && (
          <div className="rounded-2xl p-4 animate-fade-up flex items-center gap-3 flex-wrap" style={glassCard}>
            <div
              className="w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0"
              style={{ backgroundColor: isNational ? 'rgba(16,185,129,0.12)' : 'rgba(59,130,246,0.12)' }}
            >
              {isNational
                ? <Globe className="w-5 h-5 text-emerald-500" />
                : <Building2 className="w-5 h-5 text-blue-500" />}
            </div>
            <div className="flex-1 min-w-[220px]">
              <label className={`block text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Reporting Scope</label>
              <select
                value={selectedScope}
                onChange={(e) => setSelectedScope(e.target.value)}
                className={`w-full px-3 py-2 rounded-xl text-sm ${text.heading} focus:outline-none focus:ring-2 focus:ring-blue-500/20 transition-all duration-300`}
                style={inputStyle}
              >
                <option value={NATIONAL}>National — all hospitals (de-identified rollup)</option>
                {hospitals.map((h) => (
                  <option key={h.id} value={h.id}>
                    {h.name}{h.hospitalCode ? ` (${h.hospitalCode})` : ''}
                  </option>
                ))}
              </select>
            </div>
            <p className={`text-[11px] ${text.muted} max-w-xs`}>
              {isNational
                ? 'Aggregated across every active hospital. Generate, submit, and review national MoH returns.'
                : 'Single-hospital reports for the selected facility.'}
            </p>
          </div>
        )}

        {/* -- Generate Form ---------------------------------------- */}
        {showForm && (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <div className="flex items-center gap-2 mb-4">
              <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(59,130,246,0.12)' }}>
                <Plus className="w-4 h-4 text-blue-500" />
              </div>
              <h3 className={`text-sm font-extrabold ${text.heading}`}>Generate New Report</h3>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              {/* Report Type */}
              <div>
                <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Report Type</label>
                <select
                  value={formType}
                  onChange={(e) => setFormType(e.target.value)}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} focus:outline-none focus:ring-2 focus:ring-blue-500/20 transition-all duration-300`}
                  style={inputStyle}
                >
                  {REPORT_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>{t.label}</option>
                  ))}
                </select>
              </div>

              {/* Period Start */}
              <div>
                <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Period Start</label>
                <input
                  type="date"
                  value={periodStart}
                  onChange={(e) => setPeriodStart(e.target.value)}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} focus:outline-none focus:ring-2 focus:ring-blue-500/20 transition-all duration-300`}
                  style={inputStyle}
                />
              </div>

              {/* Period End */}
              <div>
                <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Period End</label>
                <input
                  type="date"
                  value={periodEnd}
                  onChange={(e) => setPeriodEnd(e.target.value)}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} focus:outline-none focus:ring-2 focus:ring-blue-500/20 transition-all duration-300`}
                  style={inputStyle}
                />
              </div>
            </div>

            <div className="flex items-center justify-end gap-3 mt-4 pt-3 border-t border-gray-100/30">
              <button
                onClick={() => setShowForm(false)}
                className={`px-4 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
              >
                Cancel
              </button>
              <button
                onClick={handleGenerate}
                disabled={!periodStart || !periodEnd || actionLoading === 'generate'}
                className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {actionLoading === 'generate' ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : (
                  <FileBarChart className="w-3.5 h-3.5" />
                )}
                Generate
              </button>
            </div>
          </div>
        )}

        {/* -- MoH Compliance Indicators ----------------------------- */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 animate-fade-up" style={{ animationDelay: '0.1s' } as any}>
          {[
            { icon: Download, label: 'PDF Export', value: 'Available', color: 'text-blue-500', bg: 'rgba(59,130,246,0.10)' },
            { icon: ShieldCheck, label: 'Aggregate Only', value: 'No PII', color: 'text-emerald-500', bg: 'rgba(34,197,94,0.10)' },
            { icon: AlertTriangle, label: 'De-identified', value: 'Enforced', color: 'text-amber-500', bg: 'rgba(245,158,11,0.10)' },
            { icon: BarChart3, label: 'Reports This Period', value: String(totalElements), color: 'text-indigo-500', bg: 'rgba(99,102,241,0.10)' },
          ].map((ind) => (
            <div key={ind.label} className="rounded-2xl p-4" style={glassCard}>
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: ind.bg }}>
                  <ind.icon className={`w-5 h-5 ${ind.color}`} />
                </div>
                <div>
                  <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider`}>{ind.label}</p>
                  <p className={`text-sm font-extrabold ${text.heading}`}>{ind.value}</p>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* -- Status Pipeline Legend -------------------------------- */}
        <div className="rounded-2xl p-4 animate-fade-up" style={{ ...glassCard, animationDelay: '0.12s' } as any}>
          <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-3`}>Report Lifecycle</p>
          <div className="flex items-center gap-2 flex-wrap">
            {STATUS_PIPELINE.map((st, i) => {
              const cfg = STATUS_CONFIG[st];
              return (
                <div key={st} className="flex items-center gap-2">
                  <span className={`text-[10px] font-bold ${cfg.color} px-2.5 py-1 rounded-lg`} style={{ background: cfg.bg }}>
                    {cfg.label}
                  </span>
                  {i < STATUS_PIPELINE.length - 1 && <ArrowRight className={`w-3 h-3 ${text.muted}`} />}
                </div>
              );
            })}
            <span className={`text-[10px] ${text.muted} mx-1`}>or</span>
            <span className="text-[10px] font-bold text-red-600 px-2.5 py-1 rounded-lg" style={{ background: 'rgba(239,68,68,0.10)' }}>
              Rejected
            </span>
          </div>
        </div>

        {/* -- Report List ------------------------------------------ */}
        <div className="space-y-2">
          <div className="flex items-center justify-between px-1 animate-fade-up" style={{ animationDelay: '0.18s' } as any}>
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(59,130,246,0.12)' }}>
                <FileBarChart className="w-3.5 h-3.5 text-blue-500" />
              </div>
              <div>
                <h3 className={`text-sm font-extrabold ${text.heading}`}>Generated Reports</h3>
                <p className={`text-[10px] ${text.muted} font-medium`}>{totalElements} total reports</p>
              </div>
            </div>
          </div>

          {loading && reports.length === 0 ? (
            <div className="rounded-2xl p-12 text-center" style={glassCard}>
              <Loader2 className={`w-8 h-8 ${text.muted} animate-spin mx-auto mb-3`} />
              <p className={`text-sm font-bold ${text.body}`}>Loading reports...</p>
            </div>
          ) : reports.length === 0 ? (
            <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                <FileBarChart className="w-8 h-8 text-slate-300" />
              </div>
              <p className={`text-sm font-bold ${text.heading}`}>No Reports Generated</p>
              <p className={`text-xs ${text.muted} mt-1`}>Generate a new MoH report using the button above</p>
            </div>
          ) : (
            <div className="space-y-2">
              {reports.map((report, idx) => {
                const isExpanded = expandedId === report.id;
                const typeCfg = REPORT_TYPE_CONFIG[report.reportType]
                  || { color: 'text-slate-600', bg: 'rgba(100,116,139,0.10)' };
                const statusCfg = STATUS_CONFIG[report.status] || STATUS_CONFIG.DRAFT;
                const isLoading = actionLoading === report.id;

                return (
                  <div
                    key={report.id}
                    className="rounded-2xl overflow-hidden transition-all duration-300 animate-fade-up hover:-translate-y-0.5"
                    style={{ ...glassCard, animationDelay: `${0.2 + idx * 0.03}s` } as any}
                  >
                    {/* Row header */}
                    <button
                      onClick={() => setExpandedId(isExpanded ? null : report.id)}
                      className="w-full text-left p-4"
                    >
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: typeCfg.bg }}>
                          <FileBarChart className={`w-5 h-5 ${typeCfg.color}`} />
                        </div>

                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                            <span className={`text-[10px] font-bold ${typeCfg.color} px-2 py-0.5 rounded-md uppercase tracking-wider`} style={{ background: typeCfg.bg }}>
                              {getTypeLabel(report.reportType)}
                            </span>
                            <span className={`text-[10px] font-bold ${statusCfg.color} px-2 py-0.5 rounded-md uppercase tracking-wider`} style={{ background: statusCfg.bg }}>
                              {statusCfg.label}
                            </span>
                            {report.reportLevel === 'NATIONAL' ? (
                              <span className="text-[10px] font-bold text-emerald-600 px-2 py-0.5 rounded-md uppercase tracking-wider inline-flex items-center gap-1" style={{ background: 'rgba(16,185,129,0.10)' }}>
                                <Globe className="w-2.5 h-2.5" /> National · {report.includedHospitalCount ?? 0} hosp
                              </span>
                            ) : report.hospitalName ? (
                              <span className={`text-[10px] font-semibold ${text.muted} px-2 py-0.5 rounded-md inline-flex items-center gap-1`} style={{ background: 'rgba(100,116,139,0.10)' }}>
                                <Building2 className="w-2.5 h-2.5" /> {report.hospitalName}
                              </span>
                            ) : null}
                          </div>
                          <p className={`text-[12px] font-semibold ${text.heading} truncate`}>
                            {format(new Date(report.reportPeriodStart), 'dd MMM yyyy')} — {format(new Date(report.reportPeriodEnd), 'dd MMM yyyy')}
                          </p>
                          <div className="flex items-center gap-3 mt-1">
                            <span className={`text-[10px] ${text.muted}`}>
                              by <span className={`font-semibold ${text.body}`}>{report.generatedByName}</span>
                            </span>
                            <span className={`text-[10px] ${text.muted} flex items-center gap-1`}>
                              <Clock className="w-2.5 h-2.5" />
                              {format(new Date(report.generatedAt), 'dd MMM yyyy HH:mm')}
                            </span>
                          </div>
                        </div>

                        <div className="flex-shrink-0">
                          {isExpanded ? <ChevronDown className={`w-4 h-4 ${text.muted}`} /> : <ChevronRight className={`w-4 h-4 ${text.muted}`} />}
                        </div>
                      </div>
                    </button>

                    {/* Expanded detail */}
                    {isExpanded && (
                      <div className="px-4 pb-4 pt-1 border-t border-gray-100/30">
                        {/* Aggregate Stats Grid */}
                        <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-3 mt-2`}>De-identified Aggregate Statistics</p>
                        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
                          {[
                            { icon: Users, label: 'Total ED Visits', value: report.totalEdVisits, color: 'text-blue-500', bg: 'rgba(59,130,246,0.10)' },
                            { icon: Activity, label: 'Total Triaged', value: report.totalTriaged, color: 'text-indigo-500', bg: 'rgba(99,102,241,0.10)' },
                            { icon: Clock, label: 'Avg Wait (min)', value: report.averageWaitTimeMinutes, color: 'text-amber-500', bg: 'rgba(245,158,11,0.10)' },
                            { icon: Heart, label: 'Mortality', value: report.mortalityCount, color: 'text-red-500', bg: 'rgba(239,68,68,0.10)' },
                            { icon: Building2, label: 'Admissions', value: report.admissionCount, color: 'text-emerald-500', bg: 'rgba(34,197,94,0.10)' },
                            { icon: AlertTriangle, label: 'ICU Admissions', value: report.icuAdmissionCount, color: 'text-orange-500', bg: 'rgba(249,115,22,0.10)' },
                            { icon: Baby, label: 'Pediatric Visits', value: report.pediatricVisitCount, color: 'text-pink-500', bg: 'rgba(236,72,153,0.10)' },
                            { icon: Bug, label: 'Malaria Positive', value: report.malariaPositiveCount, color: 'text-yellow-600', bg: 'rgba(202,138,4,0.10)' },
                          ].map((stat) => (
                            <div key={stat.label} className="rounded-xl p-3" style={glassInner}>
                              <div className="flex items-center gap-2 mb-1">
                                <stat.icon className={`w-3.5 h-3.5 ${stat.color}`} />
                                <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider`}>{stat.label}</p>
                              </div>
                              <p className={`text-lg font-extrabold ${text.heading}`}>{stat.value}</p>
                            </div>
                          ))}
                        </div>

                        {/* Triage Category Breakdown */}
                        {report.triageCategoryBreakdown && (
                          <div className="mt-3">
                            <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-2`}>Triage Category Breakdown</p>
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className={`text-xs ${text.body} font-mono whitespace-pre-wrap`}>{report.triageCategoryBreakdown}</p>
                            </div>
                          </div>
                        )}

                        {/* Submitted date */}
                        {report.submittedAt && (
                          <div className="mt-3 rounded-xl p-3" style={glassInner}>
                            <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Submitted At</p>
                            <p className={`text-[11px] font-semibold ${text.heading}`}>{format(new Date(report.submittedAt), 'dd MMM yyyy HH:mm')}</p>
                          </div>
                        )}

                        {/* Action buttons */}
                        <div className="flex items-center gap-2 mt-4 pt-3 border-t border-gray-100/30 flex-wrap">
                          {/* PDF export — available for every report state (statutory MoH / HMIS return) */}
                          <button
                            onClick={() => handleDownloadPdf(report.id)}
                            disabled={actionLoading === `pdf-${report.id}`}
                            className={`flex items-center gap-2 px-4 py-2 text-xs font-bold rounded-xl transition-all duration-300 disabled:opacity-50 ${text.body}`}
                            style={glassInner}
                          >
                            {actionLoading === `pdf-${report.id}`
                              ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                              : <Download className="w-3.5 h-3.5" />}
                            Download PDF
                          </button>
                          {report.status === 'DRAFT' && (
                            <button
                              onClick={() => handleSubmit(report.id)}
                              disabled={isLoading}
                              className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-blue-600 to-blue-700 hover:from-blue-700 hover:to-blue-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                            >
                              {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
                              Submit to MoH
                            </button>
                          )}
                          {report.status === 'SUBMITTED' && (
                            <>
                              <button
                                onClick={() => handleAccept(report.id)}
                                disabled={isLoading}
                                className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-emerald-600 to-emerald-700 hover:from-emerald-700 hover:to-emerald-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                              >
                                {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                                Accept
                              </button>
                              <button
                                onClick={() => setRejectTarget(report.id)}
                                disabled={isLoading}
                                className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-red-600 to-red-700 hover:from-red-700 hover:to-red-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                              >
                                <XCircle className="w-3.5 h-3.5" />
                                Reject
                              </button>
                            </>
                          )}
                          {report.status === 'ACCEPTED' && (
                            <div className="flex items-center gap-2 px-3 py-2 rounded-xl" style={{ background: 'rgba(34,197,94,0.10)' }}>
                              <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                              <span className="text-xs font-bold text-emerald-600">Accepted by MoH</span>
                            </div>
                          )}
                          {report.status === 'REJECTED' && (
                            <div className="flex items-center gap-2 px-3 py-2 rounded-xl" style={{ background: 'rgba(239,68,68,0.10)' }}>
                              <XCircle className="w-4 h-4 text-red-500" />
                              <span className="text-xs font-bold text-red-600">Rejected by MoH</span>
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

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 pt-3">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all duration-300 disabled:opacity-40 ${text.body}`}
                style={glassInner}
              >
                Previous
              </button>
              <span className={`text-xs font-semibold ${text.muted}`}>
                Page {page + 1} of {totalPages}
              </span>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all duration-300 disabled:opacity-40 ${text.body}`}
                style={glassInner}
              >
                Next
              </button>
            </div>
          )}
        </div>

        {/* -- Reject Modal ----------------------------------------- */}
        {rejectTarget && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm animate-fade-in">
            <div className="rounded-2xl p-6 w-full max-w-md mx-4 shadow-2xl" style={glassCard}>
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(239,68,68,0.12)' }}>
                  <XCircle className="w-5 h-5 text-red-500" />
                </div>
                <div>
                  <h3 className={`text-sm font-extrabold ${text.heading}`}>Reject Report</h3>
                  <p className={`text-[10px] ${text.muted}`}>Provide a reason for rejection</p>
                </div>
              </div>
              <textarea
                value={rejectReason}
                onChange={(e) => setRejectReason(e.target.value)}
                placeholder="Reason for rejection..."
                rows={4}
                className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} focus:outline-none focus:ring-2 focus:ring-red-500/20 transition-all duration-300 resize-none`}
                style={inputStyle}
              />
              <div className="flex items-center justify-end gap-3 mt-4">
                <button
                  onClick={() => { setRejectTarget(null); setRejectReason(''); }}
                  className={`px-4 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
                >
                  Cancel
                </button>
                <button
                  onClick={handleReject}
                  disabled={!rejectReason.trim() || actionLoading === rejectTarget}
                  className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-red-600 to-red-700 hover:from-red-700 hover:to-red-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {actionLoading === rejectTarget ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
                  Reject Report
                </button>
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}
