import { useState } from 'react';
import { FileText, Download, Calendar, TrendingUp, Users, AlertTriangle, BarChart3, Baby, Activity, Clock } from 'lucide-react';
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

// ── Mock data for charts ──────────────────────────────

const patientFlowData = [
  { time: '06:00', patients: 2, critical: 0, discharged: 1 },
  { time: '07:00', patients: 5, critical: 1, discharged: 1 },
  { time: '08:00', patients: 12, critical: 2, discharged: 3 },
  { time: '09:00', patients: 18, critical: 3, discharged: 5 },
  { time: '10:00', patients: 24, critical: 2, discharged: 8 },
  { time: '11:00', patients: 22, critical: 4, discharged: 12 },
  { time: '12:00', patients: 19, critical: 3, discharged: 14 },
  { time: '13:00', patients: 26, critical: 5, discharged: 16 },
  { time: '14:00', patients: 30, critical: 4, discharged: 20 },
  { time: '15:00', patients: 28, critical: 3, discharged: 23 },
  { time: '16:00', patients: 21, critical: 2, discharged: 26 },
  { time: '17:00', patients: 16, critical: 1, discharged: 28 },
  { time: '18:00', patients: 10, critical: 1, discharged: 30 },
];

const hourlyArrivals = [
  { hour: '6AM', ambulance: 1, walkin: 1, referral: 0 },
  { hour: '7AM', ambulance: 2, walkin: 2, referral: 1 },
  { hour: '8AM', ambulance: 3, walkin: 6, referral: 3 },
  { hour: '9AM', ambulance: 4, walkin: 8, referral: 6 },
  { hour: '10AM', ambulance: 2, walkin: 12, referral: 10 },
  { hour: '11AM', ambulance: 3, walkin: 10, referral: 9 },
  { hour: '12PM', ambulance: 1, walkin: 9, referral: 9 },
  { hour: '1PM', ambulance: 5, walkin: 12, referral: 9 },
  { hour: '2PM', ambulance: 4, walkin: 14, referral: 12 },
  { hour: '3PM', ambulance: 3, walkin: 13, referral: 12 },
  { hour: '4PM', ambulance: 2, walkin: 10, referral: 9 },
  { hour: '5PM', ambulance: 1, walkin: 8, referral: 7 },
  { hour: '6PM', ambulance: 1, walkin: 5, referral: 4 },
];

const weeklyTrendData = [
  { day: 'Mon', patients: 42, avgTEWS: 3.2, waitTime: 18 },
  { day: 'Tue', patients: 38, avgTEWS: 2.8, waitTime: 15 },
  { day: 'Wed', patients: 51, avgTEWS: 3.5, waitTime: 22 },
  { day: 'Thu', patients: 45, avgTEWS: 3.1, waitTime: 19 },
  { day: 'Fri', patients: 56, avgTEWS: 3.8, waitTime: 25 },
  { day: 'Sat', patients: 34, avgTEWS: 2.6, waitTime: 12 },
  { day: 'Sun', patients: 29, avgTEWS: 2.4, waitTime: 10 },
];

const performanceData = [
  { metric: 'Triage Speed', value: 88, fullMark: 100 },
  { metric: 'Accuracy', value: 95, fullMark: 100 },
  { metric: 'Throughput', value: 72, fullMark: 100 },
  { metric: 'Response', value: 91, fullMark: 100 },
  { metric: 'Compliance', value: 85, fullMark: 100 },
  { metric: 'Satisfaction', value: 78, fullMark: 100 },
];

// Custom tooltip component
function CustomTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white/95 backdrop-blur-sm rounded-xl border border-gray-200 shadow-lg px-3.5 py-2.5">
      <p className="text-xs font-semibold text-gray-800 mb-1">{label}</p>
      {payload.map((entry: any, i: number) => (
        <p key={i} className="text-[11px] flex items-center gap-1.5" style={{ color: entry.color }}>
          <span className="w-2 h-2 rounded-full" style={{ backgroundColor: entry.color }} />
          {entry.name}: <span className="font-semibold">{entry.value}</span>
        </p>
      ))}
    </div>
  );
}

