import { useState, useMemo } from 'react';
import {
  ScrollText, Search, Download, Calendar, Clock,
  UserPlus, Edit, Stethoscope, CheckCircle, ShieldAlert,
  Activity, Users, AlertTriangle, ChevronDown, ChevronRight,
  FileText, Shield,
} from 'lucide-react';
import { useAuditStore, AuditFilters } from '@/store/auditStore';
import { usePatientStore } from '@/store/patientStore';
import { AuditAction, AuditLogEntry } from '@/types';
import { formatDistanceToNow, format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

// Action metadata for UI
const ACTION_CONFIG: Record<AuditAction, { icon: any; color: string; bg: string; label: string }> = {
  PATIENT_REGISTERED:    { icon: UserPlus,      color: 'text-cyan-600',    bg: 'rgba(6,182,212,0.1)',    label: 'Patient Registered' },
  PATIENT_UPDATED:       { icon: Edit,          color: 'text-blue-600',    bg: 'rgba(59,130,246,0.1)',   label: 'Patient Updated' },
  TRIAGE_STARTED:        { icon: Stethoscope,   color: 'text-violet-600',  bg: 'rgba(139,92,246,0.1)',   label: 'Triage Started' },
  TRIAGE_COMPLETED:      { icon: CheckCircle,   color: 'text-emerald-600', bg: 'rgba(34,197,94,0.1)',    label: 'Triage Completed' },
  CATEGORY_ASSIGNED:     { icon: Shield,        color: 'text-indigo-600',  bg: 'rgba(99,102,241,0.1)',   label: 'Category Assigned' },
  CATEGORY_OVERRIDDEN:   { icon: ShieldAlert,   color: 'text-amber-600',   bg: 'rgba(245,158,11,0.1)',   label: 'Category Overridden' },
  VITALS_RECORDED:       { icon: Activity,      color: 'text-rose-600',    bg: 'rgba(244,63,94,0.1)',    label: 'Vitals Recorded' },
  ALERT_ACKNOWLEDGED:    { icon: AlertTriangle, color: 'text-orange-600',  bg: 'rgba(249,115,22,0.1)',   label: 'Alert Acknowledged' },
  NURSE_ASSIGNED:        { icon: Users,         color: 'text-teal-600',    bg: 'rgba(20,184,166,0.1)',   label: 'Nurse Assigned' },
  DEMOGRAPHICS_EDITED:   { icon: FileText,      color: 'text-slate-600',   bg: 'rgba(100,116,139,0.1)',  label: 'Demographics Edited' },
};


export function AuditTrail() {
  const { glassCard, glassInner, isDark } = useTheme();
  const auditEntries = useAuditStore((s) => s.entries);
  const getFilteredEntries = useAuditStore((s) => s.getFilteredEntries);
  const exportToCSV = useAuditStore((s) => s.exportToCSV);
  const getAuditStats = useAuditStore((s) => s.getAuditStats);
  const patients = usePatientStore((s) => s.patients);

  const [searchQuery, setSearchQuery] = useState('');
  const [selectedActions] = useState<AuditAction[]>([]);
  const [showFilters, setShowFilters] = useState(false);
  const [expandedEntry, setExpandedEntry] = useState<string | null>(null);
  const [dateRange, setDateRange] = useState<{ start: string; end: string }>({ start: '', end: '' });

  const displayEntries = useMemo(() => {
    if (auditEntries.length === 0) return [];
    const filters: AuditFilters = {
      search: searchQuery || undefined,
      actions: selectedActions.length > 0 ? selectedActions : undefined,
      startDate: dateRange.start ? new Date(dateRange.start) : undefined,
      endDate: dateRange.end ? new Date(dateRange.end + 'T23:59:59') : undefined,
    };
    return getFilteredEntries(filters);
  }, [auditEntries, searchQuery, selectedActions, dateRange, getFilteredEntries]);

  const handleExportCSV = () => {
    const csv = exportToCSV(displayEntries);
    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `smarttriage-audit-${format(new Date(), 'yyyy-MM-dd-HHmm')}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const getPatientName = (patientId?: string) => {
    if (!patientId) return null;
    const patient = patients.find(p => p.id === patientId);
    return patient?.fullName || patientId;
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* ── Dark Header Banner ── */}
        <div className="glass-card-dark rounded-3xl overflow-hidden animate-fade-up">
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-gradient-to-br from-emerald-500/30 to-teal-500/30 rounded-2xl flex items-center justify-center shadow-lg border border-emerald-400/20">
                  <ScrollText className="w-6 h-6 text-emerald-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Audit Trail & Compliance</h1>
                  <p className="text-white/70 text-xs font-medium">Complete clinical action history with regulatory compliance tracking</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <button
                  onClick={handleExportCSV}
                  className="flex items-center gap-2 px-4 py-2 bg-white/15 hover:bg-white/25 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all duration-300 border border-white/10"
                >
                  <Download className="w-3.5 h-3.5" />
                  Export CSV
                </button>
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                  <span className="text-xs font-semibold text-white/90">Module 6</span>
                </div>
              </div>
            </div>
          </div>
        </div>



        {/* ── Search & Filter Bar ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={{ ...glassCard, animationDelay: '0.15s' } as any}>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search audit entries by details, clinician, or action..."
                className="w-full pl-10 pr-4 py-2.5 rounded-xl text-sm text-slate-800 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-emerald-500/20 transition-all duration-300"
                style={{
                  background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.7)',
                  border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.5)',
                  boxShadow: isDark ? '0 1px 4px rgba(0,0,0,0.2)' : '0 1px 4px rgba(0,0,0,0.03), inset 0 1px 0 rgba(255,255,255,0.8)',
                }}
              />
            </div>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`flex items-center gap-1.5 px-3.5 py-2.5 text-[11px] font-bold rounded-lg transition-all duration-300 ${
                showFilters
                  ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                  : 'text-slate-500 hover:bg-white/60'
              }`}
            >
              <Calendar className="w-3 h-3" />
              Date Range
              {showFilters ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            </button>
          </div>

          {/* Date Range Inputs */}
          {showFilters && (
            <div className="flex items-center gap-3 mt-3 pt-3 border-t border-gray-100/60">
              <div className="flex items-center gap-2">
                <span className="text-[11px] font-semibold text-slate-500">From:</span>
                <input
                  type="date"
                  value={dateRange.start}
                  onChange={(e) => setDateRange(prev => ({ ...prev, start: e.target.value }))}
                  className="px-3 py-1.5 rounded-lg text-xs text-slate-700 focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
                  style={{
                    background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.7)',
                    border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.5)',
                  }}
                />
              </div>
              <div className="flex items-center gap-2">
                <span className="text-[11px] font-semibold text-slate-500">To:</span>
                <input
                  type="date"
                  value={dateRange.end}
                  onChange={(e) => setDateRange(prev => ({ ...prev, end: e.target.value }))}
                  className="px-3 py-1.5 rounded-lg text-xs text-slate-700 focus:outline-none focus:ring-2 focus:ring-emerald-500/20"
                  style={{
                    background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.7)',
                    border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.5)',
                  }}
                />
              </div>
              {(dateRange.start || dateRange.end) && (
                <button
                  onClick={() => setDateRange({ start: '', end: '' })}
                  className="text-[10px] font-bold text-cyan-600 hover:text-cyan-700"
                >
                  Clear dates
                </button>
              )}
            </div>
          )}
        </div>



        {/* ── Audit Entries List ── */}
        <div className="space-y-2">
          <div className="flex items-center justify-between px-1 animate-fade-up" style={{ animationDelay: '0.22s' } as any}>
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(34,197,94,0.12)' }}>
                <ScrollText className="w-3.5 h-3.5 text-emerald-500" />
              </div>
              <div>
                <h3 className="text-sm font-extrabold text-slate-800">Audit Log</h3>
                <p className="text-[10px] text-slate-400 font-medium">{displayEntries.length} entries</p>
              </div>
            </div>
          </div>

          {displayEntries.length === 0 ? (
            <div className="rounded-2xl p-12 text-center animate-fade-up" style={{ ...glassCard, animationDelay: '0.26s' } as any}>
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                <ScrollText className="w-8 h-8 text-slate-300" />
              </div>
              <p className="text-sm font-bold text-slate-600">No Audit Entries</p>
              <p className="text-xs text-slate-400 mt-1">Clinical actions will appear here as they are performed</p>
            </div>
          ) : (
            <div className="space-y-2">
              {displayEntries.map((entry, idx) => {
                const config = ACTION_CONFIG[entry.action] || ACTION_CONFIG.PATIENT_REGISTERED;
                const Icon = config.icon;
                const isExpanded = expandedEntry === entry.id;
                const patientName = getPatientName(entry.patientId);

                return (
                  <div
                    key={entry.id}
                    className="rounded-2xl overflow-hidden transition-all duration-300 animate-fade-up hover:-translate-y-0.5"
                    style={{ ...glassCard, animationDelay: `${0.26 + idx * 0.03}s` } as any}
                  >
                    <button
                      onClick={() => setExpandedEntry(isExpanded ? null : entry.id)}
                      className="w-full text-left p-4"
                    >
                      <div className="flex items-center gap-3">
                        {/* Action Icon */}
                        <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: config.bg }}>
                          <Icon className={`w-4.5 h-4.5 ${config.color}`} />
                        </div>

                        {/* Content */}
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-0.5">
                            <span
                              className={`text-[10px] font-bold ${config.color} px-2 py-0.5 rounded-md uppercase tracking-wider`}
                              style={{ background: config.bg }}
                            >
                              {config.label}
                            </span>
                            <span className="text-[10px] text-slate-400 flex items-center gap-1">
                              <Clock className="w-2.5 h-2.5" />
                              {formatDistanceToNow(new Date(entry.timestamp), { addSuffix: true })}
                            </span>
                          </div>
                          <p className="text-[12px] font-semibold text-slate-700 truncate">{entry.details}</p>
                          <div className="flex items-center gap-3 mt-1">
                            <span className="text-[10px] text-slate-400">
                              by <span className="font-semibold text-slate-500">{entry.performedByName}</span>
                            </span>
                            {patientName && (
                              <span className="text-[10px] text-slate-400">
                                Patient: <span className="font-semibold text-slate-500">{patientName}</span>
                              </span>
                            )}
                          </div>
                        </div>

                        {/* Expand Arrow */}
                        <div className="flex-shrink-0">
                          {isExpanded ? (
                            <ChevronDown className="w-4 h-4 text-slate-400" />
                          ) : (
                            <ChevronRight className="w-4 h-4 text-slate-400" />
                          )}
                        </div>
                      </div>
                    </button>

                    {/* Expanded Details */}
                    {isExpanded && (
                      <div className="px-4 pb-4 pt-1 border-t border-gray-100/60">
                        <div className="grid grid-cols-2 gap-3 mt-2">
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Entry ID</p>
                            <p className="text-[11px] font-mono text-slate-700">{entry.id}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Timestamp</p>
                            <p className="text-[11px] text-slate-700 font-semibold">{format(new Date(entry.timestamp), 'yyyy-MM-dd HH:mm:ss')}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Performed By</p>
                            <p className="text-[11px] text-slate-700 font-semibold">{entry.performedByName} ({entry.performedBy})</p>
                          </div>
                          {entry.patientId && (
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Patient</p>
                              <p className="text-[11px] text-slate-700 font-semibold">{patientName}</p>
                            </div>
                          )}
                          {entry.previousValue && (
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Previous Value</p>
                              <p className="text-[11px] text-red-600 font-semibold">{entry.previousValue}</p>
                            </div>
                          )}
                          {entry.newValue && (
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">New Value</p>
                              <p className="text-[11px] text-emerald-600 font-semibold">{entry.newValue}</p>
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
        </div>
      </div>
    </div>
  );
}
