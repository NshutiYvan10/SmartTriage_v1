/* ════════════════════════════════════════════════════════════════════
   DoctorInvestigationsView — Workflow 2 refinement.

   Doctors no longer manage the Lab inbox (a tech surface). This page
   gives them what they actually need: a single roll-up of every
   investigation they've ordered, across every visit, grouped by
   status, with click-through to the relevant visit chart.

   Sections (in display order):
     1. Resulted   — newest first, abnormal/critical highlighted.
     2. In progress — specimen in lab, processing.
     3. Specimen collected — drawn but not yet at the lab.
     4. Pending    — ORDERED but not yet acted on.
     5. Cancelled  — for audit / hand-off context.

   Read-only: no inbox transitions here; the lab tech drives status.
   The doctor's role on this page is "what have I asked for and what's
   come back?".
   ════════════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  FlaskConical, RefreshCw, Loader2, AlertTriangle, CheckCircle2,
  Clock, FileSearch, XCircle, ChevronRight,
} from 'lucide-react';
import { investigationApi } from '@/api/investigations';
import type { InvestigationResponse, InvestigationStatus } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';

type Section = {
  status: InvestigationStatus;
  title: string;
  helper: string;
  icon: typeof FlaskConical;
  /** Tailwind tint for the section header chip. */
  tint: string;
};

const SECTIONS: Section[] = [
  {
    status: 'RESULTED',
    title: 'Resulted',
    helper: 'Result available — review and act',
    icon: CheckCircle2,
    tint: 'bg-emerald-100 text-emerald-800 border-emerald-300',
  },
  {
    status: 'IN_PROGRESS',
    title: 'In progress',
    helper: 'Specimen received by lab, processing',
    icon: Loader2,
    tint: 'bg-cyan-100 text-cyan-800 border-cyan-300',
  },
  {
    status: 'SPECIMEN_COLLECTED',
    title: 'Specimen collected',
    helper: 'Drawn — on its way to the lab',
    icon: FileSearch,
    tint: 'bg-amber-100 text-amber-800 border-amber-300',
  },
  {
    status: 'ORDERED',
    title: 'Pending',
    helper: 'Ordered — specimen not yet drawn',
    icon: Clock,
    tint: 'bg-slate-100 text-slate-700 border-slate-300',
  },
  {
    status: 'CANCELLED',
    title: 'Cancelled',
    helper: 'Cancelled — for audit only',
    icon: XCircle,
    tint: 'bg-rose-50 text-rose-700 border-rose-200',
  },
];

function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    const then = new Date(iso).getTime();
    const diffMs = Date.now() - then;
    if (diffMs < 60_000) return 'just now';
    const min = Math.floor(diffMs / 60_000);
    if (min < 60) return `${min}m ago`;
    const hr = Math.floor(min / 60);
    if (hr < 24) return `${hr}h ago`;
    const days = Math.floor(hr / 24);
    return `${days}d ago`;
  } catch { return iso; }
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}

