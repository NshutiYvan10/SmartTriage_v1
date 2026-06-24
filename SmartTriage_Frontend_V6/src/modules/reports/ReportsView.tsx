import { useNavigate } from 'react-router-dom';
import {
  FileText, TrendingUp, Users, AlertTriangle, BarChart3, Baby, Printer,
  ChevronRight, FlaskConical, Siren, ShieldAlert, ClipboardList, Pill, UserX,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { usePatientStore } from '@/store/patientStore';
import { useAuthStore } from '@/store/authStore';
import { canAccessPage } from '@/types/roles';
import type { AppPage } from '@/types/roles';
import { Badge } from '@/components/ui/Badge';
import { TriageCategory } from '@/types';
import { getCategoryColor } from '@/utils/tewsCalculator';
import {
  PieChart, Pie, Cell, Legend, Tooltip, ResponsiveContainer,
} from 'recharts';

/* ── Premium tooltip (donut) ── */
function CustomTooltip({ active, payload }: any) {
  const { isDark, text } = useTheme();
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-xl px-4 py-3" style={{
      background: isDark ? 'rgba(8,47,73,0.92)' : 'rgba(255,255,255,0.92)',
      backdropFilter: 'blur(16px)',
      border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(255,255,255,0.7)',
    }}>
      {payload.map((entry: any, i: number) => (
        <p key={i} className="text-[11px] flex items-center gap-2 py-0.5 font-medium" style={{ color: entry.color }}>
          <span className="w-2 h-2 rounded-full" style={{ backgroundColor: entry.color }} />
          <span className={text.body}>{entry.name}:</span>
          <span className={`font-bold ${text.heading}`}>{entry.value}</span>
        </p>
      ))}
    </div>
  );
}

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

