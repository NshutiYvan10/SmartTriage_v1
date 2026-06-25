/**
 * PatientHistoryPanel — compact prior-visits timeline for a returning
 * patient, shown after a federated-lookup match in the registration flow.
 *
 * Why this exists:
 *   When a nurse picks an existing patient via PatientLookupPanel she's
 *   doing two things at once — verifying it's the right person, and
 *   forming a clinical mental-model. A "Last visit: 3d ago" line isn't
 *   enough; she needs to see *what* the prior visit was for, *how* it
 *   was triaged, and *how* it ended (admitted? discharged? LWBS?).
 *
 * Data source:
 *   GET /api/v1/visits/patient/{patientId}  (paginated VisitResponse[])
 *
 * Display rules:
 *   - Newest visit first (sorted client-side by arrivalTime desc).
 *   - Top 5 visible by default; "Show all N" if more exist.
 *   - Each row: arrival date · visit number · chief complaint ·
 *     triage category pill · disposition pill (or status if not yet
 *     dispositioned) · pediatric flag.
 *   - Empty state collapses to a single "First visit on record" line
 *     so the panel doesn't look broken when there's no history.
 */
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Activity, AlertCircle, Loader2, ChevronDown, ChevronRight, Stethoscope, ExternalLink } from 'lucide-react';
import { visitApi } from '@/api/visits';
import type {
  VisitResponse,
  TriageCategory,
  VisitStatus,
  DispositionType,
} from '@/api/types';
import { useTheme } from '@/hooks/useTheme';

// ── Display helpers ──────────────────────────────────────────────────

