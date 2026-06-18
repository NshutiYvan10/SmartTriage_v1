import { useState, useMemo, useEffect, useCallback } from 'react';
import {
  ScrollText, Search, Download, Calendar, Clock,
  CheckCircle, AlertTriangle, ChevronDown, ChevronRight, Loader2, RefreshCw,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { auditApi, AuditLogEntry } from '@/api/audit';
import { formatDistanceToNow, format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

const startIso = (d: string) => (d ? new Date(`${d}T00:00:00`).toISOString() : undefined);
const endIso = (d: string) => (d ? new Date(`${d}T23:59:59`).toISOString() : undefined);

export function AuditTrail() {
  const { glassCard, glassInner, isDark } = useTheme();
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';

  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const [expandedEntry, setExpandedEntry] = useState<string | null>(null);
  const [dateRange, setDateRange] = useState<{ start: string; end: string }>({ start: '', end: '' });
  const [downloading, setDownloading] = useState(false);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true); setError(null);
    try {
      const res = await auditApi.list(hospitalId, {
        size: 200,
        from: startIso(dateRange.start),
        to: endIso(dateRange.end),
      });
      setEntries(res.content || []);
    } catch (e) {
      setError('Failed to load the audit log. You must be an administrator or auditor for this hospital.');
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, dateRange.start, dateRange.end]);

  useEffect(() => { load(); }, [load]);

  const displayEntries = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return entries;
    return entries.filter((e) =>
      (e.actorName || '').toLowerCase().includes(q) ||
      (e.action || '').toLowerCase().includes(q) ||
      (e.path || '').toLowerCase().includes(q) ||
      (e.actorRole || '').toLowerCase().includes(q));
  }, [entries, searchQuery]);

  const handleExportCSV = async () => {
    if (!hospitalId) return;
    setDownloading(true);
    try {
      await auditApi.exportCsv(hospitalId, startIso(dateRange.start), endIso(dateRange.end));
    } catch {
      setError('Failed to export the audit CSV.');
    } finally {
      setDownloading(false);
    }
  };

  const inputStyle = {
    background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.7)',
    border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.5)',
  } as React.CSSProperties;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="glass-card-dark rounded-3xl overflow-hidden animate-fade-up">
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-gradient-to-br from-emerald-500/30 to-teal-500/30 rounded-2xl flex items-center justify-center shadow-lg border border-emerald-400/20">
                  <ScrollText className="w-6 h-6 text-emerald-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Audit Trail &amp; Compliance</h1>
                  <p className="text-white/70 text-xs font-medium">Server-backed log of every state-changing action — who, what, when, outcome</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <button
                  onClick={load}
                  className="w-9 h-9 rounded-xl bg-white/10 hover:bg-white/20 flex items-center justify-center transition-all"
                  title="Refresh"
                >
                  <RefreshCw className={`w-4 h-4 text-white ${loading ? 'animate-spin' : ''}`} />
                </button>
                <button
                  onClick={handleExportCSV}
                  disabled={downloading}
                  className="flex items-center gap-2 px-4 py-2 bg-white/15 hover:bg-white/25 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all border border-white/10 disabled:opacity-50"
                >
                  {downloading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
                  Export CSV
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Search + date filter */}
        <div className="rounded-2xl p-4 animate-fade-up" style={{ ...glassCard, animationDelay: '0.15s' } as React.CSSProperties}>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search by actor, role, action, or path..."
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={inputStyle}
              />
            </div>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`flex items-center gap-1.5 px-3.5 py-2.5 text-[11px] font-bold rounded-lg transition-all ${showFilters ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md' : 'text-slate-500 hover:bg-white/60'}`}
            >
              <Calendar className="w-3 h-3" /> Date Range
              {showFilters ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            </button>
          </div>
          {showFilters && (
            <div className="flex items-center gap-3 mt-3 pt-3 border-t border-gray-100/60 flex-wrap">
              <div className="flex items-center gap-2">
                <span className="text-[11px] font-semibold text-slate-500">From:</span>
                <input type="date" value={dateRange.start} onChange={(e) => setDateRange((p) => ({ ...p, start: e.target.value }))}
                  className="px-3 py-1.5 rounded-lg text-xs text-slate-700 focus:outline-none" style={inputStyle} />
              </div>
              <div className="flex items-center gap-2">
                <span className="text-[11px] font-semibold text-slate-500">To:</span>
                <input type="date" value={dateRange.end} onChange={(e) => setDateRange((p) => ({ ...p, end: e.target.value }))}
                  className="px-3 py-1.5 rounded-lg text-xs text-slate-700 focus:outline-none" style={inputStyle} />
              </div>
              {(dateRange.start || dateRange.end) && (
                <button onClick={() => setDateRange({ start: '', end: '' })} className="text-[10px] font-bold text-cyan-600 hover:text-cyan-700">Clear dates</button>
              )}
            </div>
          )}
        </div>

        {error && (
          <div className="rounded-xl p-3 text-xs font-medium text-rose-600" style={{ ...glassInner, border: '1px solid rgba(244,63,94,0.3)' }}>{error}</div>
        )}

        {/* List */}
        <div className="space-y-2">
          <div className="flex items-center justify-between px-1">
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(34,197,94,0.12)' }}>
                <ScrollText className="w-3.5 h-3.5 text-emerald-500" />
              </div>
              <div>
                <h3 className={`text-sm font-extrabold ${isDark ? 'text-white' : 'text-slate-800'}`}>Audit Log</h3>
                <p className="text-[10px] text-slate-400 font-medium">{displayEntries.length} entries</p>
              </div>
            </div>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-16"><Loader2 className="w-7 h-7 animate-spin text-emerald-500" /></div>
          ) : displayEntries.length === 0 ? (
            <div className="rounded-2xl p-12 text-center" style={glassCard}>
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                <ScrollText className="w-8 h-8 text-slate-300" />
              </div>
              <p className="text-sm font-bold text-slate-600">No Audit Entries</p>
              <p className="text-xs text-slate-400 mt-1">State-changing actions appear here as they are performed</p>
            </div>
          ) : (
            <div className="space-y-2">
              {displayEntries.map((entry) => {
                const failed = entry.outcome === 'FAILED';
                const Icon = failed ? AlertTriangle : CheckCircle;
                const color = failed ? 'text-rose-600' : 'text-emerald-600';
                const bg = failed ? 'rgba(244,63,94,0.1)' : 'rgba(34,197,94,0.1)';
                const isExpanded = expandedEntry === entry.id;
                return (
                  <div key={entry.id} className="rounded-2xl overflow-hidden transition-all hover:-translate-y-0.5" style={glassCard}>
                    <button onClick={() => setExpandedEntry(isExpanded ? null : entry.id)} className="w-full text-left p-4">
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: bg }}>
                          <Icon className={`w-4 h-4 ${color}`} />
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                            <span className={`text-[10px] font-bold ${color} px-2 py-0.5 rounded-md uppercase tracking-wider`} style={{ background: bg }}>
                              {entry.outcome}{entry.statusCode ? ` · ${entry.statusCode}` : ''}
                            </span>
                            <span className="text-[10px] text-slate-400 flex items-center gap-1">
                              <Clock className="w-2.5 h-2.5" />
                              {entry.timestamp ? formatDistanceToNow(new Date(entry.timestamp), { addSuffix: true }) : ''}
                            </span>
                          </div>
                          <p className={`text-[12px] font-semibold truncate ${isDark ? 'text-slate-200' : 'text-slate-700'}`}>{entry.action}</p>
                          <div className="flex items-center gap-3 mt-1">
                            <span className="text-[10px] text-slate-400">
                              by <span className="font-semibold text-slate-500">{entry.actorName}</span>
                              {entry.actorRole ? <span className="text-slate-400"> ({entry.actorRole})</span> : null}
                            </span>
                          </div>
                        </div>
                        <div className="flex-shrink-0">
                          {isExpanded ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevronRight className="w-4 h-4 text-slate-400" />}
                        </div>
                      </div>
                    </button>
                    {isExpanded && (
                      <div className="px-4 pb-4 pt-1 border-t border-gray-100/60">
                        <div className="grid grid-cols-2 gap-3 mt-2">
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Timestamp</p>
                            <p className="text-[11px] text-slate-700 font-semibold">{entry.timestamp ? format(new Date(entry.timestamp), 'yyyy-MM-dd HH:mm:ss') : '—'}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Actor</p>
                            <p className="text-[11px] text-slate-700 font-semibold">{entry.actorName}{entry.actorRole ? ` (${entry.actorRole})` : ''}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Method</p>
                            <p className="text-[11px] font-mono text-slate-700">{entry.httpMethod}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Status</p>
                            <p className={`text-[11px] font-semibold ${failed ? 'text-rose-600' : 'text-emerald-600'}`}>{entry.statusCode ?? '—'} · {entry.outcome}</p>
                          </div>
                          <div className="rounded-xl p-3 col-span-2" style={glassInner}>
                            <p className="text-[9px] font-bold text-slate-400 uppercase tracking-wider mb-1">Path</p>
                            <p className="text-[11px] font-mono text-slate-700 break-all">{entry.path}</p>
                          </div>
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
