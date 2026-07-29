/* ═══════════════════════════════════════════════════════════════
   Report Center — server-generated operational reports + launcher.

   Every "Generate" button downloads a REAL PDF rendered by the backend from
   authoritative queries: branded masthead, report parameters, requested-by,
   generated-at, page numbers, data tables, and (for the shift handover) a
   signature block. The previous page's "Save as PDF" was window.print() — a
   screenshot of the screen — and its "census" read the client-side store;
   both are gone. Generation endpoints are authz-gated per report, so every
   download is attributed in the hospital audit trail.
   ═══════════════════════════════════════════════════════════════ */

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  FileText, BarChart3, ChevronRight, FlaskConical, Siren, ShieldAlert,
  ClipboardList, Pill, UserX, CalendarDays, Users2, Download, Loader2,
  ClipboardCheck, Stethoscope, AlertTriangle, Table2,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { useCanSeeAllZones } from '@/hooks/useCanSeeAllZones';
import { canAccessPage } from '@/types/roles';
import type { AppPage } from '@/types/roles';
import { operationalReportApi } from '@/api/reports';
import { hospitalApi } from '@/api/hospitals';
import type { HospitalResponse } from '@/api/types';
import { ApiError } from '@/api/client';
import { PdfPreviewModal, usePdfPreview } from '@/components/PdfPreviewModal';
import { CsvPreviewModal, useCsvPreview } from '@/components/CsvPreviewModal';

/* ── The real report surfaces this hub launches into ── */
interface ReportLink {
  page: AppPage;
  path: string;
  name: string;
  description: string;
  icon: typeof FileText;
  iconBg: string;
  iconColor: string;
}

