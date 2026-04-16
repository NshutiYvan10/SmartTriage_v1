import { useState } from 'react';
import { FileText, Download, Calendar, TrendingUp, Users, AlertTriangle, BarChart3, Baby, Activity, Clock, Printer } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { usePatientStore } from '@/store/patientStore';
import { Badge } from '@/components/ui/Badge';
import { TriageCategory } from '@/types';
import { getCategoryColor } from '@/utils/tewsCalculator';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell, Legend,
  BarChart, Bar,
  RadarChart, Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis,
  LineChart, Line,
} from 'recharts';

// ── Chart data (empty — populated from real patient data when available) ──
const patientFlowData: any[] = [];
const hourlyArrivals: any[] = [];
const weeklyTrendData: any[] = [];
const performanceData: any[] = [];

// ── Shared inline-glass style ──
// (Now provided by useTheme hook)

// ── Premium tooltip ──
function CustomTooltip({ active, payload, label }: any) {
  const { isDark } = useTheme();
  if (!active || !payload?.length) return null;
  return (
    <div
      className="rounded-xl px-4 py-3"
      style={{
        background: isDark ? 'rgba(8,47,73,0.92)' : 'rgba(255,255,255,0.92)',
        backdropFilter: 'blur(16px)',
        WebkitBackdropFilter: 'blur(16px)',
        border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(255,255,255,0.7)',
        boxShadow: isDark ? '0 12px 40px rgba(0,0,0,0.3)' : '0 12px 40px rgba(0,0,0,0.1), inset 0 1px 0 rgba(255,255,255,0.9)',
      }}
    >
      <p className="text-[12px] font-bold text-slate-800 mb-1.5 tracking-wide">{label}</p>
      {payload.map((entry: any, i: number) => (
        <p key={i} className="text-[11px] flex items-center gap-2 py-0.5 font-medium" style={{ color: entry.color }}>
          <span className="w-2 h-2 rounded-full shadow-sm" style={{ backgroundColor: entry.color }} />
          <span className="text-slate-500">{entry.name}:</span>
          <span className="font-bold text-slate-800">{entry.value}</span>
        </p>
      ))}
    </div>
  );
}