export function DoctorInvestigationsView() {
  const { cardClass, glassCard, glassInner, isDark, text } = useTheme();
  const [orders, setOrders] = useState<InvestigationResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [filter, setFilter] = useState<'ALL' | InvestigationStatus>('ALL');

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const rows = await investigationApi.getMyOrders();
      setOrders(Array.isArray(rows) ? rows : []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load investigations');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const grouped = useMemo(() => {
    const m = new Map<InvestigationStatus, InvestigationResponse[]>();
    for (const o of orders) {
      const list = m.get(o.status) ?? [];
      list.push(o);
      m.set(o.status, list);
    }
    return m;
  }, [orders]);

  const totals = useMemo(() => ({
    all: orders.length,
    RESULTED: grouped.get('RESULTED')?.length ?? 0,
    IN_PROGRESS: grouped.get('IN_PROGRESS')?.length ?? 0,
    SPECIMEN_COLLECTED: grouped.get('SPECIMEN_COLLECTED')?.length ?? 0,
    ORDERED: grouped.get('ORDERED')?.length ?? 0,
    CANCELLED: grouped.get('CANCELLED')?.length ?? 0,
    abnormal: orders.filter((o) => o.status === 'RESULTED' && (o.isAbnormal || o.isCritical)).length,
  }), [orders, grouped]);

  const visibleSections = SECTIONS.filter(
    (s) => filter === 'ALL' || filter === s.status,
  );

  return (
    <div className="p-4 md:p-6 space-y-4 max-w-6xl mx-auto">
      {/* ── Header ── */}
      <div className={`${cardClass} overflow-hidden`} style={glassCard}>
        <div className="p-5 md:p-6 flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div className="flex items-center gap-4">
            <div className={`w-12 h-12 rounded-2xl flex items-center justify-center bg-gradient-to-br ${
              isDark ? 'from-cyan-500/20 to-blue-500/20' : 'from-cyan-50 to-blue-50'
            }`}>
              <FlaskConical className={`w-6 h-6 ${text.accent}`} />
            </div>
            <div>
              <h1 className={`text-xl md:text-2xl font-bold tracking-tight ${text.heading}`}>
                My Investigations
              </h1>
              <p className={`text-sm mt-0.5 ${text.muted}`}>
                Every investigation you've ordered, grouped by status.
                Click a row to open the visit.
              </p>
            </div>
          </div>
          <button
            onClick={load}
            disabled={loading}
            className={`inline-flex items-center gap-2 ${cardClass} px-3 py-2 text-xs font-bold transition-colors ${
              isDark ? 'hover:bg-white/10 text-slate-300' : 'hover:bg-slate-100 text-slate-600'
            }`}
            style={glassInner}
          >
            {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
            Refresh
          </button>
        </div>

        {/* Stat strip + filter chips */}
        <div className="border-t border-slate-200/30 px-5 md:px-6 py-3 flex flex-wrap items-center gap-2">
          <FilterChip active={filter === 'ALL'}             label={`All ${totals.all}`}                onClick={() => setFilter('ALL')} />
          <FilterChip active={filter === 'RESULTED'}        label={`Resulted ${totals.RESULTED}`}      onClick={() => setFilter('RESULTED')} accent="emerald" />
          <FilterChip active={filter === 'IN_PROGRESS'}     label={`In progress ${totals.IN_PROGRESS}`} onClick={() => setFilter('IN_PROGRESS')} accent="cyan" />
          <FilterChip active={filter === 'SPECIMEN_COLLECTED'} label={`Specimen ${totals.SPECIMEN_COLLECTED}`} onClick={() => setFilter('SPECIMEN_COLLECTED')} accent="amber" />
          <FilterChip active={filter === 'ORDERED'}         label={`Pending ${totals.ORDERED}`}        onClick={() => setFilter('ORDERED')} accent="slate" />
          <FilterChip active={filter === 'CANCELLED'}       label={`Cancelled ${totals.CANCELLED}`}    onClick={() => setFilter('CANCELLED')} accent="rose" />
          {totals.abnormal > 0 && (
            <span className="ml-auto inline-flex items-center gap-1 px-2 py-1 rounded-md bg-red-100 text-red-800 text-[11px] font-bold">
              <AlertTriangle className="w-3 h-3" /> {totals.abnormal} abnormal/critical need review
            </span>
          )}
        </div>
      </div>

      {/* ── Sections ── */}
      {err && (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 flex items-start gap-2">
          <AlertTriangle className="w-4 h-4 mt-0.5" />
          <span>{err}</span>
        </div>
      )}

      {loading && orders.length === 0 ? (
        <div className={`text-center py-16 ${text.muted}`}>
          <Loader2 className="w-8 h-8 animate-spin mx-auto mb-3 opacity-50" />
          <p className="text-sm">Loading your investigations…</p>
        </div>
      ) : orders.length === 0 ? (
        <div className={`${cardClass} px-5 py-12 text-center ${text.muted}`} style={glassCard}>
          <FileSearch className="w-8 h-8 mx-auto mb-3 opacity-50" />
          <p className="text-sm">No investigations ordered yet.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {visibleSections.map((section) => {
            const rows = grouped.get(section.status) ?? [];
            if (rows.length === 0) return null;
            return (
              <SectionCard
                key={section.status}
                section={section}
                rows={rows}
                cardClass={cardClass}
                glassCard={glassCard}
                glassInner={glassInner}
                isDark={isDark}
                text={text}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}

function FilterChip({
  active, label, onClick, accent,
}: {
  active: boolean;
  label: string;
  onClick: () => void;
  accent?: 'emerald' | 'cyan' | 'amber' | 'slate' | 'rose';
}) {
  const accents: Record<string, string> = {
    emerald: 'bg-emerald-500/15 text-emerald-700 border-emerald-400/40',
    cyan: 'bg-cyan-500/15 text-cyan-700 border-cyan-400/40',
    amber: 'bg-amber-500/15 text-amber-700 border-amber-400/40',
    slate: 'bg-slate-500/15 text-slate-700 border-slate-400/40',
    rose: 'bg-rose-500/15 text-rose-700 border-rose-400/40',
  };
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-2.5 py-1 rounded-lg text-[11px] font-bold border transition-colors ${
        active
          ? (accent ? accents[accent] : 'bg-slate-800 text-white border-slate-800')
          : 'bg-white/60 border-slate-200 text-slate-500 hover:bg-slate-100'
      }`}
    >
      {label}
    </button>
  );
}

function SectionCard({
  section, rows, cardClass, glassCard, glassInner, isDark, text,
}: {
  section: Section;
  rows: InvestigationResponse[];
  cardClass: string;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: { heading: string; muted: string; body: string; accent: string; label: string };
}) {
  const Icon = section.icon;
  return (
    <div className={`${cardClass} overflow-hidden`} style={glassCard}>
      <div className={`px-4 py-2.5 flex items-center gap-2 border-b ${
        isDark ? 'border-white/10' : 'border-slate-200/60'
      }`}>
        <span className={`inline-flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider px-2 py-1 rounded-md border ${section.tint}`}>
          <Icon className="w-3 h-3" />
          {section.title}
          <span className="ml-1 inline-flex items-center justify-center min-w-[20px] px-1 rounded bg-white/70">
            {rows.length}
          </span>
        </span>
        <span className={`text-[11px] ${text.muted}`}>{section.helper}</span>
      </div>
      <ul>
        {rows.map((r) => (
          <li key={r.id} className={`px-4 py-2.5 flex items-center gap-3 border-b last:border-0 ${
            isDark ? 'border-white/5 hover:bg-white/5' : 'border-slate-100 hover:bg-slate-50'
          }`}>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-sm font-bold ${text.heading}`}>{r.testName}</span>
                <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${
                  r.priority === 'STAT' ? 'bg-red-100 text-red-800'
                  : r.priority === 'URGENT' ? 'bg-amber-100 text-amber-800'
                  : 'bg-slate-200 text-slate-600'
                }`}>
                  {r.priority || 'ROUTINE'}
                </span>
                <span className={`text-[10px] uppercase tracking-wider ${text.muted}`}>
                  {r.investigationType?.replace(/_/g, ' ')}
                </span>
                {r.isCritical && (
                  <span className="text-[10px] font-bold uppercase px-1.5 py-0.5 rounded bg-red-600 text-white">
                    Critical
                  </span>
                )}
                {!r.isCritical && r.isAbnormal && (
                  <span className="text-[10px] font-bold uppercase px-1.5 py-0.5 rounded bg-amber-200 text-amber-900">
                    Abnormal
                  </span>
                )}
              </div>
              <div className={`text-[11px] mt-0.5 ${text.muted} flex items-center gap-3 flex-wrap`}>
                <span>Visit <span className={`font-mono ${text.body}`}>{r.visitNumber}</span></span>
                {r.patientName && <span>{r.patientName}</span>}
                <span>Ordered {fmtRelative(r.orderedAt)}</span>
                {r.resultedAt && <span>Resulted {fmtDateTime(r.resultedAt)}</span>}
              </div>
              {r.status === 'RESULTED' && r.result && (
                <div className={`mt-1 text-[12px] ${text.body} truncate`}>
                  Result: <span className="font-medium">{r.result}</span>
                  {r.resultUnit && <span className="text-slate-500"> {r.resultUnit}</span>}
                </div>
              )}
            </div>
            <Link
              to={`/visit/${r.visitId}`}
              className={`inline-flex items-center gap-1 px-3 py-1.5 text-[11px] font-bold ${cardClass} ${
                isDark ? 'bg-white/5 hover:bg-white/10 text-cyan-300' : 'bg-white hover:bg-slate-100 text-cyan-700'
              }`}
              style={glassInner}
            >
              Open visit <ChevronRight className="w-3 h-3" />
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default DoctorInvestigationsView;
