import { useState, useEffect, useCallback } from 'react';
import {
  ScrollText, Search, Download, Calendar, CheckCircle, AlertTriangle,
  ChevronDown, ChevronRight, ChevronLeft, Loader2, RefreshCw,
  User, History, X, Eye, EyeOff,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { auditApi, AuditLogEntry } from '@/api/audit';
import { format, formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { describeAuditEntry, CATEGORY_STYLE } from './auditEventLabels';

const startIso = (d: string) => (d ? new Date(`${d}T00:00:00`).toISOString() : undefined);
const endIso = (d: string) => (d ? new Date(`${d}T23:59:59`).toISOString() : undefined);

const PAGE_SIZE = 50;

/**
 * Audit Trail — the hospital admin / auditor's forensic view.
 *
 * Professionalised (V107/V108): humanized event names (raw method/path kept in
 * the expandable technical detail), session housekeeping (login/refresh) folded
 * away by default, dense table with EXACT timestamps, true server-side
 * search/filters + pagination, request origin (IP/device), per-patient incident
 * timeline drawer, and a CSV export that honours the on-screen filters.
 */
export function AuditTrail() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';

  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  // Filters — ALL server-side, so they cover the whole log, not the loaded page.
  const [searchInput, setSearchInput] = useState('');
  const [q, setQ] = useState('');
  const [outcomeFilter, setOutcomeFilter] = useState<'' | 'SUCCESS' | 'FAILED'>('');
  const [showSession, setShowSession] = useState(false); // session housekeeping hidden by default
  const [showFilters, setShowFilters] = useState(false);
  const [dateRange, setDateRange] = useState<{ start: string; end: string }>({ start: '', end: '' });

  const [expandedEntry, setExpandedEntry] = useState<string | null>(null);

  // Per-patient incident timeline drawer (V107).
  const [trailFor, setTrailFor] = useState<AuditLogEntry | null>(null);
  const [trail, setTrail] = useState<AuditLogEntry[]>([]);
  const [trailLoading, setTrailLoading] = useState(false);

  // Debounce the search box into the server-side `q` (resets to page 0).
  useEffect(() => {
    const t = setTimeout(() => {
      setQ(searchInput.trim());
      setPage(0);
    }, 450);
    return () => clearTimeout(t);
  }, [searchInput]);

  const filterOpts = useCallback(() => ({
    from: startIso(dateRange.start),
    to: endIso(dateRange.end),
    outcome: outcomeFilter || undefined,
    q: q || undefined,
    includeAuth: showSession,
  }), [dateRange.start, dateRange.end, outcomeFilter, q, showSession]);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true); setError(null);
    try {
      const res = await auditApi.list(hospitalId, { ...filterOpts(), page, size: PAGE_SIZE });
      setEntries(res.content || []);
      setTotalElements(res.totalElements ?? (res.content || []).length);
    } catch (e) {
      setError('Failed to load the audit log. You must be an administrator or auditor for this hospital.');
      setEntries([]); setTotalElements(0);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, page, filterOpts]);

  useEffect(() => { load(); }, [load]);

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

  const handleExportCSV = async () => {
    if (!hospitalId) return;
    setDownloading(true);
    try {
      await auditApi.exportCsv(hospitalId, filterOpts()); // WYSIWYG — honours on-screen filters
    } catch {
      setError('Failed to export the audit CSV.');
    } finally {
      setDownloading(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE));

  const chip = (active: boolean, danger = false) =>
    `px-3 py-2 text-[11px] font-bold rounded-lg transition-all border ${
      active
        ? danger
          ? 'bg-rose-500/20 text-rose-400 border-rose-500/30'
          : 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30'
        : `${text.body} hover:bg-white/5 border-transparent`
    }`;

  const thClass = `px-3 py-2.5 text-left text-[9px] font-bold uppercase tracking-wider ${text.muted}`;

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
                  <p className="text-sm text-white/50">Who did what, when, to which patient, from where — including failed attempts</p>
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
                  title="Exports exactly what the current filters show"
                  className="flex items-center gap-2 px-4 py-2 bg-white/15 hover:bg-white/25 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all border border-white/10 disabled:opacity-50"
                >
                  {downloading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
                  Export CSV
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Filters — all server-side */}
        <div className="rounded-2xl p-4 animate-fade-up" style={{ ...glassCard, animationDelay: '0.15s' } as React.CSSProperties}>
          <div className="flex flex-col lg:flex-row lg:items-center gap-3">
            <div className="relative flex-1">
              <Search className={`absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 ${text.muted}`} />
              <input
                type="text"
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                placeholder="Search the whole log — actor, action, or path..."
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center gap-1.5 flex-wrap">
              <button onClick={() => { setOutcomeFilter(''); setPage(0); }} className={chip(outcomeFilter === '')}>All</button>
              <button onClick={() => { setOutcomeFilter('SUCCESS'); setPage(0); }} className={chip(outcomeFilter === 'SUCCESS')}>Success</button>
              <button onClick={() => { setOutcomeFilter('FAILED'); setPage(0); }} className={chip(outcomeFilter === 'FAILED', true)}>Failed</button>
              <button
                onClick={() => { setShowSession(!showSession); setPage(0); }}
                className={chip(showSession)}
                title="Login / session-renewal housekeeping is hidden by default so real actions stay visible"
              >
                {showSession ? <Eye className="w-3 h-3 inline mr-1" /> : <EyeOff className="w-3 h-3 inline mr-1" />}
                Session events
              </button>
              <button
                onClick={() => setShowFilters(!showFilters)}
                className={`flex items-center gap-1.5 px-3.5 py-2.5 text-[11px] font-bold rounded-lg transition-all border ${showFilters ? 'bg-cyan-500/20 text-cyan-400 border-cyan-500/30' : `${text.body} hover:bg-white/5 border-transparent`}`}
              >
                <Calendar className="w-3 h-3" /> Date Range
                {showFilters ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
              </button>
            </div>
          </div>
          {showFilters && (
            <div className="flex items-center gap-3 mt-3 pt-3 flex-wrap" style={{ borderTop: borderStyle }}>
              <div className="flex items-center gap-2">
                <span className={`text-[11px] font-semibold ${text.label}`}>From:</span>
                <input type="date" value={dateRange.start} onChange={(e) => { setDateRange((p) => ({ ...p, start: e.target.value })); setPage(0); }}
                  className={`px-3 py-1.5 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner} />
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-[11px] font-semibold ${text.label}`}>To:</span>
                <input type="date" value={dateRange.end} onChange={(e) => { setDateRange((p) => ({ ...p, end: e.target.value })); setPage(0); }}
                  className={`px-3 py-1.5 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} style={glassInner} />
              </div>
              {(dateRange.start || dateRange.end) && (
                <button onClick={() => { setDateRange({ start: '', end: '' }); setPage(0); }} className={`text-[10px] font-bold ${text.accent} hover:opacity-80`}>Clear dates</button>
              )}
            </div>
          )}
        </div>

        {error && (
          <div className={`rounded-xl p-3 text-xs font-medium ${isDark ? 'text-rose-300' : 'text-rose-600'}`} style={{ ...glassInner, border: '1px solid rgba(244,63,94,0.3)' }}>{error}</div>
        )}

        {/* Dense event table */}
        <div className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="px-4 py-3 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(34,197,94,0.12)' }}>
                <ScrollText className="w-3.5 h-3.5 text-emerald-500" />
              </div>
              <div>
                <h3 className={`text-sm font-extrabold ${text.heading}`}>Audit Log</h3>
                <p className={`text-[10px] ${text.muted} font-medium`}>
                  {totalElements.toLocaleString()} event{totalElements === 1 ? '' : 's'}
                  {!showSession ? ' · session events hidden' : ''}
                </p>
              </div>
            </div>
            {/* Pagination */}
            <div className="flex items-center gap-2">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0 || loading}
                className={`w-7 h-7 rounded-lg flex items-center justify-center transition-all disabled:opacity-30 hover:bg-white/5 ${text.body}`}
                title="Previous page"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <span className={`text-[10px] font-bold ${text.muted}`}>Page {page + 1} of {totalPages}</span>
              <button
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1 || loading}
                className={`w-7 h-7 rounded-lg flex items-center justify-center transition-all disabled:opacity-30 hover:bg-white/5 ${text.body}`}
                title="Next page"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-16"><Loader2 className="w-7 h-7 animate-spin text-emerald-500" /></div>
          ) : entries.length === 0 ? (
            <div className="p-12 text-center">
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                <ScrollText className={`w-8 h-8 ${text.muted}`} />
              </div>
              <p className={`text-sm font-bold ${text.heading}`}>No matching audit events</p>
              <p className={`text-xs ${text.muted} mt-1`}>Adjust the filters, or enable session events</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full" style={{ borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ borderBottom: borderStyle }}>
                    <th className={thClass}>Time</th>
                    <th className={thClass}>Event</th>
                    <th className={thClass}>Actor</th>
                    <th className={thClass}>Patient</th>
                    <th className={thClass}>Outcome</th>
                    <th className={thClass} />
                  </tr>
                </thead>
                <tbody>
                  {entries.map((entry) => {
                    const failed = entry.outcome === 'FAILED';
                    const { label, category } = describeAuditEntry(entry);
                    const isExpanded = expandedEntry === entry.id;
                    return (
                      <FragmentRow
                        key={entry.id}
                        entry={entry}
                        label={label}
                        category={category}
                        failed={failed}
                        isExpanded={isExpanded}
                        onToggle={() => setExpandedEntry(isExpanded ? null : entry.id)}
                        onOpenTrail={() => openTrail(entry)}
                        borderStyle={borderStyle}
                        glassInner={glassInner}
                        text={text}
                        isDark={isDark}
                      />
                    );
                  })}
                </tbody>
              </table>
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
                  const desc = describeAuditEntry(t);
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
                      <p className={`text-[11px] font-semibold mt-1 ${text.label}`}>{desc.label}</p>
                      <p className={`text-[10px] mt-0.5 ${text.muted}`}>
                        by <span className={`font-semibold ${text.body}`}>{t.actorName}</span>
                        {t.actorRole ? ` (${t.actorRole})` : ''}
                        {t.sourceIp ? ` · ${t.sourceIp}` : ''}
                      </p>
                      <p className={`text-[9px] font-mono mt-0.5 ${text.muted}`}>{t.action}</p>
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

/** One audit row + its expandable technical-detail row. */
function FragmentRow({ entry, label, category, failed, isExpanded, onToggle, onOpenTrail, borderStyle, glassInner, text, isDark }: {
  entry: AuditLogEntry;
  label: string;
  category: string;
  failed: boolean;
  isExpanded: boolean;
  onToggle: () => void;
  onOpenTrail: () => void;
  borderStyle: string;
  glassInner: React.CSSProperties;
  text: Record<string, string>;
  isDark: boolean;
}) {
  const Icon = failed ? AlertTriangle : CheckCircle;
  const color = failed ? 'text-rose-500' : 'text-emerald-500';
  const catStyle = (CATEGORY_STYLE as Record<string, string>)[category] || CATEGORY_STYLE.Other;
  const tdClass = 'px-3 py-2 align-top';
  return (
    <>
      <tr
        onClick={onToggle}
        className="cursor-pointer transition-colors hover:bg-white/5"
        style={{ borderBottom: isExpanded ? 'none' : borderStyle }}
      >
        <td className={`${tdClass} whitespace-nowrap`}>
          <p className={`text-[11px] font-mono font-semibold ${text.body}`}>
            {entry.timestamp ? format(new Date(entry.timestamp), 'dd MMM HH:mm:ss') : '—'}
          </p>
          <p className={`text-[9px] ${text.muted}`}>
            {entry.timestamp ? formatDistanceToNow(new Date(entry.timestamp), { addSuffix: true }) : ''}
          </p>
        </td>
        <td className={tdClass}>
          <p className={`text-[12px] font-semibold ${text.label}`}>{label}</p>
          <span className={`inline-block mt-0.5 px-1.5 py-0.5 text-[8px] font-bold uppercase tracking-wider rounded ${catStyle}`}>{category}</span>
        </td>
        <td className={tdClass}>
          <p className={`text-[11px] font-semibold ${text.body}`}>{entry.actorName}</p>
          <p className={`text-[9px] ${text.muted}`}>{entry.actorRole || ''}</p>
        </td>
        <td className={tdClass}>
          {entry.patientName || entry.visitNumber ? (
            <>
              <p className={`text-[11px] font-semibold ${text.body} flex items-center gap-1`}>
                <User className="w-2.5 h-2.5" />{entry.patientName || 'Patient'}
              </p>
              <p className={`text-[9px] font-mono ${text.muted}`}>{entry.visitNumber || ''}</p>
            </>
          ) : (
            <span className={`text-[10px] ${text.muted}`}>—</span>
          )}
        </td>
        <td className={`${tdClass} whitespace-nowrap`}>
          <span className={`inline-flex items-center gap-1 text-[10px] font-bold ${color}`}>
            <Icon className="w-3 h-3" />
            {entry.outcome}{entry.statusCode ? ` · ${entry.statusCode}` : ''}
          </span>
        </td>
        <td className={`${tdClass} w-8`}>
          {isExpanded ? <ChevronDown className={`w-3.5 h-3.5 ${text.muted}`} /> : <ChevronRight className={`w-3.5 h-3.5 ${text.muted}`} />}
        </td>
      </tr>
      {isExpanded && (
        <tr style={{ borderBottom: borderStyle }}>
          <td colSpan={6} className="px-4 pb-3">
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-2 mt-1">
              <div className="rounded-xl p-2.5" style={glassInner}>
                <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Request</p>
                <p className={`text-[10px] font-mono ${text.body} break-all`}>{entry.httpMethod} {entry.path}</p>
              </div>
              <div className="rounded-xl p-2.5" style={glassInner}>
                <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Status</p>
                <p className={`text-[10px] font-semibold ${failed ? (isDark ? 'text-rose-300' : 'text-rose-600') : (isDark ? 'text-emerald-300' : 'text-emerald-600')}`}>
                  {entry.statusCode ?? '—'} · {entry.outcome}
                </p>
              </div>
              <div className="rounded-xl p-2.5" style={glassInner}>
                <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Source IP</p>
                <p className={`text-[10px] font-mono ${text.body}`}>{entry.sourceIp || '—'}</p>
              </div>
              <div className="rounded-xl p-2.5" style={glassInner}>
                <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Device</p>
                <p className={`text-[10px] ${text.body} break-all`} title={entry.userAgent || undefined}>
                  {entry.userAgent ? (entry.userAgent.length > 80 ? entry.userAgent.slice(0, 80) + '…' : entry.userAgent) : '—'}
                </p>
              </div>
              {entry.visitId && (
                <div className="rounded-xl p-2.5 col-span-2 lg:col-span-4 flex items-center justify-between gap-3" style={glassInner}>
                  <div>
                    <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Patient</p>
                    <p className={`text-[10px] ${text.body} font-semibold`}>
                      {entry.patientName || 'Patient'}{entry.visitNumber ? ` · ${entry.visitNumber}` : ''}
                    </p>
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); onOpenTrail(); }}
                    className="flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-lg bg-cyan-500/10 text-cyan-400 hover:bg-cyan-500/20 transition-colors"
                  >
                    <History className="w-3.5 h-3.5" /> View patient trail
                  </button>
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}
