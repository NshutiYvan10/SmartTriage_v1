/* ── CrossHospitalPanel (Phase 3) ──
 *
 * Per-visit chart tab showing the patient's bounded clinical history from OTHER SmartTriage
 * hospitals. Disclosure is gated server-side: served when the patient's data-sharing CONSENT is
 * on file, otherwise locked with a break-the-glass emergency override (mandatory reason, recorded
 * forensically + governance-alerted). Read-only, provenance-tagged.
 */
import { useCallback, useEffect, useState } from 'react';
import {
  Globe, Loader2, Lock, ShieldAlert, ShieldCheck, Building2, Stethoscope,
  FlaskConical, FileText, Pill, ChevronRight,
} from 'lucide-react';
import { crossHospitalApi, type CrossHospitalDeepRecord, type CrossHospitalVisitSummary, type CrossHospitalDischargeSummary } from '@/api/crossHospital';
import { ApiError } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';
import { BreakTheGlassModal } from './BreakTheGlassModal';

interface Props {
  nationalId: string | null;
}

export function CrossHospitalPanel({ nationalId }: Props) {
  const { glassCard, isDark, text } = useTheme();
  const [record, setRecord] = useState<CrossHospitalDeepRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showBreakGlass, setShowBreakGlass] = useState(false);

  const load = useCallback(async (breakTheGlassReason?: string) => {
    // No national ID (or whitespace-only) → there is no cross-hospital identity to
    // resolve and nothing to audit, so skip the doomed lookup and show the empty
    // state. A real lookup (with an ID) IS audited server-side by the deep-record
    // service, so no audit coverage is lost by returning early here.
    if (!nationalId?.trim()) { setLoading(false); return; }
    setLoading(true);
    setError(null);
    try {
      setRecord(await crossHospitalApi.getDeepRecord(nationalId, breakTheGlassReason));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cross-hospital record');
    } finally {
      setLoading(false);
    }
  }, [nationalId]);

  useEffect(() => { load(); }, [load]);

  if (!nationalId?.trim()) {
    return (
      <div className={`rounded-xl p-6 text-center text-sm ${text.muted}`} style={glassCard}>
        This patient has no national ID on file, so no cross-hospital identity exists.
      </div>
    );
  }
  if (loading) {
    return (
      <div className={`flex items-center justify-center gap-2 py-10 text-sm ${text.muted}`}>
        <Loader2 className="w-5 h-5 animate-spin" /> Loading cross-hospital record…
      </div>
    );
  }
  if (error) {
    return (
      <div className={`rounded-xl p-4 text-sm font-semibold border ${isDark ? 'text-red-300 bg-red-500/15 border-red-500/30' : 'text-red-700 bg-red-50 border-red-200'}`}>
        {error}
      </div>
    );
  }
  if (!record || !record.found) {
    return (
      <div className={`rounded-xl p-6 text-center text-sm ${text.muted}`} style={glassCard}>
        No cross-hospital record found for this patient.
      </div>
    );
  }

  // Access denied → locked state with break-the-glass.
  if (!record.accessGranted) {
    const label = `${record.firstName ?? ''} ${record.lastName ?? ''}`.trim() || undefined;
    return (
      <div className="space-y-4">
        <div className="rounded-xl p-6 text-center" style={glassCard}>
          <Lock className="w-10 h-10 mx-auto text-amber-500 mb-3" />
          <h3 className={`text-sm font-bold ${text.heading}`}>Cross-hospital record is locked</h3>
          <p className={`text-xs ${text.muted} mt-1 max-w-md mx-auto`}>
            This patient is registered at {record.linkedHospitalCount} SmartTriage hospital
            {record.linkedHospitalCount === 1 ? '' : 's'}, but has not consented to sharing their
            deep clinical record. Consent can be recorded at registration. In an emergency, you may
            break the glass — an audited, governance-notified override.
          </p>
          <button
            onClick={() => setShowBreakGlass(true)}
            className="inline-flex items-center gap-1.5 mt-4 px-4 py-2 rounded-lg text-xs font-bold text-white bg-red-600 hover:bg-red-500"
          >
            <ShieldAlert className="w-4 h-4" /> Break the glass
          </button>
        </div>
        {showBreakGlass && (
          <BreakTheGlassModal
            patientLabel={label}
            onConfirm={async (reason) => { await load(reason); setShowBreakGlass(false); }}
            onClose={() => setShowBreakGlass(false)}
          />
        )}
      </div>
    );
  }

  // Access granted → render bounded provenance-tagged history.
  const basisGranted = record.accessBasis === 'BREAK_THE_GLASS';
  return (
    <div className="space-y-4">
      {/* Access basis chip */}
      <div className={`rounded-xl px-4 py-3 flex items-center gap-2 border ${
        basisGranted
          ? (isDark ? 'bg-red-500/15 border-red-500/30' : 'bg-red-50 border-red-200')
          : (isDark ? 'bg-emerald-500/15 border-emerald-500/30' : 'bg-emerald-50 border-emerald-200')}`}>
        {basisGranted ? <ShieldAlert className={`w-4 h-4 ${isDark ? 'text-red-300' : 'text-red-600'}`} /> : <ShieldCheck className={`w-4 h-4 ${isDark ? 'text-emerald-300' : 'text-emerald-600'}`} />}
        <div className="text-xs">
          <span className={`font-bold ${basisGranted ? (isDark ? 'text-red-300' : 'text-red-700') : (isDark ? 'text-emerald-300' : 'text-emerald-700')}`}>
            {basisGranted ? 'BREAK-THE-GLASS access' : 'Access by patient consent'}
          </span>
          <span className={text.muted}>
            {' · '}{record.linkedHospitalCount} hospital{record.linkedHospitalCount === 1 ? '' : 's'}
            {basisGranted ? ' · this override has been logged and is auditable' : ''}
          </span>
        </div>
      </div>

      {/* Cross-visit medication history */}
      {record.medicationHistory && record.medicationHistory.length > 0 && (
        <div className="rounded-xl p-4" style={glassCard}>
          <div className="flex items-center gap-2 mb-2">
            <Pill className="w-4 h-4 text-cyan-600" />
            <h4 className={`text-xs font-bold uppercase tracking-wide ${text.heading}`}>Medication history</h4>
          </div>
          <ul className="space-y-1">
            {record.medicationHistory.map((m, i) => (
              <li key={i} className={`text-xs ${text.body}`}>{m}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Per-hospital sections */}
      {(record.hospitals ?? []).map((h, hi) => (
        <div key={hi} className="rounded-xl p-4" style={glassCard}>
          <div className="flex items-center gap-2 mb-3">
            <Building2 className={`w-4 h-4 ${text.muted}`} />
            <h4 className={`text-sm font-bold ${text.heading}`}>{h.sourceHospital}</h4>
            {h.truncated && (
              <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded border ${isDark ? 'text-amber-300 bg-amber-500/20 border-amber-500/30' : 'text-amber-600 bg-amber-50 border-amber-200'}`}>
                showing most recent visits
              </span>
            )}
          </div>

          {(h.visits ?? []).length === 0 ? (
            <p className={`text-xs ${text.muted}`}>No visit summaries.</p>
          ) : (
            <div className="space-y-3">
              {(h.visits ?? []).map((v, vi) => (
                <VisitRow key={vi} visit={v} />
              ))}
            </div>
          )}
        </div>
      ))}

      {(record.hospitals ?? []).length === 0 && (
        <div className={`rounded-xl p-6 text-center text-sm flex items-center justify-center gap-2 ${text.muted}`} style={glassCard}>
          <Globe className="w-4 h-4" /> No detailed history available across hospitals.
        </div>
      )}
    </div>
  );
}

/** One cross-hospital visit, collapsed by default — click the row to reveal its
 *  bounded clinical detail. Visits with nothing further show an explicit empty
 *  state rather than a dead chevron. */
function VisitRow({ visit }: { visit: CrossHospitalVisitSummary }) {
  const { glassInner, text, isDark } = useTheme();
  const [open, setOpen] = useState(false);

  const stringSections = [
    { icon: Stethoscope, label: 'Diagnoses', items: visit.diagnoses, tone: isDark ? 'text-indigo-300' : 'text-indigo-600' },
    { icon: FlaskConical, label: 'Labs & tests', items: visit.labs, tone: isDark ? 'text-red-300' : 'text-red-600' },
    { icon: FileText, label: 'Key notes', items: visit.keyNotes, tone: text.body },
  ];
  const summaries = visit.dischargeSummaries ?? [];
  const presentSections = stringSections.filter((s) => s.items && s.items.length > 0);
  const totalItems = presentSections.reduce((n, s) => n + (s.items?.length ?? 0), 0) + summaries.length;
  const hasDetail = presentSections.length > 0 || summaries.length > 0;

  return (
    <div className="rounded-lg overflow-hidden" style={glassInner}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className={`w-full text-left p-3 flex items-center gap-2 text-xs transition-colors ${isDark ? 'hover:bg-white/5' : 'hover:bg-black/5'}`}
      >
        <ChevronRight className={`w-3.5 h-3.5 flex-shrink-0 transition-transform duration-200 ${text.muted} ${open ? 'rotate-90' : ''}`} />
        <span className={`font-bold ${text.heading}`}>Visit {visit.visitNumber ?? '—'}</span>
        {visit.status && <span className={text.muted}>· {visit.status}</span>}
        {visit.arrivalTime && <span className={text.muted}>· {new Date(visit.arrivalTime).toLocaleDateString()}</span>}
        {!open && (
          <span className={`ml-auto text-[11px] font-medium ${text.muted}`}>
            {!hasDetail ? 'no detail' : `${totalItems} item${totalItems === 1 ? '' : 's'}`}
          </span>
        )}
      </button>
      {open && (
        <div className="px-3 pb-3">
          {!hasDetail ? (
            <p className={`text-xs ${text.muted}`}>No further clinical detail was recorded for this visit.</p>
          ) : (
            <>
              {presentSections.map((s, i) => (
                <Section key={i} icon={s.icon} label={s.label} items={s.items} tone={s.tone} />
              ))}
              {summaries.length > 0 && (
                <div className="mt-1.5">
                  <div className={`flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wide ${isDark ? 'text-emerald-300' : 'text-emerald-600'}`}>
                    <FileText className="w-3 h-3" /> Discharge summaries
                  </div>
                  <div className="mt-0.5 ml-4 space-y-1">
                    {summaries.map((d, i) => <DischargeSummaryItem key={i} doc={d} />)}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** A discharge summary line — click to expand its full text inline. */
function DischargeSummaryItem({ doc }: { doc: CrossHospitalDischargeSummary }) {
  const { text, isDark } = useTheme();
  const [open, setOpen] = useState(false);
  const hasContent = !!doc.content && doc.content.trim().length > 0;
  return (
    <div>
      <button
        type="button"
        onClick={() => hasContent && setOpen((o) => !o)}
        aria-expanded={hasContent ? open : undefined}
        className={`flex items-start gap-1.5 text-xs text-left w-full ${hasContent ? 'cursor-pointer' : 'cursor-default'} ${text.body}`}
      >
        <FileText className={`w-3 h-3 mt-0.5 flex-shrink-0 ${isDark ? 'text-emerald-300' : 'text-emerald-600'}`} />
        <span>
          <span className={hasContent ? 'hover:underline' : ''}>{doc.title}</span>{' '}
          <span className={text.muted}>{doc.signed ? '(signed)' : '(unsigned)'}</span>
          {hasContent && (
            <span className={`ml-1.5 text-[10px] font-semibold ${isDark ? 'text-cyan-300' : 'text-cyan-600'}`}>
              {open ? '▲ Hide' : '▼ View full'}
            </span>
          )}
        </span>
      </button>
      {open && hasContent && (
        <pre
          className={`mt-1 ml-4 p-2.5 rounded-lg text-[11px] leading-relaxed whitespace-pre-wrap ${isDark ? 'bg-black/25 text-slate-300' : 'bg-slate-50 text-slate-700 border border-slate-200'}`}
          style={{ fontFamily: 'inherit' }}
        >
          {doc.content}
        </pre>
      )}
    </div>
  );
}

function Section({ icon: Icon, label, items, tone }: {
  icon: typeof Stethoscope; label: string; items: string[] | null; tone: string;
}) {
  const { text } = useTheme();
  if (!items || items.length === 0) return null;
  return (
    <div className="mt-1.5">
      <div className={`flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wide ${tone}`}>
        <Icon className="w-3 h-3" /> {label}
      </div>
      <ul className="mt-0.5 ml-4 space-y-0.5">
        {items.map((it, i) => <li key={i} className={`text-xs list-disc ${text.body}`}>{it}</li>)}
      </ul>
    </div>
  );
}