export function ReportsView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const patients = usePatientStore((state) => state.patients);
  const role = useAuthStore((s) => s.user?.role);

  const stats = {
    totalPatients: patients.length,
    averageTEWS: patients.filter((p) => p.tewsScore !== undefined).length > 0
      ? (patients.reduce((sum, p) => sum + (p.tewsScore || 0), 0) /
        patients.filter((p) => p.tewsScore !== undefined).length).toFixed(1)
      : '0',
    criticalCases: patients.filter((p) => p.category === 'RED').length,
    pediatricCases: patients.filter((p) => p.isPediatric).length,
    categoryBreakdown: {
      RED: patients.filter((p) => p.category === 'RED').length,
      ORANGE: patients.filter((p) => p.category === 'ORANGE').length,
      YELLOW: patients.filter((p) => p.category === 'YELLOW').length,
      GREEN: patients.filter((p) => p.category === 'GREEN').length,
      BLUE: patients.filter((p) => p.category === 'BLUE').length,
    },
  };

  const pieData = (['RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE'] as TriageCategory[])
    .map((cat) => ({ name: cat, value: stats.categoryBreakdown[cat], color: getCategoryColor(cat) }))
    .filter((d) => d.value > 0);

  const links = REPORT_LINKS.filter((l) => role != null && canAccessPage(role, l.page));

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-10 h-10 bg-cyan-500/20 rounded-xl flex items-center justify-center shadow-lg">
                  <BarChart3 className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Reports &amp; Analytics</h1>
                  <p className="text-white/50 text-xs font-medium">A live snapshot plus a launcher to every reporting surface you can access</p>
                </div>
              </div>
              <button
                onClick={() => window.print()}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-slate-800 bg-white hover:bg-gray-50 rounded-xl transition-all shadow-lg hover:-translate-y-0.5"
              >
                <Printer className="w-3.5 h-3.5" /> Print this page
              </button>
            </div>
          </div>
        </div>

        {/* ── Live census snapshot (real, from the patient store) ── */}
        <div className="rounded-2xl p-5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.08s' } as any}>
          <h3 className={`text-base font-extrabold ${text.heading} tracking-tight mb-0.5`}>Current census</h3>
          <p className={`text-xs ${text.body} font-medium mb-4`}>Live, from the active patient list</p>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            {[
              { label: 'Total Patients', value: stats.totalPatients, sublabel: 'In the system now', icon: Users, iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600' },
              { label: 'Average TEWS', value: stats.averageTEWS, sublabel: 'Severity index', icon: TrendingUp, iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500' },
              { label: 'Critical Cases', value: stats.criticalCases, sublabel: 'RED category', icon: AlertTriangle, iconBg: 'rgba(239,68,68,0.1)', iconColor: 'text-red-500' },
              { label: 'Pediatric Cases', value: stats.pediatricCases, sublabel: 'Age < 15 years', icon: Baby, iconBg: 'rgba(236,72,153,0.1)', iconColor: 'text-pink-500' },
            ].map((stat) => {
              const Icon = stat.icon;
              return (
                <div key={stat.label} className="flex items-center gap-3 p-3 rounded-xl" style={glassInner}>
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: stat.iconBg }}>
                    <Icon className={`w-[18px] h-[18px] ${stat.iconColor}`} />
                  </div>
                  <div className="min-w-0">
                    <div className={`text-lg font-extrabold ${text.heading} leading-none`}>{stat.value}</div>
                    <div className={`text-[11px] ${text.muted} font-medium mt-0.5`}>{stat.sublabel}</div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* ── Triage distribution (real) ── */}
        <div className="rounded-2xl p-5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.15s' } as any}>
          <div className="flex items-center gap-3 mb-1">
            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
              <BarChart3 className="w-[18px] h-[18px] text-cyan-600" />
            </div>
            <div>
              <h2 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Triage Distribution</h2>
              <p className={`text-xs ${text.body} font-medium mt-0.5`}>Current breakdown by severity</p>
            </div>
          </div>
          {pieData.length === 0 ? (
            <p className={`text-sm ${text.muted} py-8 text-center`}>No patients in the system yet.</p>
          ) : (
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 items-center">
              <div style={{ height: 200 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={pieData} cx="50%" cy="50%" innerRadius={55} outerRadius={85} paddingAngle={4} dataKey="value" stroke="none">
                      {pieData.map((entry, index) => <Cell key={`cell-${index}`} fill={entry.color} />)}
                    </Pie>
                    <Tooltip content={<CustomTooltip />} />
                    <Legend iconType="circle" iconSize={8}
                      formatter={(value: string) => <span className={`text-[11px] font-medium ml-1 ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>{value}</span>} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="space-y-2.5">
                {(['RED', 'ORANGE', 'YELLOW', 'GREEN'] as TriageCategory[]).map((category) => {
                  const count = stats.categoryBreakdown[category];
                  const percentage = stats.totalPatients > 0 ? (count / stats.totalPatients) * 100 : 0;
                  return (
                    <div key={category} className="flex items-center gap-2.5">
                      <Badge category={category} size="sm" />
                      <div className={`flex-1 rounded-full h-1.5 overflow-hidden ${isDark ? 'bg-slate-700/40' : 'bg-gray-100/60'}`}>
                        <div className="h-1.5 rounded-full transition-all duration-700" style={{ width: `${percentage}%`, backgroundColor: getCategoryColor(category) }} />
                      </div>
                      <span className={`text-[11px] font-bold w-10 text-right tabular-nums ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>{percentage.toFixed(0)}%</span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>

        {/* ── Reports & exports hub (launcher to the real surfaces) ── */}
        <div className="rounded-2xl p-5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.2s' } as any}>
          <div className="flex items-center gap-3 mb-5">
            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
              <FileText className="w-[18px] h-[18px] text-cyan-600" />
            </div>
            <div>
              <h2 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Reports &amp; exports</h2>
              <p className={`text-xs ${text.body} font-medium mt-0.5`}>Open a reporting surface — each has its own live data, PDF, and/or CSV export</p>
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
            Per-visit SBAR handover PDFs are available on each patient's chart (Handover tab).
          </p>
        </div>

      </div>
    </div>
  );
}