export function ReportsView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const patients = usePatientStore((state) => state.patients);
  const [dateRange, setDateRange] = useState('today');

  // Calculate statistics from real patient data
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
    .map((cat) => ({
      name: cat,
      value: stats.categoryBreakdown[cat],
      color: getCategoryColor(cat),
    }))
    .filter((d) => d.value > 0);

  const reportTypes = [
    { id: 'daily', name: 'Daily Triage Summary', icon: FileText, description: 'Complete daily activity report', iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600' },
    { id: 'performance', name: 'Performance Metrics', icon: TrendingUp, description: 'TEWS scores and wait times', iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500' },
    { id: 'patient', name: 'Patient Demographics', icon: Users, description: 'Age, gender, and complaint analysis', iconBg: 'rgba(34,197,94,0.12)', iconColor: 'text-emerald-500' },
    { id: 'critical', name: 'Critical Cases Review', icon: AlertTriangle, description: 'RED category incident reports', iconBg: 'rgba(239,68,68,0.1)', iconColor: 'text-red-500' },
  ];

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Dark Header Banner ── */}
        <div className="glass-card-dark rounded-3xl overflow-hidden animate-fade-up">
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                  <BarChart3 className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Reports & Analytics</h1>
                  <p className="text-white/70 text-xs font-medium">Comprehensive triage system reports and data-driven insights</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                  <span className="text-xs font-semibold text-white/90">Live Data</span>
                </div>
                <button className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-slate-800 bg-white hover:bg-gray-50 rounded-xl transition-all duration-300 shadow-lg hover:-translate-y-0.5">
                  <Printer className="w-3.5 h-3.5" />
                  Print Report
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Quick Stats Overview ── */}
        <div
          className="rounded-2xl p-5 animate-fade-up"
          style={{ ...glassCard, animationDelay: '0.08s' } as any}
        >
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className="text-base font-extrabold text-slate-800 tracking-tight">Today's Snapshot</h3>
              <p className="text-xs text-slate-500 font-medium mt-0.5">Key metrics at a glance</p>
            </div>
            {/* Date Range Selector */}
            <div className="flex items-center gap-2">
              <Calendar className="w-3.5 h-3.5 text-slate-400" />
              {['today', 'week', 'month', 'year'].map((range) => (
                <button
                  key={range}
                  onClick={() => setDateRange(range)}
                  className={`px-3 py-1.5 text-[11px] font-bold rounded-lg transition-all duration-300 ${dateRange === range
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md shadow-slate-800/20'
                    : 'text-slate-500 hover:bg-white/60'
                  }`}
                >
                  {range.charAt(0).toUpperCase() + range.slice(1)}
                </button>
              ))}
            </div>
          </div>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            {[
              { label: 'Total Patients', value: stats.totalPatients, sublabel: 'Registered today', icon: Users, iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600' },
              { label: 'Average TEWS', value: stats.averageTEWS, sublabel: 'Severity index', icon: TrendingUp, iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500' },
              { label: 'Critical Cases', value: stats.criticalCases, sublabel: 'RED category', icon: AlertTriangle, iconBg: 'rgba(239,68,68,0.1)', iconColor: 'text-red-500' },
              { label: 'Pediatric Cases', value: stats.pediatricCases, sublabel: 'Age < 15 years', icon: Baby, iconBg: 'rgba(236,72,153,0.1)', iconColor: 'text-pink-500' },
            ].map((stat, idx) => {
              const Icon = stat.icon;
              return (
                <div
                  key={stat.label}
                  className="flex items-center gap-3 p-3 rounded-xl hover:-translate-y-0.5 transition-all duration-400 group cursor-default"
                  style={{
                    background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.6)',
                    border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)',
                    boxShadow: isDark ? '0 2px 8px rgba(0,0,0,0.2)' : '0 2px 8px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)',
                  }}
                >
                  <div
                    className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 group-hover:scale-110 transition-transform duration-400"
                    style={{ backgroundColor: stat.iconBg }}
                  >
                    <Icon className={`w-[18px] h-[18px] ${stat.iconColor}`} />
                  </div>
                  <div className="min-w-0">
                    <div className="text-lg font-extrabold text-slate-800 leading-none animate-number-pop" style={{ animationDelay: `${idx * 0.1}s` }}>{stat.value}</div>
                    <div className="text-[11px] text-slate-400 font-medium mt-0.5">{stat.sublabel}</div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* ── Row 1: Patient Flow Area Chart (full width) ── */}
        <div
          className="rounded-2xl p-5 animate-fade-up"
          style={{ ...glassCard, animationDelay: '0.15s' } as any}
        >
          <div className="flex items-center justify-between mb-1">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(99,102,241,0.12)' }}>
                <Activity className="w-[18px] h-[18px] text-indigo-500" />
              </div>
              <div>
                <h2 className="text-base font-extrabold text-slate-800 tracking-tight">Patient Flow Over Time</h2>
                <p className="text-xs text-slate-500 font-medium mt-0.5">Real-time patient census throughout the day</p>
              </div>
            </div>
            <div className="flex items-center gap-4 text-[11px] text-slate-500 font-medium">
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-indigo-500 shadow-sm" />Active</span>
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-red-400 shadow-sm" />Critical</span>
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-emerald-400 shadow-sm" />Discharged</span>
            </div>
          </div>
          <div className="mt-4" style={{ height: 220 }}>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={patientFlowData} margin={{ top: 5, right: 10, left: -10, bottom: 0 }}>
                <defs>
                  <linearGradient id="gradientPatients" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#6366f1" stopOpacity={0.25} />
                    <stop offset="100%" stopColor="#6366f1" stopOpacity={0.01} />
                  </linearGradient>
                  <linearGradient id="gradientCritical" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#f87171" stopOpacity={0.25} />
                    <stop offset="100%" stopColor="#f87171" stopOpacity={0.01} />
                  </linearGradient>
                  <linearGradient id="gradientDischarged" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#34d399" stopOpacity={0.25} />
                    <stop offset="100%" stopColor="#34d399" stopOpacity={0.01} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke={isDark ? '#1e293b' : '#f1f5f9'} vertical={false} />
                <XAxis dataKey="time" tick={{ fontSize: 11, fill: '#94a3b8', fontWeight: 500 }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fontSize: 11, fill: '#94a3b8', fontWeight: 500 }} axisLine={false} tickLine={false} />
                <Tooltip content={<CustomTooltip />} />
                <Area type="monotone" dataKey="patients" name="Active" stroke="#6366f1" strokeWidth={2.5} fill="url(#gradientPatients)" dot={false} activeDot={{ r: 5, fill: '#6366f1', stroke: '#fff', strokeWidth: 2.5 }} />
                <Area type="monotone" dataKey="critical" name="Critical" stroke="#f87171" strokeWidth={2} fill="url(#gradientCritical)" dot={false} activeDot={{ r: 5, fill: '#f87171', stroke: '#fff', strokeWidth: 2.5 }} />
                <Area type="monotone" dataKey="discharged" name="Discharged" stroke="#34d399" strokeWidth={2} fill="url(#gradientDischarged)" dot={false} activeDot={{ r: 5, fill: '#34d399', stroke: '#fff', strokeWidth: 2.5 }} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* ── Row 2: Donut Chart + Stacked Bar Chart ── */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

          {/* Triage Category Donut */}
          <div
            className="rounded-2xl p-5 animate-fade-up"
            style={{ ...glassCard, animationDelay: '0.2s' } as any}
          >
            <div className="flex items-center gap-3 mb-1">
              <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
                <BarChart3 className="w-[18px] h-[18px] text-cyan-600" />
              </div>
              <div>
                <h2 className="text-base font-extrabold text-slate-800 tracking-tight">Triage Distribution</h2>
                <p className="text-xs text-slate-500 font-medium mt-0.5">Breakdown by severity level</p>
              </div>
            </div>
            <div className="mt-3" style={{ height: 200 }}>
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={pieData}
                    cx="50%"
                    cy="50%"
                    innerRadius={55}
                    outerRadius={85}
                    paddingAngle={4}
                    dataKey="value"
                    stroke="none"
                  >
                    {pieData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.color} />
                    ))}
                  </Pie>
                  <Tooltip content={<CustomTooltip />} />
                  <Legend
                    iconType="circle"
                    iconSize={8}
                    formatter={(value: string) => (
                      <span className={`text-[11px] font-medium ml-1 ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>{value}</span>
                    )}
                  />
                </PieChart>
              </ResponsiveContainer>
            </div>
            {/* Mini bar breakdown */}
            <div className="space-y-2.5 mt-3 pt-4 border-t border-gray-100/60">
              {(['RED', 'ORANGE', 'YELLOW', 'GREEN'] as TriageCategory[]).map((category) => {
                const count = stats.categoryBreakdown[category];
                const percentage = stats.totalPatients > 0 ? (count / stats.totalPatients) * 100 : 0;
                return (
                  <div key={category} className="flex items-center gap-2.5">
                    <Badge category={category} size="sm" />
                    <div className={`flex-1 rounded-full h-1.5 overflow-hidden ${isDark ? 'bg-slate-700/40' : 'bg-gray-100/60'}`}>
                      <div className="h-1.5 rounded-full transition-all duration-700 ease-out" style={{ width: `${percentage}%`, backgroundColor: getCategoryColor(category) }} />
                    </div>
                    <span className={`text-[11px] font-bold w-10 text-right tabular-nums ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>{percentage.toFixed(0)}%</span>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Hourly Arrivals Stacked Bar Chart */}
          <div
            className="rounded-2xl p-5 animate-fade-up"
            style={{ ...glassCard, animationDelay: '0.25s' } as any}
          >
            <div className="flex items-center gap-3 mb-1">
              <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(251,146,60,0.12)' }}>
                <Clock className="w-[18px] h-[18px] text-orange-500" />
              </div>
              <div>
                <h2 className="text-base font-extrabold text-slate-800 tracking-tight">Hourly Arrivals</h2>
                <p className="text-xs text-slate-500 font-medium mt-0.5">Breakdown by arrival mode</p>
              </div>
            </div>
            <div className="mt-3" style={{ height: 230 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={hourlyArrivals} margin={{ top: 5, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke={isDark ? '#1e293b' : '#f1f5f9'} vertical={false} />
                  <XAxis dataKey="hour" tick={{ fontSize: 10, fill: '#94a3b8', fontWeight: 500 }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 11, fill: '#94a3b8', fontWeight: 500 }} axisLine={false} tickLine={false} />
                  <Tooltip content={<CustomTooltip />} />
                  <Bar dataKey="ambulance" name="Ambulance" stackId="arrivals" fill="#ef4444" radius={[0, 0, 0, 0]} />
                  <Bar dataKey="walkin" name="Walk-in" stackId="arrivals" fill="#6366f1" radius={[0, 0, 0, 0]} />
                  <Bar dataKey="referral" name="Referral" stackId="arrivals" fill="#a78bfa" radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
            <div className="flex items-center justify-center gap-5 mt-4 pt-4 border-t border-gray-100/60">
              <span className="flex items-center gap-1.5 text-[11px] text-slate-500 font-medium"><span className="w-2.5 h-2.5 rounded-sm bg-red-500 shadow-sm" />Ambulance</span>
              <span className="flex items-center gap-1.5 text-[11px] text-slate-500 font-medium"><span className="w-2.5 h-2.5 rounded-sm bg-indigo-500 shadow-sm" />Walk-in</span>
              <span className="flex items-center gap-1.5 text-[11px] text-slate-500 font-medium"><span className="w-2.5 h-2.5 rounded-sm bg-purple-400 shadow-sm" />Referral</span>
            </div>
          </div>
        </div>

        {/* ── Row 3: Weekly Trend + Radar ── */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

          {/* Weekly Trend Line Chart */}
          <div
            className="lg:col-span-2 rounded-2xl p-5 animate-fade-up"
            style={{ ...glassCard, animationDelay: '0.3s' } as any}
          >
            <div className="flex items-center justify-between mb-1">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(34,197,94,0.12)' }}>
                  <TrendingUp className="w-[18px] h-[18px] text-emerald-500" />
                </div>
                <div>
                  <h2 className="text-base font-extrabold text-slate-800 tracking-tight">Weekly Trends</h2>
                  <p className="text-xs text-slate-500 font-medium mt-0.5">Patient volume, TEWS scores, and wait times</p>
                </div>
              </div>
              <div className="flex items-center gap-4 text-[11px] text-slate-500 font-medium">
                <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-indigo-500 shadow-sm" />Patients</span>
                <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-amber-400 shadow-sm" />Avg TEWS</span>
                <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-emerald-400 shadow-sm" />Wait (min)</span>
              </div>
            </div>
            <div className="mt-4" style={{ height: 240 }}>
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={weeklyTrendData} margin={{ top: 5, right: 10, left: -10, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke={isDark ? '#1e293b' : '#f1f5f9'} vertical={false} />
                  <XAxis dataKey="day" tick={{ fontSize: 11, fill: '#94a3b8', fontWeight: 500 }} axisLine={false} tickLine={false} />
                  <YAxis yAxisId="left" tick={{ fontSize: 11, fill: '#94a3b8', fontWeight: 500 }} axisLine={false} tickLine={false} />
                  <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 11, fill: '#94a3b8', fontWeight: 500 }} axisLine={false} tickLine={false} />
                  <Tooltip content={<CustomTooltip />} />
                  <Line yAxisId="left" type="monotone" dataKey="patients" name="Patients" stroke="#6366f1" strokeWidth={2.5} dot={{ fill: '#6366f1', r: 4, stroke: '#fff', strokeWidth: 2 }} activeDot={{ r: 6, fill: '#6366f1', stroke: '#fff', strokeWidth: 2.5 }} />
                  <Line yAxisId="right" type="monotone" dataKey="avgTEWS" name="Avg TEWS" stroke="#f59e0b" strokeWidth={2} strokeDasharray="5 5" dot={{ fill: '#f59e0b', r: 3, stroke: '#fff', strokeWidth: 2 }} />
                  <Line yAxisId="right" type="monotone" dataKey="waitTime" name="Wait (min)" stroke="#34d399" strokeWidth={2} dot={{ fill: '#34d399', r: 3, stroke: '#fff', strokeWidth: 2 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Performance Radar */}
          <div
            className="rounded-2xl p-5 animate-fade-up"
            style={{ ...glassCard, animationDelay: '0.35s' } as any}
          >
            <div className="flex items-center gap-3 mb-1">
              <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(99,102,241,0.12)' }}>
                <Activity className="w-[18px] h-[18px] text-indigo-500" />
              </div>
              <div>
                <h2 className="text-base font-extrabold text-slate-800 tracking-tight">Performance</h2>
                <p className="text-xs text-slate-500 font-medium mt-0.5">Department KPI overview</p>
              </div>
            </div>
            <div className="mt-3" style={{ height: 220 }}>
              <ResponsiveContainer width="100%" height="100%">
                <RadarChart data={performanceData} cx="50%" cy="50%" outerRadius="75%">
                  <PolarGrid stroke="#e2e8f0" strokeDasharray="3 3" />
                  <PolarAngleAxis dataKey="metric" tick={{ fontSize: 10, fill: '#64748b', fontWeight: 500 }} />
                  <PolarRadiusAxis tick={false} axisLine={false} domain={[0, 100]} />
                  <Radar name="Score" dataKey="value" stroke="#6366f1" fill="#6366f1" fillOpacity={0.15} strokeWidth={2} dot={{ fill: '#6366f1', r: 3, stroke: '#fff', strokeWidth: 1.5 }} />
                </RadarChart>
              </ResponsiveContainer>
            </div>
            {/* Score summary */}
            <div className="mt-3 pt-4 border-t border-gray-100/60 text-center">
              <span className="text-2xl font-extrabold text-indigo-600 animate-number-pop">
                {performanceData.length > 0 ? `${Math.round(performanceData.reduce((s, d) => s + d.value, 0) / performanceData.length)}%` : 'N/A'}
              </span>
              <p className="text-[11px] text-slate-400 font-medium mt-1">Overall Performance Score</p>
            </div>
          </div>
        </div>

        {/* ── Row 4: Available Reports ── */}
        <div
          className="rounded-2xl p-5 animate-fade-up"
          style={{ ...glassCard, animationDelay: '0.4s' } as any}
        >
          <div className="flex items-center gap-3 mb-5">
            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
              <FileText className="w-[18px] h-[18px] text-cyan-600" />
            </div>
            <div>
              <h2 className="text-base font-extrabold text-slate-800 tracking-tight">Available Reports</h2>
              <p className="text-xs text-slate-500 font-medium mt-0.5">Export and download detailed reports</p>
            </div>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {reportTypes.map((report) => {
              const Icon = report.icon;
              return (
                <button
                  key={report.id}
                  className="w-full flex items-center justify-between p-3.5 rounded-xl hover:-translate-y-1 transition-all duration-400 group cursor-pointer text-left"
                  style={{
                    background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.6)',
                    border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)',
                    boxShadow: isDark ? '0 2px 8px rgba(0,0,0,0.2)' : '0 2px 8px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)',
                  }}
                >
                  <div className="flex items-center gap-3">
                    <div
                      className="w-10 h-10 rounded-xl flex items-center justify-center group-hover:scale-110 transition-transform duration-400 flex-shrink-0"
                      style={{ backgroundColor: report.iconBg }}
                    >
                      <Icon className={`w-[18px] h-[18px] ${report.iconColor}`} />
                    </div>
                    <div>
                      <div className="text-[13px] font-bold text-slate-800">{report.name}</div>
                      <div className="text-[11px] text-slate-400 font-medium">{report.description}</div>
                    </div>
                  </div>
                  <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-50 group-hover:bg-cyan-50 border border-slate-200 group-hover:border-cyan-200 transition-all duration-300 flex-shrink-0 ml-3">
                    <Download className="w-3.5 h-3.5 text-slate-400 group-hover:text-cyan-600 transition-all duration-300" />
                    <span className="text-[10px] font-bold text-slate-500 group-hover:text-cyan-700 transition-all duration-300 uppercase tracking-wider">Export</span>
                  </div>
                </button>
              );
            })}
          </div>
        </div>

      </div>
    </div>
  );
}