const REPORT_LINKS: ReportLink[] = [
  { page: 'quality', path: '/quality', name: 'Quality Metrics', description: 'ED quality dashboard + CSV export', icon: BarChart3, iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600' },
  { page: 'moh-reports', path: '/moh-reports', name: 'MOH Reports', description: 'Ministry of Health statutory reports (PDF)', icon: ClipboardList, iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500' },
  { page: 'safety-incidents', path: '/safety-incidents', name: 'Safety Incidents', description: 'Incident register + CSV / per-incident PDF', icon: ShieldAlert, iconBg: 'rgba(239,68,68,0.1)', iconColor: 'text-red-500' },
  { page: 'med-safety-overrides', path: '/med-safety/overrides', name: 'Override Audit', description: 'Medication-safety + break-the-glass governance', icon: Pill, iconBg: 'rgba(244,63,94,0.1)', iconColor: 'text-rose-500' },
  { page: 'lab', path: '/lab', name: 'Laboratory Reporting', description: 'Turnaround / workload pack (PDF + CSV)', icon: FlaskConical, iconBg: 'rgba(34,197,94,0.12)', iconColor: 'text-emerald-500' },
  { page: 'ems', path: '/ems', name: 'EMS / Pre-hospital', description: 'Paramedic runs + Patient Care Report (PDF)', icon: Siren, iconBg: 'rgba(251,146,60,0.12)', iconColor: 'text-orange-500' },
  { page: 'registrar-reports', path: '/registrar-reports', name: 'Registrar Reporting', description: 'Intake log, identity-reconciliation queue & census (CSV)', icon: UserX, iconBg: 'rgba(20,184,166,0.12)', iconColor: 'text-teal-500' },
];

const today = () => new Date().toISOString().slice(0, 10);
const daysAgo = (n: number) => new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);

type ReportKey = 'daily' | 'shift' | 'period' | 'quality' | 'mine';

export function ReportsView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const { showPdf, previewProps } = usePdfPreview();
  const { showCsv, previewProps: csvPreviewProps } = useCsvPreview();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const role = user?.role;
  const access = useCanSeeAllZones();

  /* ── SUPER_ADMIN is a national role — let it generate for any hospital
     (own hospitalId is the phantom system hospital). Everyone else is pinned. ── */
  const isSuperAdmin = role === 'SUPER_ADMIN';
  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);
  const [selectedHospitalId, setSelectedHospitalId] = useState('');
  useEffect(() => {
    if (!isSuperAdmin) return;
    hospitalApi.getAll(0, 50).then((page) => {
      const rows = page.content || [];
      setHospitals(rows);
      const firstReal = rows.find((h) => h.id !== user?.hospitalId) || rows[0];
      if (firstReal) setSelectedHospitalId((cur) => cur || firstReal.id);
    }).catch((e) => console.error('[Reports] hospitals load failed:', e));
  }, [isSuperAdmin, user?.hospitalId]);
  const hospitalId = (isSuperAdmin ? selectedHospitalId : user?.hospitalId) || user?.hospitalId || '';

  /* ── Parameters per report ── */
  const [dailyDate, setDailyDate] = useState(today());
  const [shiftDate, setShiftDate] = useState(today());
  const [shiftPeriod, setShiftPeriod] = useState<'DAY' | 'NIGHT'>('DAY');
  const [periodFrom, setPeriodFrom] = useState(daysAgo(30));
  const [periodTo, setPeriodTo] = useState(today());
  const [qualityFrom, setQualityFrom] = useState(daysAgo(30));
  const [qualityTo, setQualityTo] = useState(today());
  const [mineFrom, setMineFrom] = useState(daysAgo(30));
  const [mineTo, setMineTo] = useState(today());

  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async (
    key: ReportKey,
    fn: () => Promise<{ blob: Blob; filename: string }>,
    kind: 'pdf' | 'csv' = 'pdf',
  ) => {
    setBusy(kind === 'csv' ? `${key}:csv` : key);
    setError(null);
    try {
      const { blob, filename } = await fn();
      if (kind === 'csv') showCsv(blob, filename);
      else showPdf(blob, filename);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Report generation failed');
      console.error('[Reports] generation failed:', err);
    } finally {
      setBusy(null);
    }
  };

  /* ── Role → catalog visibility (mirrors the endpoint authz) ── */
  const opsLeadership = access.canSeeAllZones; // admin / CN / shift lead / super admin
  const governance = opsLeadership;
  const clinician = role === 'DOCTOR' || role === 'NURSE' || role === 'HOSPITAL_ADMIN' || role === 'SUPER_ADMIN';

  interface CatalogEntry {
    key: ReportKey;
    show: boolean;
    icon: typeof FileText;
    iconBg: string;
    iconColor: string;
    name: string;
    description: string;
    params: React.ReactNode;
    generate: () => void;
    /** Optional CSV export (tabular reports only) — same params, table preview. */
    generateCsv?: () => void;
  }

  const dateInput = (value: string, onChange: (v: string) => void, title: string) => (
    <input type="date" value={value} onChange={(e) => onChange(e.target.value)} title={title}
      style={glassInner} className={`px-2.5 py-1.5 text-[11px] rounded-lg focus:outline-none ${text.body}`} />
  );

  const catalog: CatalogEntry[] = useMemo(() => [
    {
      key: 'daily', show: opsLeadership, icon: CalendarDays,
      iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600',
      name: 'Daily ED Activity',
      description: 'Arrivals, triage mix, dispositions, waits, module activity and census for one day.',
      params: dateInput(dailyDate, setDailyDate, 'Report date'),
      generate: () => run('daily', () => operationalReportApi.dailyActivity(hospitalId, dailyDate)),
    },
    {
      key: 'shift', show: opsLeadership, icon: ClipboardCheck,
      iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500',
      name: 'Shift Handover Summary',
      description: 'Live census, open clinical work, shift-window activity, roster + signature block.',
      params: (
        <div className="flex items-center gap-1.5">
          {dateInput(shiftDate, setShiftDate, 'Shift date')}
          <select value={shiftPeriod} onChange={(e) => setShiftPeriod(e.target.value as 'DAY' | 'NIGHT')}
            style={glassInner} className={`px-2 py-1.5 text-[11px] font-bold rounded-lg focus:outline-none ${text.body}`}>
            <option value="DAY">DAY</option>
            <option value="NIGHT">NIGHT</option>
          </select>
        </div>
      ),
      generate: () => run('shift', () => operationalReportApi.shiftHandoverSummary(hospitalId, shiftDate, shiftPeriod)),
    },
    {
      key: 'period', show: governance, icon: BarChart3,
      iconBg: 'rgba(34,197,94,0.12)', iconColor: 'text-emerald-600',
      name: 'Period Activity (trend)',
      description: 'Totals + per-day trend table over a date range (max 92 days).',
      params: (
        <div className="flex items-center gap-1.5">
          {dateInput(periodFrom, setPeriodFrom, 'From')}
          <span className={`text-[10px] ${text.muted}`}>→</span>
          {dateInput(periodTo, setPeriodTo, 'To')}
        </div>
      ),
      generate: () => run('period', () => operationalReportApi.periodActivity(hospitalId, periodFrom, periodTo)),
      generateCsv: () => run('period', () => operationalReportApi.periodActivityCsv(hospitalId, periodFrom, periodTo), 'csv'),
    },
    {
      key: 'quality', show: governance, icon: Users2,
      iconBg: 'rgba(244,63,94,0.10)', iconColor: 'text-rose-500',
      name: 'Quality Metrics',
      description: 'Daily KPI snapshots (waits, door-to-triage, mortality, LWBS, re-triages) over a period.',
      params: (
        <div className="flex items-center gap-1.5">
          {dateInput(qualityFrom, setQualityFrom, 'From')}
          <span className={`text-[10px] ${text.muted}`}>→</span>
          {dateInput(qualityTo, setQualityTo, 'To')}
        </div>
      ),
      generate: () => run('quality', () => operationalReportApi.qualityMetrics(hospitalId, qualityFrom, qualityTo)),
      generateCsv: () => run('quality', () => operationalReportApi.qualityMetricsCsv(hospitalId, qualityFrom, qualityTo), 'csv'),
    },
    {
      key: 'mine', show: clinician, icon: Stethoscope,
      iconBg: 'rgba(168,85,247,0.12)', iconColor: 'text-purple-500',
      name: 'My Clinical Activity',
      description: 'Your own workload: patients attended, notes authored, medications prescribed.',
      params: (
        <div className="flex items-center gap-1.5">
          {dateInput(mineFrom, setMineFrom, 'From')}
          <span className={`text-[10px] ${text.muted}`}>→</span>
          {dateInput(mineTo, setMineTo, 'To')}
        </div>
      ),
      generate: () => run('mine', () => operationalReportApi.myActivity(mineFrom, mineTo)),
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [opsLeadership, governance, clinician, hospitalId, dailyDate, shiftDate, shiftPeriod,
      periodFrom, periodTo, qualityFrom, qualityTo, mineFrom, mineTo, busy, glassInner, text]);

  const visible = catalog.filter((c) => c.show);
  const links = REPORT_LINKS.filter((l) => role != null && canAccessPage(role, l.page));

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-4">
                <div className="w-10 h-10 bg-cyan-500/20 rounded-xl flex items-center justify-center shadow-lg">
                  <FileText className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Report Center</h1>
                  <p className="text-white/50 text-xs font-medium">
                    Server-generated PDF reports from live clinical data — every generation is audit-logged
                  </p>
                </div>
              </div>
              {isSuperAdmin && hospitals.length > 0 && (
                <select
                  value={selectedHospitalId}
                  onChange={(e) => setSelectedHospitalId(e.target.value)}
                  title="Which hospital to report on (national role)"
                  className="px-3 py-2 rounded-xl text-xs font-bold bg-white/10 text-white border border-white/15 focus:outline-none [&>option]:text-slate-800"
                >
                  {hospitals.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
                </select>
              )}
            </div>
          </div>
        </div>

        {error && (
          <div className="rounded-2xl px-4 py-3 flex items-start gap-2 bg-red-500/10 border border-red-500/20 animate-fade-up">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[12px] font-semibold text-red-500">{error}</p>
          </div>
        )}

        {/* ── Generate a report ── */}
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <div className="flex items-center gap-3 mb-4">
            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
              <Download className="w-[18px] h-[18px] text-cyan-600" />
            </div>
            <div>
              <h2 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Generate a report</h2>
              <p className={`text-xs ${text.body} font-medium mt-0.5`}>
                Pick parameters and download — the PDF carries the parameters, who requested it, and when
              </p>
            </div>
          </div>
          {visible.length === 0 ? (
            <p className={`text-sm ${text.muted} py-6 text-center`}>Your role has no generatable reports.</p>
          ) : (
            <div className="space-y-2.5">
              {visible.map((c) => {
                const Icon = c.icon;
                return (
                  <div key={c.key} className="flex items-center justify-between gap-3 p-3.5 rounded-xl flex-wrap" style={glassInner}>
                    <div className="flex items-center gap-3 min-w-0">
                      <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: c.iconBg }}>
                        <Icon className={`w-[18px] h-[18px] ${c.iconColor}`} />
                      </div>
                      <div className="min-w-0">
                        <div className={`text-[13px] font-bold ${text.heading}`}>{c.name}</div>
                        <div className={`text-[11px] ${text.muted} font-medium`}>{c.description}</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 flex-wrap">
                      {c.params}
                      <button
                        onClick={c.generate}
                        disabled={busy !== null}
                        title="Preview the branded PDF, then download"
                        className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-600 text-white hover:bg-cyan-700 transition-colors disabled:opacity-50 shadow-md"
                      >
                        {busy === c.key ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
                        PDF
                      </button>
                      {c.generateCsv && (
                        <button
                          onClick={c.generateCsv}
                          disabled={busy !== null}
                          title="Preview the data as a table, then download CSV"
                          className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-600 text-white hover:bg-emerald-700 transition-colors disabled:opacity-50 shadow-md"
                        >
                          {busy === `${c.key}:csv` ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Table2 className="w-3.5 h-3.5" />}
                          CSV
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* ── Reports & exports hub (launcher to the specialised surfaces) ── */}
        <div className="rounded-2xl p-5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.1s' } as any}>
          <div className="flex items-center gap-3 mb-5">
            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
              <FileText className="w-[18px] h-[18px] text-cyan-600" />
            </div>
            <div>
              <h2 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Specialised reporting surfaces</h2>
              <p className={`text-xs ${text.body} font-medium mt-0.5`}>Each has its own live register, filters, and PDF/CSV exports</p>
            </div>
          </div>
          {links.length === 0 ? (
            <p className={`text-sm ${text.muted} py-6 text-center`}>Your role has no reporting surfaces.</p>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {links.map((l) => {
                const Icon = l.icon;
                return (
                  <button
                    key={l.page}
                    onClick={() => navigate(l.path)}
                    className="w-full flex items-center justify-between p-3.5 rounded-xl hover:-translate-y-1 transition-all group text-left"
                    style={glassInner}
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 group-hover:scale-110 transition-transform" style={{ backgroundColor: l.iconBg }}>
                        <Icon className={`w-[18px] h-[18px] ${l.iconColor}`} />
                      </div>
                      <div>
                        <div className={`text-[13px] font-bold ${text.heading}`}>{l.name}</div>
                        <div className={`text-[11px] ${text.muted} font-medium`}>{l.description}</div>
                      </div>
                    </div>
                    <ChevronRight className={`w-4 h-4 ${text.muted} group-hover:text-cyan-600 transition-colors flex-shrink-0 ml-3`} />
                  </button>
                );
              })}
            </div>
          )}
          <p className={`text-[11px] ${text.muted} mt-4`}>
            Per-visit SBAR handover PDFs are on each patient's chart (Handover tab). Isolation, sepsis,
            hypoglycemia and fast-track case registers live under Clinical Tools.
          </p>
        </div>

      </div>
      <PdfPreviewModal {...previewProps} />
      <CsvPreviewModal {...csvPreviewProps} />
    </div>
  );
}