const TRIAGE_PILL: Record<TriageCategory, { label: string; cls: string }> = {
  RED:    { label: 'Red',    cls: 'bg-red-500/20 text-red-300 border border-red-500/30' },
  ORANGE: { label: 'Orange', cls: 'bg-orange-500/20 text-orange-300 border border-orange-500/30' },
  YELLOW: { label: 'Yellow', cls: 'bg-yellow-500/20 text-yellow-300 border border-yellow-500/30' },
  GREEN:  { label: 'Green',  cls: 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30' },
  BLUE:   { label: 'Blue',   cls: 'bg-blue-500/20 text-blue-300 border border-blue-500/30' },
};

/**
 * Disposition pill — only shown when the visit ended. Color coded by
 * whether the outcome was routine (discharge) or escalated (admit/ICU/
 * transfer/death).
 */
const DISPOSITION_PILL: Record<DispositionType, { label: string; cls: string }> = {
  DISCHARGED_HOME:               { label: 'Discharged',         cls: 'bg-[rgba(16,185,129,0.08)] text-emerald-600 border border-[rgba(16,185,129,0.2)]' },
  ADMITTED_TO_WARD:              { label: 'Admitted',           cls: 'bg-[rgba(59,130,246,0.08)] text-blue-600 border border-[rgba(59,130,246,0.2)]' },
  ICU_ADMISSION:                 { label: 'ICU',                cls: 'bg-[rgba(239,68,68,0.08)] text-red-600 border border-[rgba(239,68,68,0.2)]' },
  TRANSFERRED:                   { label: 'Transferred',        cls: 'bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]' },
  LEFT_AGAINST_MEDICAL_ADVICE:   { label: 'LAMA',               cls: 'bg-[rgba(244,63,94,0.08)] text-rose-600 border border-[rgba(244,63,94,0.2)]' },
  LEFT_WITHOUT_BEING_SEEN:       { label: 'LWBS',               cls: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]' },
  DECEASED:                      { label: 'Deceased',           cls: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]' },
};

/** Status pill for visits that are still in-flight (no disposition yet). */
const STATUS_PILL: Partial<Record<VisitStatus, { label: string; cls: string }>> = {
  REGISTERED:           { label: 'Registered',          cls: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]' },
  AWAITING_TRIAGE:      { label: 'Awaiting triage',     cls: 'bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]' },
  TRIAGED:              { label: 'Triaged',             cls: 'bg-[rgba(59,130,246,0.08)] text-blue-600 border border-[rgba(59,130,246,0.2)]' },
  AWAITING_ASSESSMENT:  { label: 'Awaiting MD',         cls: 'bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]' },
  UNDER_ASSESSMENT:     { label: 'Under assessment',    cls: 'bg-[rgba(139,92,246,0.08)] text-violet-600 border border-[rgba(139,92,246,0.2)]' },
  UNDER_TREATMENT:      { label: 'Under treatment',     cls: 'bg-[rgba(139,92,246,0.08)] text-violet-600 border border-[rgba(139,92,246,0.2)]' },
  UNDER_OBSERVATION:    { label: 'Under observation',   cls: 'bg-[rgba(139,92,246,0.08)] text-violet-600 border border-[rgba(139,92,246,0.2)]' },
  PENDING_DISPOSITION:  { label: 'Pending disposition', cls: 'bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]' },
};

/**
 * Returns "5 May 2026", "today · 14:32", or "yesterday" — whichever is
 * most useful for a clinical timeline. Time is shown only for very
 * recent visits because that's when "when today did this happen?"
 * matters; for older entries, the date is enough.
 */
function formatVisitWhen(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '?';
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) {
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `Today · ${hh}:${mm}`;
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

// ── Component ───────────────────────────────────────────────────────

interface Props {
  /**
   * UUID of the patient whose history to fetch. When this changes, the
   * panel re-fetches. Pass null/empty to render nothing.
   */
  patientId: string | null;
  /**
   * Optional — visit ID to filter *out* of the rendered list. Use this
   * when embedding the panel inside a VisitDetailPage so the page
   * doesn't list itself as "prior history."
   */
  excludeVisitId?: string;
  /**
   * Optional override for the empty-state message. Defaults to "First
   * visit on record…" which is the right copy on the registration
   * surface but reads weirdly on a doctor's workspace.
   */
  emptyMessage?: string;
}

export function PatientHistoryPanel({ patientId, excludeVisitId, emptyMessage }: Props) {
  const { glassCard, glassInner, text } = useTheme();
  const navigate = useNavigate();

  const [visits, setVisits] = useState<VisitResponse[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    if (!patientId) {
      setVisits(null);
      setError(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    setExpanded(false);
    visitApi
      .getByPatient(patientId, 0, 20)
      .then((page) => {
        if (cancelled) return;
        // Backend doesn't enforce a default sort, so we sort here. Newest
        // arrival first because clinicians read top-down chronologically.
        // Filter out excludeVisitId (set when embedding inside a visit
        // detail page so the page doesn't list itself).
        const sorted = [...(page.content ?? [])]
          .filter((v) => !excludeVisitId || v.id !== excludeVisitId)
          .sort((a, b) => {
            const ta = new Date(a.arrivalTime).getTime();
            const tb = new Date(b.arrivalTime).getTime();
            return tb - ta;
          });
        setVisits(sorted);
      })
      .catch((err: any) => {
        if (cancelled) return;
        setError(err?.message ?? 'Failed to load patient history');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [patientId, excludeVisitId]);

  if (!patientId) return null;

  const cardCls = 'rounded-2xl shadow-md p-4';
  const headerTextCls = text.heading;
  const subtleTextCls = text.muted;

  // ── Render ──────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className={cardCls} style={glassCard}>
        <div className="flex items-center gap-2 text-sm">
          <Loader2 className={`w-4 h-4 animate-spin ${subtleTextCls}`} />
          <span className={subtleTextCls}>Loading patient history…</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className={cardCls} style={glassCard}>
        <div className="flex items-center gap-2 text-sm text-red-400">
          <AlertCircle className="w-4 h-4" />
          {error}
        </div>
      </div>
    );
  }

  if (!visits || visits.length === 0) {
    return (
      <div className={cardCls} style={glassCard}>
        <div className="flex items-center gap-2 text-sm">
          <Activity className={`w-4 h-4 ${subtleTextCls}`} />
          <span className={subtleTextCls}>
            {emptyMessage ?? 'First visit on record — no prior history at this hospital.'}
          </span>
        </div>
      </div>
    );
  }

  const visible = expanded ? visits : visits.slice(0, 5);
  const hiddenCount = visits.length - visible.length;

  return (
    <div className={cardCls} style={glassCard}>
      <div className="flex items-center justify-between mb-2">
        <h3 className={`text-sm font-bold flex items-center gap-2 ${headerTextCls}`}>
          <Activity className="w-4 h-4" />
          Prior visits
          <span className={`text-xs font-medium ${subtleTextCls}`}>
            ({visits.length})
          </span>
        </h3>
      </div>

      <div className="space-y-1.5">
        {visible.map((v) => {
          const triagePill = v.currentTriageCategory ? TRIAGE_PILL[v.currentTriageCategory] : null;
          const dispoPill = v.dispositionType ? DISPOSITION_PILL[v.dispositionType] : null;
          const statusPill = !dispoPill ? STATUS_PILL[v.status] : null;

          return (
            <button
              key={v.id}
              type="button"
              onClick={() => navigate(`/visit/${v.id}`)}
              title="Open visit detail"
              style={glassInner}
              className="w-full text-left rounded-xl p-2.5 transition-colors group hover:bg-white/[0.03]"
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className={`text-xs font-bold ${headerTextCls}`}>
                  {formatVisitWhen(v.arrivalTime)}
                </span>
                <span className={`text-[11px] ${subtleTextCls}`}>
                  · {v.visitNumber}
                </span>
                <ExternalLink
                  className="w-3 h-3 opacity-0 group-hover:opacity-100 transition-opacity text-cyan-400"
                />
                {triagePill && (
                  <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${triagePill.cls}`}>
                    {triagePill.label}
                    {v.currentTewsScore != null && <> · TEWS {v.currentTewsScore}</>}
                  </span>
                )}
                {dispoPill && (
                  <span className={`inline-flex items-center text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg ${dispoPill.cls}`}>
                    {dispoPill.label}
                  </span>
                )}
                {statusPill && (
                  <span className={`inline-flex items-center text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg ${statusPill.cls}`}>
                    {statusPill.label}
                  </span>
                )}
                {v.retriageCount > 0 && (
                  <span className="inline-flex items-center text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]">
                    Retriaged ×{v.retriageCount}
                  </span>
                )}
              </div>
              <div className={`text-xs mt-1 flex items-start gap-1.5 ${subtleTextCls}`}>
                <Stethoscope className="w-3 h-3 mt-0.5 flex-shrink-0" />
                <span className="break-words">
                  {v.chiefComplaint?.trim() || 'No chief complaint recorded'}
                </span>
              </div>
              {v.referringFacility && (
                <div className={`text-[11px] mt-0.5 ${subtleTextCls}`}>
                  Referred from: {v.referringFacility}
                </div>
              )}
            </button>
          );
        })}
      </div>

      {hiddenCount > 0 && (
        <button
          type="button"
          onClick={() => setExpanded(true)}
          className="mt-2 text-xs font-semibold inline-flex items-center gap-1 text-cyan-400 hover:text-cyan-300"
        >
          <ChevronDown className="w-3 h-3" />
          Show {hiddenCount} older visit{hiddenCount === 1 ? '' : 's'}
        </button>
      )}
      {expanded && visits.length > 5 && (
        <button
          type="button"
          onClick={() => setExpanded(false)}
          className="mt-2 text-xs font-semibold inline-flex items-center gap-1 text-cyan-400 hover:text-cyan-300"
        >
          <ChevronRight className="w-3 h-3" />
          Collapse
        </button>
      )}
    </div>
  );
}

export default PatientHistoryPanel;