export function ReportsView() {
  const patients = usePatientStore((state) => state.patients);
  const [dateRange, setDateRange] = useState('today');

  // Mock patients for demonstration if none exist
  const mockPatients = patients.length === 0 ? [
    { id: '1', category: 'RED', tewsScore: 8, isPediatric: false },
    { id: '2', category: 'RED', tewsScore: 7, isPediatric: false },
    { id: '3', category: 'ORANGE', tewsScore: 5, isPediatric: true },
    { id: '4', category: 'YELLOW', tewsScore: 3, isPediatric: false },
    { id: '5', category: 'YELLOW', tewsScore: 4, isPediatric: true },
    { id: '6', category: 'YELLOW', tewsScore: 3, isPediatric: false },
    { id: '7', category: 'GREEN', tewsScore: 1, isPediatric: false },
    { id: '8', category: 'GREEN', tewsScore: 2, isPediatric: false },
    { id: '9', category: 'GREEN', tewsScore: 1, isPediatric: true },
    { id: '10', category: 'GREEN', tewsScore: 0, isPediatric: false },
  ] as any[] : patients;

  // Calculate statistics
  const stats = {
    totalPatients: mockPatients.length,
    averageTEWS: mockPatients.filter((p) => p.tewsScore !== undefined).length > 0
      ? (mockPatients.reduce((sum, p) => sum + (p.tewsScore || 0), 0) /
        mockPatients.filter((p) => p.tewsScore !== undefined).length).toFixed(1)
      : '0',
    criticalCases: mockPatients.filter((p) => p.category === 'RED').length,
    pediatricCases: mockPatients.filter((p) => p.isPediatric).length,
    categoryBreakdown: {
      RED: mockPatients.filter((p) => p.category === 'RED').length,
      ORANGE: mockPatients.filter((p) => p.category === 'ORANGE').length,
      YELLOW: mockPatients.filter((p) => p.category === 'YELLOW').length,
      GREEN: mockPatients.filter((p) => p.category === 'GREEN').length,
      BLUE: mockPatients.filter((p) => p.category === 'BLUE').length,
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
    { id: 'daily', name: 'Daily Triage Summary', icon: FileText, description: 'Complete daily activity report' },
    { id: 'performance', name: 'Performance Metrics', icon: TrendingUp, description: 'TEWS scores and wait times' },
    { id: 'patient', name: 'Patient Demographics', icon: Users, description: 'Age, gender, and complaint analysis' },
    { id: 'critical', name: 'Critical Cases Review', icon: AlertTriangle, description: 'RED category incident reports' },
  ];

  return (
    <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-5 animate-fade-in">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-xl font-extrabold text-gray-900 flex items-center gap-3 tracking-tight">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-slate-800 to-slate-700 flex items-center justify-center shadow-lg shadow-slate-800/20">
              <BarChart3 className="w-5 h-5 text-white" />
            </div>
            Reports & Analytics
          </h1>
          <p className="text-sm text-gray-500 mt-1 font-medium">
            Comprehensive triage system reports and insights
          </p>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {[
          { label: 'Total Patients', value: stats.totalPatients, icon: Users, iconBg: 'bg-gradient-to-br from-cyan-600 to-cyan-500' },
          { label: 'Average TEWS', value: stats.averageTEWS, icon: TrendingUp, iconBg: 'bg-gradient-to-br from-cyan-500 to-cyan-600' },
          { label: 'Critical Cases', value: stats.criticalCases, icon: AlertTriangle, iconBg: 'bg-gradient-to-br from-red-500 to-red-600' },
          { label: 'Pediatric Cases', value: stats.pediatricCases, icon: Baby, iconBg: 'bg-gradient-to-br from-pink-500 to-pink-600' },
        ].map((stat) => {
          const Icon = stat.icon;
          return (
            <div key={stat.label} className="glass-card relative rounded-3xl hover:shadow-card-hover hover:-translate-y-0.5 transition-all duration-500 overflow-hidden group animate-fade-up" style={{ animationDelay: `${0.05 * (['Total Patients', 'Average TEWS', 'Critical Cases', 'Pediatric Cases'].indexOf(stat.label))}s` }}>
              <div className="p-4">
                <div className={`w-10 h-10 rounded-full ${stat.iconBg} flex items-center justify-center mb-3 shadow-sm`}>
                  <Icon className="w-5 h-5 text-white" />
                </div>
                <p className="text-lg font-bold text-gray-900 mb-1">{stat.value}</p>
                <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide">{stat.label}</p>
              </div>
            </div>
          );
        })}
      </div>

      {/* Date Range Filter */}
      <div className="glass-card rounded-3xl p-5 animate-fade-up" style={{ animationDelay: '0.2s' }}>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2 text-sm font-medium text-gray-600">
            <Calendar className="w-4 h-4 text-cyan-600" />
            Report Period:
          </div>
          <div className="flex items-center gap-2">
            {['today', 'week', 'month', 'year'].map((range) => (
              <button
                key={range}
                onClick={() => setDateRange(range)}
                className={`px-3 py-1.5 text-xs font-medium rounded-lg transition-all ${dateRange === range
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-lg shadow-slate-800/20'
                    : 'bg-white/60 backdrop-blur-sm text-gray-600 hover:bg-white/80 border border-white/60 shadow-sm'
                  }`}
              >
                {range.charAt(0).toUpperCase() + range.slice(1)}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* ── Row 1: Patient Flow Area Chart (full width) ── */}
      <div className="glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.25s' }}>
        <div className="flex items-center justify-between mb-1">
          <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2">
            <Activity className="w-4 h-4 text-cyan-600" />
            Patient Flow Over Time
          </h2>
          <div className="flex items-center gap-4 text-[11px] text-gray-500">
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-indigo-500" />Active</span>
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-red-400" />Critical</span>
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-emerald-400" />Discharged</span>
          </div>
        </div>
        <p className="text-xs text-gray-400 mb-4">Real-time patient census throughout the day</p>
        <div className="h-48">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={patientFlowData} margin={{ top: 5, right: 10, left: -10, bottom: 0 }}>
              <defs>
                <linearGradient id="gradientPatients" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#6366f1" stopOpacity={0.3} />
                  <stop offset="100%" stopColor="#6366f1" stopOpacity={0.02} />
                </linearGradient>
                <linearGradient id="gradientCritical" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#f87171" stopOpacity={0.3} />
                  <stop offset="100%" stopColor="#f87171" stopOpacity={0.02} />
                </linearGradient>
                <linearGradient id="gradientDischarged" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#34d399" stopOpacity={0.3} />
                  <stop offset="100%" stopColor="#34d399" stopOpacity={0.02} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
              <XAxis dataKey="time" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
              <Tooltip content={<CustomTooltip />} />
              <Area type="monotone" dataKey="patients" name="Active" stroke="#6366f1" strokeWidth={2.5} fill="url(#gradientPatients)" dot={false} activeDot={{ r: 4, fill: '#6366f1', stroke: '#fff', strokeWidth: 2 }} />
              <Area type="monotone" dataKey="critical" name="Critical" stroke="#f87171" strokeWidth={2} fill="url(#gradientCritical)" dot={false} activeDot={{ r: 4, fill: '#f87171', stroke: '#fff', strokeWidth: 2 }} />
              <Area type="monotone" dataKey="discharged" name="Discharged" stroke="#34d399" strokeWidth={2} fill="url(#gradientDischarged)" dot={false} activeDot={{ r: 4, fill: '#34d399', stroke: '#fff', strokeWidth: 2 }} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* ── Row 2: Donut Chart + Bar Chart ── */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Triage Category Donut */}
        <div className="glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.3s' }}>
          <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-1">
            <BarChart3 className="w-4 h-4 text-cyan-600" />
            Triage Category Distribution
          </h2>
          <p className="text-xs text-gray-400 mb-4">Breakdown by severity level</p>
          <div className="h-44">
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
                    <span className="text-xs text-gray-600 ml-1">{value}</span>
                  )}
                />
              </PieChart>
            </ResponsiveContainer>
          </div>
          {/* Mini bar breakdown below */}
          <div className="space-y-2 mt-2">
            {(['RED', 'ORANGE', 'YELLOW', 'GREEN'] as TriageCategory[]).map((category) => {
              const count = stats.categoryBreakdown[category];
              const percentage = stats.totalPatients > 0 ? (count / stats.totalPatients) * 100 : 0;
              return (
                <div key={category} className="flex items-center gap-2">
                  <Badge category={category} size="sm" />
                  <div className="flex-1 bg-gray-100 rounded-full h-1.5">
                    <div className="h-1.5 rounded-full transition-all duration-500" style={{ width: `${percentage}%`, backgroundColor: getCategoryColor(category) }} />
                  </div>
                  <span className="text-[10px] font-semibold text-gray-500 w-8 text-right">{percentage.toFixed(0)}%</span>
                </div>
              );
            })}
          </div>
        </div>

        {/* Hourly Arrivals Stacked Bar Chart */}
        <div className="glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.35s' }}>
          <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-1">
            <Clock className="w-4 h-4 text-cyan-600" />
            Hourly Arrivals by Mode
          </h2>
          <p className="text-xs text-gray-400 mb-4">Breakdown of how patients arrive</p>
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={hourlyArrivals} margin={{ top: 5, right: 10, left: -10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
                <XAxis dataKey="hour" tick={{ fontSize: 10, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                <Tooltip content={<CustomTooltip />} />
                <Bar dataKey="ambulance" name="Ambulance" stackId="arrivals" fill="#ef4444" radius={[0, 0, 0, 0]} />
                <Bar dataKey="walkin" name="Walk-in" stackId="arrivals" fill="#6366f1" radius={[0, 0, 0, 0]} />
                <Bar dataKey="referral" name="Referral" stackId="arrivals" fill="#a78bfa" radius={[3, 3, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="flex items-center justify-center gap-5 mt-3 text-[11px] text-gray-500">
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm bg-red-500" />Ambulance</span>
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm bg-indigo-500" />Walk-in</span>
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm bg-purple-400" />Referral</span>
          </div>
        </div>
      </div>

      {/* ── Row 3: Weekly Trend + Radar ── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Weekly Trend Line Chart */}
        <div className="lg:col-span-2 glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.4s' }}>
          <div className="flex items-center justify-between mb-1">
            <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2">
              <TrendingUp className="w-4 h-4 text-cyan-600" />
              Weekly Trends
            </h2>
            <div className="flex items-center gap-4 text-[11px] text-gray-500">
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-indigo-500" />Patients</span>
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-amber-400" />Avg TEWS</span>
              <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-full bg-emerald-400" />Wait (min)</span>
            </div>
          </div>
          <p className="text-xs text-gray-400 mb-4">Patient volume, TEWS scores, and average wait times</p>
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={weeklyTrendData} margin={{ top: 5, right: 10, left: -10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" vertical={false} />
                <XAxis dataKey="day" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                <YAxis yAxisId="left" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                <Tooltip content={<CustomTooltip />} />
                <Line yAxisId="left" type="monotone" dataKey="patients" name="Patients" stroke="#6366f1" strokeWidth={2.5} dot={{ fill: '#6366f1', r: 4, stroke: '#fff', strokeWidth: 2 }} activeDot={{ r: 6, fill: '#6366f1', stroke: '#fff', strokeWidth: 2 }} />
                <Line yAxisId="right" type="monotone" dataKey="avgTEWS" name="Avg TEWS" stroke="#f59e0b" strokeWidth={2} strokeDasharray="5 5" dot={{ fill: '#f59e0b', r: 3, stroke: '#fff', strokeWidth: 2 }} />
                <Line yAxisId="right" type="monotone" dataKey="waitTime" name="Wait (min)" stroke="#34d399" strokeWidth={2} dot={{ fill: '#34d399', r: 3, stroke: '#fff', strokeWidth: 2 }} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Performance Radar */}
        <div className="glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.45s' }}>
          <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-1">
            <Activity className="w-4 h-4 text-cyan-600" />
            Performance Score
          </h2>
          <p className="text-xs text-gray-400 mb-4">Department KPI overview</p>
          <div className="h-56">
            <ResponsiveContainer width="100%" height="100%">
              <RadarChart data={performanceData} cx="50%" cy="50%" outerRadius="75%">
                <PolarGrid stroke="#e2e8f0" />
                <PolarAngleAxis dataKey="metric" tick={{ fontSize: 10, fill: '#64748b' }} />
                <PolarRadiusAxis tick={false} axisLine={false} domain={[0, 100]} />
                <Radar name="Score" dataKey="value" stroke="#6366f1" fill="#6366f1" fillOpacity={0.2} strokeWidth={2} dot={{ fill: '#6366f1', r: 3 }} />
              </RadarChart>
            </ResponsiveContainer>
          </div>
          {/* Score summary */}
          <div className="mt-2 text-center">
            <span className="text-lg font-bold text-cyan-600">
              {Math.round(performanceData.reduce((s, d) => s + d.value, 0) / performanceData.length)}%
            </span>
            <p className="text-[11px] text-gray-400 mt-0.5">Overall Performance Score</p>
          </div>
        </div>
      </div>

      {/* ── Row 4: Available Reports ── */}
      <div className="glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.5s' }}>
        <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-5">
          <FileText className="w-4 h-4 text-cyan-600" />
          Available Reports
        </h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          {reportTypes.map((report) => {
            const Icon = report.icon;
            return (
              <div
                key={report.id}
                className="flex items-center gap-3 p-3 rounded-2xl border border-white/60 bg-white/40 backdrop-blur-sm hover:border-cyan-300 hover:bg-cyan-50/30 hover:shadow-md hover:-translate-y-0.5 transition-all duration-300 cursor-pointer group"
              >
                <div className="w-9 h-9 rounded-lg bg-gray-100 group-hover:bg-cyan-50 flex items-center justify-center transition-colors flex-shrink-0">
                  <Icon className="w-4 h-4 text-gray-500 group-hover:text-cyan-600 transition-colors" />
                </div>
                <div className="flex-1 min-w-0">
                  <h3 className="text-sm font-medium text-gray-900">{report.name}</h3>
                  <p className="text-xs text-gray-500">{report.description}</p>
                </div>
                <button className="inline-flex items-center gap-1 px-2.5 py-1.5 text-[10px] font-medium text-cyan-700 bg-cyan-50 hover:bg-cyan-100 border border-cyan-200 rounded-lg transition-colors opacity-0 group-hover:opacity-100">
                  <Download className="w-3 h-3" />
                  Export
                </button>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
