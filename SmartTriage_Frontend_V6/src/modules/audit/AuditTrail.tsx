import { useState, useMemo, useEffect, useCallback } from 'react';
import {
  ScrollText, Search, Download, Calendar, Clock,
  CheckCircle, AlertTriangle, ChevronDown, ChevronRight, Loader2, RefreshCw,
  User, History, X,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { auditApi, AuditLogEntry } from '@/api/audit';
import { formatDistanceToNow, format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

const startIso = (d: string) => (d ? new Date(`${d}T00:00:00`).toISOString() : undefined);
const endIso = (d: string) => (d ? new Date(`${d}T23:59:59`).toISOString() : undefined);

export function AuditTrail() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';

  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const [expandedEntry, setExpandedEntry] = useState<string | null>(null);
  const [dateRange, setDateRange] = useState<{ start: string; end: string }>({ start: '', end: '' });
  const [downloading, setDownloading] = useState(false);
  // Outcome filter — server-side, so "Failed" surfaces denied/erroring actions
  // beyond the loaded page (the incident-review starting point).
  const [outcomeFilter, setOutcomeFilter] = useState<'' | 'SUCCESS' | 'FAILED'>('');
  // Per-patient incident timeline drawer (V107).
  const [trailFor, setTrailFor] = useState<AuditLogEntry | null>(null);
  const [trail, setTrail] = useState<AuditLogEntry[]>([]);
  const [trailLoading, setTrailLoading] = useState(false);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true); setError(null);
    try {
      const res = await auditApi.list(hospitalId, {
        size: 200,
        from: startIso(dateRange.start),
        to: endIso(dateRange.end),
        outcome: outcomeFilter || undefined,
      });
      setEntries(res.content || []);
    } catch (e) {
      setError('Failed to load the audit log. You must be an administrator or auditor for this hospital.');
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, dateRange.start, dateRange.end, outcomeFilter]);

  useEffect(() => { load(); }, [load]);

  // Open the full chronological trail for the visit an entry touched.
  const openTrail = useCallback(async (entry: AuditLogEntry) => {
    if (!entry.visitId) return;
    setTrailFor(entry); setTrail([]); setTrailLoading(true);
    try {
      setTrail(await auditApi.visitTrail(entry.visitId));
    } catch {
      setError('Failed to load the patient audit trail.');
      setTrailFor(null);
    } finally {
      setTrailLoading(false);
    }
  }, []);

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

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <ScrollText className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white">Audit Trail &amp; Compliance</h1>
                  <p className="text-sm text-white/50">Server-backed log of every state-changing action — who, what, when, outcome</p>
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
              <Search className={`absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 ${text.muted}`} />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search by actor, role, action, or path..."
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>
            {/* Outcome chips — server-side filter (Failed = incident-review view) */}
            <div className="flex items-center gap-1.5">
              {([['', 'All'], ['SUCCESS', 'Success'], ['FAILED', 'Failed']] as const).map(([value, label]) => (
                <button
                  key={label}
                  onClick={() => setOutcomeFilter(value)}
                  className={`px-3 py-2 text-[11px] font-bold rounded-lg transition-all border ${
                    outcomeFilter === value
                      ? value === 'FAILED'
                        ? 'bg-rose-500/20 text-rose-400 border-rose-500/30'
                        : 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30'
                      : `${text.body} hover:bg-white/5 border-transparent`
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`flex items-center gap-1.5 px-3.5 py-2.5 text-[11px] font-bold rounded-lg transition-all border ${showFilters ? 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30' : `${text.body} hover:bg-white/5 border-transparent`}`}
            >
              <Calendar className="w-3 h-3" /> Date Range
              {showFilters ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            </button>
          </div>
          {showFilters && (
            <div className="flex items-center gap-3 mt-3 pt-3 flex-wrap" style={{ borderTop: borderStyle }}>
              <div className="flex items-center gap-2">
                <span className={`text-[11px] font-semibold ${text.label}`}>From:</span>
                <input type="date" value={dateRange.start} onChange={(e) => setDateRange((p) => ({ ...p, start: e.target.value }))}
                  className={`px-3 py-1.5 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner} />
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-[11px] font-semibold ${text.label}`}>To:</span>
                <input type="date" value={dateRange.end} onChange={(e) => setDateRange((p) => ({ ...p, end: e.target.value }))}
                  className={`px-3 py-1.5 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner} />
              </div>
              {(dateRange.start || dateRange.end) && (
                <button onClick={() => setDateRange({ start: '', end: '' })} className={`text-[10px] font-bold ${text.accent} hover:opacity-80`}>Clear dates</button>
              )}
            </div>
          )}
        </div>

        {error && (
          <div className={`rounded-xl p-3 text-xs font-medium ${isDark ? 'text-rose-300' : 'text-rose-600'}`} style={{ ...glassInner, border: '1px solid rgba(244,63,94,0.3)' }}>{error}</div>
        )}

        {/* List */}
        <div className="space-y-2">
          <div className="flex items-center justify-between px-1">
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(34,197,94,0.12)' }}>
                <ScrollText className="w-3.5 h-3.5 text-emerald-500" />
              </div>
              <div>
                <h3 className={`text-sm font-extrabold ${text.heading}`}>Audit Log</h3>
                <p className={`text-[10px] ${text.muted} font-medium`}>{displayEntries.length} entries</p>
              </div>
            </div>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-16"><Loader2 className="w-7 h-7 animate-spin text-emerald-500" /></div>
          ) : displayEntries.length === 0 ? (
            <div className="rounded-2xl p-12 text-center" style={glassCard}>
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                <ScrollText className={`w-8 h-8 ${text.muted}`} />
              </div>
              <p className={`text-sm font-bold ${text.heading}`}>No Audit Entries</p>
              <p className={`text-xs ${text.muted} mt-1`}>State-changing actions appear here as they are performed</p>
            </div>
          ) : (
            <div className="space-y-2">
              {displayEntries.map((entry) => {
                const failed = entry.outcome === 'FAILED';
                const Icon = failed ? AlertTriangle : CheckCircle;
                const color = failed ? 'text-rose-600' : 'text-emerald-600';
                const bg = failed ? 'rgba(244,63,94,0.1)' : 'rgba(34,197,94,0.1)';
                const badgeStyle = failed
                  ? { background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' }
                  : { background: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.2)' };
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
                            <span className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${color}`} style={badgeStyle}>
                              {entry.outcome}{entry.statusCode ? ` · ${entry.statusCode}` : ''}
                            </span>
                            <span className={`text-[10px] ${text.muted} flex items-center gap-1`}>
                              <Clock className="w-2.5 h-2.5" />
                              {entry.timestamp ? formatDistanceToNow(new Date(entry.timestamp), { addSuffix: true }) : ''}
                            </span>
                          </div>
                          <p className={`text-[12px] font-semibold truncate ${text.label}`}>{entry.action}</p>
                          <div className="flex items-center gap-3 mt-1 flex-wrap">
                            <span className={`text-[10px] ${text.muted}`}>
                              by <span className={`font-semibold ${text.body}`}>{entry.actorName}</span>
                              {entry.actorRole ? <span className={text.muted}> ({entry.actorRole})</span> : null}
                            </span>
                            {(entry.patientName || entry.visitNumber) && (
                              <span className={`text-[10px] ${text.muted} flex items-center gap-1`}>
                                <User className="w-2.5 h-2.5" />
                                <span className={`font-semibold ${text.body}`}>{entry.patientName || 'Patient'}</span>
                                {entry.visitNumber ? <span className={text.muted}>({entry.visitNumber})</span> : null}
                              </span>
                            )}
                          </div>
                        </div>
                        <div className="flex-shrink-0">
                          {isExpanded ? <ChevronDown className={`w-4 h-4 ${text.muted}`} /> : <ChevronRight className={`w-4 h-4 ${text.muted}`} />}
                        </div>
                      </div>
                    </button>
                    {isExpanded && (
                      <div className="px-4 pb-4 pt-1" style={{ borderTop: borderStyle }}>
                        <div className="grid grid-cols-2 gap-3 mt-2">
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Timestamp</p>
                            <p className={`text-[11px] ${text.body} font-semibold`}>{entry.timestamp ? format(new Date(entry.timestamp), 'yyyy-MM-dd HH:mm:ss') : '—'}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Actor</p>
                            <p className={`text-[11px] ${text.body} font-semibold`}>{entry.actorName}{entry.actorRole ? ` (${entry.actorRole})` : ''}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Method</p>
                            <p className={`text-[11px] font-mono ${text.body}`}>{entry.httpMethod}</p>
                          </div>
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Status</p>
                            <p className={`text-[11px] font-semibold ${failed ? (isDark ? 'text-rose-300' : 'text-rose-600') : (isDark ? 'text-emerald-300' : 'text-emerald-600')}`}>{entry.statusCode ?? '—'} · {entry.outcome}</p>
                          </div>
                          <div className="rounded-xl p-3 col-span-2" style={glassInner}>
                            <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Path</p>
                            <p className={`text-[11px] font-mono ${text.body} break-all`}>{entry.path}</p>
                          </div>
                          {entry.visitId && (
                            <div className="rounded-xl p-3 col-span-2 flex items-center justify-between gap-3" style={glassInner}>
                              <div>
                                <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Patient</p>
                                <p className={`text-[11px] ${text.body} font-semibold`}>
                                  {entry.patientName || 'Patient'}{entry.visitNumber ? ` · ${entry.visitNumber}` : ''}
                                </p>
                              </div>
                              <button
                                onClick={() => openTrail(entry)}
                                className="flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-lg bg-cyan-500/10 text-cyan-400 hover:bg-cyan-500/20 transition-colors"
                              >
                                <History className="w-3.5 h-3.5" /> View patient trail
                              </button>
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

      {/* Patient incident-timeline drawer (V107): the full chronological audit
          trail of one visit — who did what, when, outcome, incl. failed/denied. */}
      {trailFor && (
        <>
          <div className="fixed inset-0 bg-black/40 z-40" onClick={() => setTrailFor(null)} />
          <div className="fixed inset-y-0 right-0 w-full max-w-md z-50 flex flex-col overflow-hidden shadow-2xl" style={glassCard}>
            <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4 flex items-center justify-between shrink-0">
              <div className="flex items-center gap-3 min-w-0">
                <div className="w-9 h-9 rounded-xl bg-cyan-500/20 flex items-center justify-center shrink-0">
                  <History className="w-4 h-4 text-cyan-300" />
                </div>
                <div className="min-w-0">
                  <h2 className="text-sm font-bold text-white truncate">
                    {trailFor.patientName || 'Patient'} {trailFor.visitNumber ? `· ${trailFor.visitNumber}` : ''}
                  </h2>
                  <p className="text-[11px] text-white/50">Full audit trail for this visit — chronological, incl. failed attempts</p>
                </div>
              </div>
              <button onClick={() => setTrailFor(null)} className="w-8 h-8 rounded-lg bg-white/10 hover:bg-white/20 flex items-center justify-center transition-all shrink-0" title="Close">
                <X className="w-4 h-4 text-white" />
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-4 space-y-2">
              {trailLoading ? (
                <div className="flex items-center justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-cyan-500" /></div>
              ) : trail.length === 0 ? (
                <p className={`text-xs ${text.muted} text-center py-10`}>No audited actions for this visit yet.</p>
              ) : (
                trail.map((t) => {
                  const tFailed = t.outcome === 'FAILED';
                  return (
                    <div key={t.id} className="rounded-xl p-3" style={glassInner}>
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className={`w-2 h-2 rounded-full shrink-0 ${tFailed ? 'bg-rose-500' : 'bg-emerald-500'}`} />
                        <span className={`text-[10px] font-mono ${text.muted}`}>
                          {t.timestamp ? format(new Date(t.timestamp), 'dd MMM HH:mm:ss') : '—'}
                        </span>
                        {tFailed && (
                          <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-rose-500/10 text-rose-500">
                            Failed{t.statusCode ? ` · ${t.statusCode}` : ''}
                          </span>
                        )}
                      </div>
                      <p className={`text-[11px] font-semibold mt-1 ${text.label}`}>{t.action}</p>
                      <p className={`text-[10px] mt-0.5 ${text.muted}`}>
                        by <span className={`font-semibold ${text.body}`}>{t.actorName}</span>
                        {t.actorRole ? ` (${t.actorRole})` : ''}
                      </p>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
