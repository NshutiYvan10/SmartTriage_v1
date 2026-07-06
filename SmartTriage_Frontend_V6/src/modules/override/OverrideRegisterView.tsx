import { useState, useEffect, useCallback, Fragment } from 'react';
import {
  ShieldAlert, Search, Clock, User, Loader2, RefreshCw, Calendar,
  ChevronDown, ChevronRight, Pill, FlaskConical, Eye, CheckCircle2,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { overridesApi, OverrideRecord } from '@/api/overrides';
import { format, formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

const startIso = (d: string) => (d ? new Date(`${d}T00:00:00`).toISOString() : undefined);
const endIso = (d: string) => (d ? new Date(`${d}T23:59:59`).toISOString() : undefined);

/** Filter chips → backend override-type keys. */
const TYPE_FILTERS: Array<{ label: string; type?: string }> = [
  { label: 'All' },
  { label: 'Allergy', type: 'PRESCRIBE_ALLERGY' },
  { label: 'Interaction', type: 'PRESCRIBE_INTERACTION' },
  { label: 'Emergency approval', type: 'EMERGENCY_APPROVAL' },
  { label: 'Dose gate', type: 'DOSE_ADMINISTRATION' },
  { label: 'Safety check', type: 'MED_SAFETY_CHECK' },
  { label: 'Lab bypass', type: 'LAB_VERIFICATION_BYPASS' },
  { label: 'Break-the-glass', type: 'BREAK_THE_GLASS' },
];

function categoryIcon(category: string) {
  if (category === 'Lab') return FlaskConical;
  if (category === 'Privacy') return Eye;
  return Pill;
}

/**
 * Override Register — the authoritative "who overrode which safety gate, on whom,
 * when, and why" record for the hospital admin / clinical-safety officer. Reads the
 * unified backend register (domain tables, all override types), not alert parsing.
 */
export function OverrideRegisterView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';

  const [records, setRecords] = useState<OverrideRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [search, setSearch] = useState('');
  const [showDates, setShowDates] = useState(false);
  const [dateRange, setDateRange] = useState<{ start: string; end: string }>({ start: '', end: '' });
  const [expanded, setExpanded] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true); setError(null);
    try {
      const res = await overridesApi.list(hospitalId, {
        type: typeFilter,
        from: startIso(dateRange.start),
        to: endIso(dateRange.end),
      });
      setRecords(res || []);
    } catch {
      setError('Failed to load the override register. You must be an administrator or safety officer for this hospital.');
      setRecords([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, typeFilter, dateRange.start, dateRange.end]);

  useEffect(() => { load(); }, [load]);

  const q = search.trim().toLowerCase();
  const shown = q
    ? records.filter((r) =>
        (r.actorName || '').toLowerCase().includes(q) ||
        (r.patientName || '').toLowerCase().includes(q) ||
        (r.maskedSubject || '').toLowerCase().includes(q) ||
        (r.label || '').toLowerCase().includes(q) ||
        (r.justification || '').toLowerCase().includes(q) ||
        (r.detail || '').toLowerCase().includes(q))
    : records;

  const chip = (active: boolean) =>
    `px-3 py-1.5 text-[11px] font-bold rounded-lg transition-all border ${
      active ? 'bg-rose-500/20 text-rose-400 border-rose-500/30' : `${text.body} hover:bg-white/5 border-transparent`
    }`;
  const thClass = `px-3 py-2.5 text-left text-[9px] font-bold uppercase tracking-wider ${text.muted}`;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-rose-900 to-slate-800 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-rose-500/20 flex items-center justify-center">
                  <ShieldAlert className="w-5 h-5 text-rose-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white">Override Register</h1>
                  <p className="text-sm text-white/50">Every safety-gate override — who, on whom, when, and why. For incident investigation.</p>
                </div>
              </div>
              <button
                onClick={load}
                className="w-9 h-9 rounded-xl bg-white/10 hover:bg-white/20 flex items-center justify-center transition-all"
                title="Refresh"
              >
                <RefreshCw className={`w-4 h-4 text-white ${loading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>
        </div>

        {/* Filters */}
        <div className="rounded-2xl p-4 space-y-3 animate-fade-up" style={{ ...glassCard, animationDelay: '0.1s' } as React.CSSProperties}>
          <div className="flex flex-col lg:flex-row lg:items-center gap-3">
            <div className="relative flex-1">
              <Search className={`absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 ${text.muted}`} />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search by clinician, patient, drug, or justification..."
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-rose-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>
            <button
              onClick={() => setShowDates(!showDates)}
              className={`flex items-center gap-1.5 px-3.5 py-2.5 text-[11px] font-bold rounded-lg transition-all border ${showDates ? 'bg-rose-500/20 text-rose-400 border-rose-500/30' : `${text.body} hover:bg-white/5 border-transparent`}`}
            >
              <Calendar className="w-3 h-3" /> Date Range
              {showDates ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
            </button>
          </div>
          <div className="flex items-center gap-1.5 flex-wrap">
            {TYPE_FILTERS.map((f) => (
              <button key={f.label} onClick={() => setTypeFilter(f.type)} className={chip(typeFilter === f.type)}>
                {f.label}
              </button>
            ))}
          </div>
          {showDates && (
            <div className="flex items-center gap-3 pt-2 flex-wrap" style={{ borderTop: borderStyle }}>
              <div className="flex items-center gap-2">
                <span className={`text-[11px] font-semibold ${text.label}`}>From:</span>
                <input type="date" value={dateRange.start} onChange={(e) => setDateRange((p) => ({ ...p, start: e.target.value }))}
                  className={`px-3 py-1.5 rounded-lg text-xs focus:outline-none ${text.body}`} style={glassInner} />
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-[11px] font-semibold ${text.label}`}>To:</span>
                <input type="date" value={dateRange.end} onChange={(e) => setDateRange((p) => ({ ...p, end: e.target.value }))}
                  className={`px-3 py-1.5 rounded-lg text-xs focus:outline-none ${text.body}`} style={glassInner} />
              </div>
              {(dateRange.start || dateRange.end) && (
                <button onClick={() => setDateRange({ start: '', end: '' })} className={`text-[10px] font-bold ${text.accent} hover:opacity-80`}>Clear</button>
              )}
              <span className={`text-[10px] ${text.muted}`}>Defaults to the last 30 days.</span>
            </div>
          )}
        </div>

        {error && (
          <div className={`rounded-xl p-3 text-xs font-medium ${isDark ? 'text-rose-300' : 'text-rose-600'}`} style={{ ...glassInner, border: '1px solid rgba(244,63,94,0.3)' }}>{error}</div>
        )}

        {/* Register table */}
        <div className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="px-4 py-3 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
            <div className="flex items-center gap-2">
              <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(244,63,94,0.12)' }}>
                <ShieldAlert className="w-3.5 h-3.5 text-rose-500" />
              </div>
              <div>
                <h3 className={`text-sm font-extrabold ${text.heading}`}>Overrides</h3>
                <p className={`text-[10px] ${text.muted} font-medium`}>{shown.length} record{shown.length === 1 ? '' : 's'}</p>
              </div>
            </div>
          </div>

          {loading ? (
            <div className="flex items-center justify-center py-16"><Loader2 className="w-7 h-7 animate-spin text-rose-500" /></div>
          ) : shown.length === 0 ? (
            <div className="p-12 text-center">
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                <CheckCircle2 className={`w-8 h-8 ${text.muted}`} />
              </div>
              <p className={`text-sm font-bold ${text.heading}`}>No overrides in this window</p>
              <p className={`text-xs ${text.muted} mt-1`}>Safety gates were respected, or adjust the filters/date range.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full" style={{ borderCollapse: 'collapse' }}>
                <thead>
                  <tr style={{ borderBottom: borderStyle }}>
                    <th className={thClass}>When</th>
                    <th className={thClass}>Override</th>
                    <th className={thClass}>Clinician</th>
                    <th className={thClass}>Patient</th>
                    <th className={thClass}>Justification</th>
                    <th className={thClass} />
                  </tr>
                </thead>
                <tbody>
                  {shown.map((r) => {
                    const key = `${r.overrideType}:${r.sourceId}:${r.occurredAt}`;
                    const isOpen = expanded === key;
                    const Icon = categoryIcon(r.category);
                    const critical = r.severity === 'CRITICAL';
                    const tdClass = 'px-3 py-2 align-top';
                    return (
                      <Fragment key={key}>
                        <tr onClick={() => setExpanded(isOpen ? null : key)}
                            className="cursor-pointer transition-colors hover:bg-white/5"
                            style={{ borderBottom: isOpen ? 'none' : borderStyle }}>
                          <td className={`${tdClass} whitespace-nowrap`}>
                            <p className={`text-[11px] font-mono font-semibold ${text.body}`}>{r.occurredAt ? format(new Date(r.occurredAt), 'dd MMM HH:mm') : '—'}</p>
                            <p className={`text-[9px] ${text.muted}`}>{r.occurredAt ? formatDistanceToNow(new Date(r.occurredAt), { addSuffix: true }) : ''}</p>
                          </td>
                          <td className={tdClass}>
                            <p className={`text-[12px] font-semibold flex items-center gap-1.5 ${critical ? 'text-rose-500' : text.label}`}>
                              <Icon className="w-3.5 h-3.5 shrink-0" />{r.label}
                            </p>
                            <span className={`inline-block mt-0.5 px-1.5 py-0.5 text-[8px] font-bold uppercase tracking-wider rounded ${critical ? 'bg-rose-500/15 text-rose-500' : 'bg-slate-500/10 text-slate-400'}`}>
                              {r.category}{r.severity ? ` · ${r.severity}` : ''}
                            </span>
                          </td>
                          <td className={tdClass}>
                            <p className={`text-[11px] font-semibold ${text.body} flex items-center gap-1`}><User className="w-2.5 h-2.5" />{r.actorName || 'Unknown'}</p>
                            {r.actorRole && <p className={`text-[9px] ${text.muted}`}>{r.actorRole}</p>}
                          </td>
                          <td className={tdClass}>
                            {r.patientName ? (
                              <>
                                <p className={`text-[11px] font-semibold ${text.body}`}>{r.patientName}</p>
                                <p className={`text-[9px] font-mono ${text.muted}`}>{r.visitNumber || ''}</p>
                              </>
                            ) : (
                              <p className={`text-[11px] ${text.muted}`}>{r.maskedSubject || '—'}</p>
                            )}
                          </td>
                          <td className={tdClass}>
                            <p className={`text-[11px] ${r.justification ? text.body : 'text-rose-400 italic'}`}>
                              {r.justification || 'no reason recorded'}
                            </p>
                          </td>
                          <td className={`${tdClass} w-8`}>{isOpen ? <ChevronDown className={`w-3.5 h-3.5 ${text.muted}`} /> : <ChevronRight className={`w-3.5 h-3.5 ${text.muted}`} />}</td>
                        </tr>
                        {isOpen && (
                          <tr style={{ borderBottom: borderStyle }}>
                            <td colSpan={6} className="px-4 pb-3">
                              <div className="grid grid-cols-1 lg:grid-cols-3 gap-2 mt-1">
                                <div className="rounded-xl p-2.5 lg:col-span-2" style={glassInner}>
                                  <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>What was overridden</p>
                                  <p className={`text-[11px] ${text.body}`}>{r.detail || '—'}</p>
                                </div>
                                <div className="rounded-xl p-2.5" style={glassInner}>
                                  <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Exact time</p>
                                  <p className={`text-[11px] font-mono ${text.body}`}>{r.occurredAt ? format(new Date(r.occurredAt), 'yyyy-MM-dd HH:mm:ss') : '—'}</p>
                                </div>
                                <div className="rounded-xl p-2.5 lg:col-span-3" style={glassInner}>
                                  <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Justification</p>
                                  <p className={`text-[11px] ${r.justification ? text.body : 'text-rose-400 italic'}`}>{r.justification || 'No reason was recorded for this override.'}</p>
                                </div>
                                {r.category === 'Privacy' && (
                                  <div className="rounded-xl p-2.5 lg:col-span-3" style={glassInner}>
                                    <p className={`text-[8px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Governance review</p>
                                    <p className={`text-[11px] ${r.governanceAcknowledged ? 'text-emerald-400' : text.muted}`}>
                                      {r.governanceAcknowledged
                                        ? `Reviewed${r.acknowledgedByName ? ` by ${r.acknowledgedByName}` : ''}${r.acknowledgedAt ? ` · ${format(new Date(r.acknowledgedAt), 'dd MMM HH:mm')}` : ''}`
                                        : 'Not yet reviewed'}
                                    </p>
                                  </div>
                                )}
                              </div>
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
