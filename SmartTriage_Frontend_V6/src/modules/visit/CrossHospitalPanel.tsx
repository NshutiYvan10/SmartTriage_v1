/* ── CrossHospitalPanel (Phase 3) ──
 *
 * Per-visit chart tab showing the patient's bounded clinical history from OTHER SmartTriage
 * hospitals. Disclosure is gated server-side: served when the patient's data-sharing CONSENT is
 * on file, otherwise locked with a break-the-glass emergency override (mandatory reason, recorded
 * forensically + governance-alerted). Read-only, provenance-tagged.
 *
 * Presentation: a structured clinical record — patient identity band, per-hospital visit timeline,
 * colour-coded status/severity, and the discharge summary parsed into readable sections (not a raw
 * text dump).
 */
import { useCallback, useEffect, useState } from 'react';
import {
  Globe, Loader2, Lock, ShieldAlert, ShieldCheck, Building2, Stethoscope,
  FlaskConical, FileText, Pill, ChevronRight, User2, CalendarDays, AlertTriangle,
} from 'lucide-react';
import {
  crossHospitalApi,
  type CrossHospitalDeepRecord,
  type CrossHospitalHospitalSection,
  type CrossHospitalVisitSummary,
  type CrossHospitalDischargeSummary,
} from '@/api/crossHospital';
import { ApiError } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';
import { BreakTheGlassModal } from './BreakTheGlassModal';

interface Props {
  nationalId: string | null;
}

// ── small helpers ──────────────────────────────────────────────────────────

const titleCase = (s: string) =>
  s.toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

/** Colour + label for a visit status. */
function statusStyle(status: string | null, isDark: boolean) {
  const key = (status || '').toUpperCase();
  const map: Record<string, { txt: string; dot: string; ring: string }> = {
    DISCHARGED: { txt: isDark ? 'text-emerald-300' : 'text-emerald-600', dot: 'bg-emerald-500', ring: 'border-emerald-500/40' },
    ADMITTED: { txt: isDark ? 'text-blue-300' : 'text-blue-600', dot: 'bg-blue-500', ring: 'border-blue-500/40' },
    ICU_ADMITTED: { txt: isDark ? 'text-rose-300' : 'text-rose-600', dot: 'bg-rose-500', ring: 'border-rose-500/40' },
    TRANSFERRED: { txt: isDark ? 'text-violet-300' : 'text-violet-600', dot: 'bg-violet-500', ring: 'border-violet-500/40' },
    DECEASED: { txt: isDark ? 'text-slate-300' : 'text-slate-500', dot: 'bg-slate-400', ring: 'border-slate-400/40' },
    LEFT_WITHOUT_BEING_SEEN: { txt: isDark ? 'text-slate-300' : 'text-slate-500', dot: 'bg-slate-400', ring: 'border-slate-400/40' },
  };
  return map[key] || { txt: isDark ? 'text-amber-300' : 'text-amber-600', dot: 'bg-amber-500', ring: 'border-amber-500/40' };
}

/** "[PRIMARY] Desc (ICD) — By" → structured parts. */
function parseDiagnosis(raw: string) {
  let s = raw.trim();
  const primary = /^\[PRIMARY\]\s*/i.test(s);
  s = s.replace(/^\[PRIMARY\]\s*/i, '');
  let by: string | null = null;
  const byM = s.match(/\s+[—-]\s+([^—-]+)$/);
  if (byM && byM.index !== undefined) { by = byM[1].trim(); s = s.slice(0, byM.index).trim(); }
  let icd: string | null = null;
  const icdM = s.match(/\(([A-TV-Z]\d[A-Za-z0-9.]*)\)\s*$/);
  if (icdM && icdM.index !== undefined) { icd = icdM[1]; s = s.slice(0, icdM.index).trim(); }
  return { primary, text: s, icd, by };
}

/** "Test name [CRITICAL]" → { name, severity }. */
function parseLab(raw: string) {
  const m = raw.match(/\s*\[(CRITICAL|ABNORMAL)\]\s*$/i);
  const severity = m ? m[1].toUpperCase() : null;
  const name = m && m.index !== undefined ? raw.slice(0, m.index).trim() : raw.trim();
  return { name, severity };
}

/** "DOCTOR_NOTE: content" → { type, content }. */
function parseNote(raw: string) {
  const m = raw.match(/^([A-Z][A-Z_]+):\s*([\s\S]+)$/);
  if (m) return { type: titleCase(m[1].replace(/_/g, ' ')), content: m[2].trim() };
  return { type: null, content: raw.trim() };
}

/** Parse the auto-generated discharge-summary text into titled sections. */
function parseDischargeSummary(content: string) {
  const lines = content.split('\n');
  let generated = '';
  const sections: { title: string; lines: string[] }[] = [];
  let current: { title: string; lines: string[] } | null = null;
  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '');
    if (/^\s*===/.test(line)) continue; // banner
    const gen = line.match(/^Generated:\s*(.+)$/i);
    if (gen) { generated = gen[1].trim(); continue; }
    const sec = line.match(/^\s*---\s*(.+?)\s*---\s*$/);
    if (sec) { current = { title: sec[1], lines: [] }; sections.push(current); continue; }
    if (current && line.trim()) current.lines.push(line.trim());
  }
  return { generated, sections };
}

// ── main component ──────────────────────────────────────────────────────────

export function CrossHospitalPanel({ nationalId }: Props) {
  const { glassCard, isDark, text } = useTheme();
  const [record, setRecord] = useState<CrossHospitalDeepRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showBreakGlass, setShowBreakGlass] = useState(false);

  const load = useCallback(async (breakTheGlassReason?: string) => {
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

  // Access granted → structured, provenance-tagged clinical record.
  const hospitals = record.hospitals ?? [];
  const totalVisits = hospitals.reduce((n, h) => n + (h.visits?.length ?? 0), 0);

  return (
    <div className="space-y-4">
      <PatientBand record={record} isDark={isDark} text={text} glassCard={glassCard} totalVisits={totalVisits} />

      {record.medicationHistory && record.medicationHistory.length > 0 && (
        <div className="rounded-2xl p-4" style={glassCard}>
          <SectionHeader icon={Pill} label="Medication history — across all hospitals" tone={isDark ? 'text-cyan-300' : 'text-cyan-600'} count={record.medicationHistory.length} text={text} />
          <ul className="mt-2 space-y-1.5">
            {record.medicationHistory.map((m, i) => (
              <li key={i} className={`flex items-start gap-2 text-xs ${text.body}`}>
                <span className="mt-1.5 w-1.5 h-1.5 rounded-full bg-cyan-500 flex-shrink-0" />
                <span>{m}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {hospitals.map((h, hi) => (
        <HospitalSection key={hi} hospital={h} isDark={isDark} text={text} glassCard={glassCard} />
      ))}

      {hospitals.length === 0 && (
        <div className={`rounded-xl p-6 text-center text-sm flex items-center justify-center gap-2 ${text.muted}`} style={glassCard}>
          <Globe className="w-4 h-4" /> No detailed history available across hospitals.
        </div>
      )}
    </div>
  );
}

// ── patient identity + access band ──────────────────────────────────────────

function PatientBand({ record, isDark, text, glassCard, totalVisits }: {
  record: CrossHospitalDeepRecord; isDark: boolean; text: any; glassCard: React.CSSProperties; totalVisits: number;
}) {
  const name = `${record.firstName ?? ''} ${record.lastName ?? ''}`.trim() || 'Unknown patient';
  const initials = (name.split(/\s+/).map((p) => p[0]).join('').slice(0, 2) || '?').toUpperCase();
  const age = ageFrom(record.dateOfBirth);
  const brokeGlass = record.accessBasis === 'BREAK_THE_GLASS';

  return (
    <div className="rounded-2xl overflow-hidden" style={glassCard}>
      <div className={`px-4 py-3 flex items-center gap-3 border-b ${
        brokeGlass
          ? (isDark ? 'bg-red-500/10 border-red-500/25' : 'bg-red-50 border-red-200')
          : (isDark ? 'bg-emerald-500/10 border-emerald-500/25' : 'bg-emerald-50 border-emerald-200')}`}>
        {brokeGlass
          ? <ShieldAlert className={`w-4 h-4 flex-shrink-0 ${isDark ? 'text-red-300' : 'text-red-600'}`} />
          : <ShieldCheck className={`w-4 h-4 flex-shrink-0 ${isDark ? 'text-emerald-300' : 'text-emerald-600'}`} />}
        <div className="text-xs flex-1 min-w-0">
          <span className={`font-bold ${brokeGlass ? (isDark ? 'text-red-300' : 'text-red-700') : (isDark ? 'text-emerald-300' : 'text-emerald-700')}`}>
            {brokeGlass ? 'BREAK-THE-GLASS ACCESS' : 'ACCESS BY PATIENT CONSENT'}
          </span>
          <span className={text.muted}>
            {brokeGlass ? ' · this override is logged and auditable' : ' · consent on file'}
          </span>
        </div>
      </div>

      <div className="p-4 flex items-center gap-4">
        <div className={`w-12 h-12 rounded-2xl flex items-center justify-center text-sm font-bold flex-shrink-0 ${
          isDark ? 'bg-white/10 text-white' : 'bg-slate-800 text-white'}`}>
          {initials}
        </div>
        <div className="min-w-0 flex-1">
          <h3 className={`text-base font-bold truncate ${text.heading}`}>{name}</h3>
          <div className={`flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs mt-0.5 ${text.muted}`}>
            {age != null && <span className="inline-flex items-center gap-1"><User2 className="w-3 h-3" /> {age} yrs</span>}
            {record.gender && <span>{titleCase(record.gender)}</span>}
            {record.nationalId && <span className="font-mono">ID {record.nationalId}</span>}
          </div>
        </div>
        <div className="text-right flex-shrink-0">
          <p className={`text-lg font-bold ${text.heading}`}>{record.linkedHospitalCount}</p>
          <p className={`text-[10px] uppercase tracking-wide ${text.muted}`}>hospital{record.linkedHospitalCount === 1 ? '' : 's'}</p>
          <p className={`text-[10px] ${text.muted}`}>{totalVisits} visit{totalVisits === 1 ? '' : 's'}</p>
        </div>
      </div>
    </div>
  );
}

// ── one hospital: header + visit timeline ────────────────────────────────────

function HospitalSection({ hospital, isDark, text, glassCard }: {
  hospital: CrossHospitalHospitalSection;
  isDark: boolean; text: any; glassCard: React.CSSProperties;
}) {
  const visits = hospital.visits ?? [];
  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-center gap-2 mb-3">
        <div className={`w-7 h-7 rounded-lg flex items-center justify-center ${isDark ? 'bg-white/10' : 'bg-slate-100'}`}>
          <Building2 className={`w-4 h-4 ${text.muted}`} />
        </div>
        <h4 className={`text-sm font-bold ${text.heading}`}>{hospital.sourceHospital}</h4>
        <span className={`text-[10px] ${text.muted}`}>· {visits.length} visit{visits.length === 1 ? '' : 's'}</span>
        {hospital.truncated && (
          <span className={`ml-auto text-[10px] font-semibold px-1.5 py-0.5 rounded border ${isDark ? 'text-amber-300 bg-amber-500/20 border-amber-500/30' : 'text-amber-600 bg-amber-50 border-amber-200'}`}>
            most recent only
          </span>
        )}
      </div>

      {visits.length === 0 ? (
        <p className={`text-xs ${text.muted}`}>No visit summaries.</p>
      ) : (
        <div className={`space-y-2.5 pl-3 border-l-2 ${isDark ? 'border-white/10' : 'border-slate-200'}`}>
          {visits.map((v, vi) => (
            <VisitCard key={vi} visit={v} defaultOpen={vi === 0} isDark={isDark} text={text} />
          ))}
        </div>
      )}
    </div>
  );
}

// ── one visit: timeline card, expandable ─────────────────────────────────────

function VisitCard({ visit, defaultOpen, isDark, text }: {
  visit: CrossHospitalVisitSummary; defaultOpen: boolean; isDark: boolean; text: any;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const st = statusStyle(visit.status, isDark);
  const diagnoses = visit.diagnoses ?? [];
  const labs = visit.labs ?? [];
  const notes = visit.keyNotes ?? [];
  const summaries = visit.dischargeSummaries ?? [];
  const counts = [
    diagnoses.length && `${diagnoses.length} dx`,
    labs.length && `${labs.length} lab${labs.length === 1 ? '' : 's'}`,
    notes.length && `${notes.length} note${notes.length === 1 ? '' : 's'}`,
    summaries.length && `${summaries.length} summary`,
  ].filter(Boolean) as string[];
  const hasDetail = counts.length > 0;

  return (
    <div className={`relative rounded-xl border ${st.ring} ${isDark ? 'bg-white/[0.02]' : 'bg-white'}`}>
      {/* timeline dot */}
      <span className={`absolute -left-[19px] top-4 w-2.5 h-2.5 rounded-full ring-2 ${st.dot} ${isDark ? 'ring-slate-900' : 'ring-white'}`} />
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className={`w-full text-left p-3 flex items-center gap-2 transition-colors ${isDark ? 'hover:bg-white/[0.03]' : 'hover:bg-slate-50'} rounded-xl`}
      >
        <ChevronRight className={`w-3.5 h-3.5 flex-shrink-0 transition-transform duration-200 ${text.muted} ${open ? 'rotate-90' : ''}`} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 flex-wrap">
            <span className={`text-xs font-bold ${text.heading}`}>Visit {visit.visitNumber ?? '—'}</span>
            {visit.status && (
              <span className={`inline-flex items-center gap-1 text-[10px] font-bold uppercase tracking-wide ${st.txt}`}>
                <span className={`w-1.5 h-1.5 rounded-full ${st.dot}`} /> {titleCase(visit.status.replace(/_/g, ' '))}
              </span>
            )}
          </div>
          <div className={`flex items-center gap-2 text-[11px] mt-0.5 ${text.muted}`}>
            {visit.arrivalTime && <span className="inline-flex items-center gap-1"><CalendarDays className="w-3 h-3" /> {new Date(visit.arrivalTime).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })}</span>}
          </div>
        </div>
        {!open && (
          <span className={`ml-auto text-[10px] font-medium ${text.muted}`}>
            {hasDetail ? counts.join(' · ') : 'no detail'}
          </span>
        )}
      </button>

      {open && (
        <div className="px-3 pb-3 space-y-3">
          {!hasDetail ? (
            <p className={`text-xs ${text.muted}`}>No further clinical detail was recorded for this visit.</p>
          ) : (
            <>
              {diagnoses.length > 0 && (
                <div>
                  <SectionHeader icon={Stethoscope} label="Diagnoses" tone={isDark ? 'text-indigo-300' : 'text-indigo-600'} count={diagnoses.length} text={text} />
                  <div className="mt-1.5 space-y-1.5">
                    {diagnoses.map((d, i) => <DiagnosisRow key={i} raw={d} isDark={isDark} text={text} />)}
                  </div>
                </div>
              )}
              {labs.length > 0 && (
                <div>
                  <SectionHeader icon={FlaskConical} label="Labs & tests" tone={isDark ? 'text-red-300' : 'text-red-600'} count={labs.length} text={text} />
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {labs.map((l, i) => <LabChip key={i} raw={l} isDark={isDark} text={text} />)}
                  </div>
                </div>
              )}
              {notes.length > 0 && (
                <div>
                  <SectionHeader icon={FileText} label="Key notes" tone={text.body} count={notes.length} text={text} />
                  <div className="mt-1.5 space-y-1.5">
                    {notes.map((n, i) => <NoteCard key={i} raw={n} isDark={isDark} text={text} />)}
                  </div>
                </div>
              )}
              {summaries.length > 0 && (
                <div>
                  <SectionHeader icon={FileText} label="Discharge summaries" tone={isDark ? 'text-emerald-300' : 'text-emerald-600'} count={summaries.length} text={text} />
                  <div className="mt-1.5 space-y-2">
                    {summaries.map((s, i) => <DischargeSummaryCard key={i} doc={s} isDark={isDark} text={text} />)}
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

// ── content rows ─────────────────────────────────────────────────────────────

function SectionHeader({ icon: Icon, label, tone, count, text }: {
  icon: typeof Stethoscope; label: string; tone: string; count?: number; text: any;
}) {
  return (
    <div className={`flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wide ${tone}`}>
      <Icon className="w-3 h-3" /> {label}
      {count != null && <span className={`ml-0.5 font-semibold ${text.muted}`}>({count})</span>}
    </div>
  );
}

function DiagnosisRow({ raw, isDark, text }: { raw: string; isDark: boolean; text: any }) {
  const d = parseDiagnosis(raw);
  return (
    <div className={`rounded-lg px-2.5 py-2 ${isDark ? 'bg-white/[0.03]' : 'bg-slate-50'}`}>
      <div className="flex items-start gap-2 flex-wrap">
        {d.primary && (
          <span className="text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded bg-violet-500/15 text-violet-500">Primary</span>
        )}
        <span className={`text-xs font-medium ${text.heading}`}>{d.text}</span>
        {d.icd && (
          <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded ${isDark ? 'bg-white/10 text-slate-300' : 'bg-slate-200 text-slate-600'}`}>{d.icd}</span>
        )}
      </div>
      {d.by && <p className={`text-[10px] mt-0.5 ${text.muted}`}>by {d.by}</p>}
    </div>
  );
}

function LabChip({ raw, isDark, text }: { raw: string; isDark: boolean; text: any }) {
  const l = parseLab(raw);
  const sev = l.severity === 'CRITICAL'
    ? (isDark ? 'bg-red-500/20 text-red-300 border-red-500/40' : 'bg-red-50 text-red-700 border-red-200')
    : l.severity === 'ABNORMAL'
      ? (isDark ? 'bg-amber-500/20 text-amber-300 border-amber-500/40' : 'bg-amber-50 text-amber-700 border-amber-200')
      : (isDark ? 'bg-white/5 text-slate-300 border-white/10' : 'bg-slate-50 text-slate-600 border-slate-200');
  return (
    <span className={`inline-flex items-center gap-1.5 text-[11px] px-2 py-1 rounded-lg border ${sev}`}>
      {l.severity === 'CRITICAL' && <AlertTriangle className="w-3 h-3" />}
      {l.name}
      {l.severity && <span className="text-[8px] font-bold uppercase tracking-wide">{l.severity}</span>}
    </span>
  );
}

function NoteCard({ raw, isDark, text }: { raw: string; isDark: boolean; text: any }) {
  const n = parseNote(raw);
  return (
    <div className={`rounded-lg px-2.5 py-2 ${isDark ? 'bg-white/[0.03]' : 'bg-slate-50'}`}>
      {n.type && <p className={`text-[9px] font-bold uppercase tracking-wide mb-0.5 ${text.muted}`}>{n.type}</p>}
      <p className={`text-xs leading-relaxed ${text.body}`}>{n.content}</p>
    </div>
  );
}

// ── discharge summary: parsed structured render ──────────────────────────────

function DischargeSummaryCard({ doc, isDark, text }: {
  doc: CrossHospitalDischargeSummary; isDark: boolean; text: any;
}) {
  const [open, setOpen] = useState(false);
  const hasContent = !!doc.content && doc.content.trim().length > 0;
  const parsed = hasContent ? parseDischargeSummary(doc.content!) : null;

  return (
    <div className={`rounded-lg border ${isDark ? 'border-white/10 bg-white/[0.02]' : 'border-slate-200 bg-white'}`}>
      <button
        type="button"
        onClick={() => hasContent && setOpen((o) => !o)}
        aria-expanded={hasContent ? open : undefined}
        className={`w-full text-left px-3 py-2 flex items-center gap-2 ${hasContent ? 'cursor-pointer' : 'cursor-default'}`}
      >
        <FileText className={`w-3.5 h-3.5 flex-shrink-0 ${isDark ? 'text-emerald-300' : 'text-emerald-600'}`} />
        <span className={`text-xs font-semibold flex-1 min-w-0 truncate ${text.body}`}>{doc.title}</span>
        <span className={`text-[9px] font-bold uppercase px-1.5 py-0.5 rounded ${
          doc.signed
            ? (isDark ? 'bg-emerald-500/20 text-emerald-300' : 'bg-emerald-50 text-emerald-700')
            : (isDark ? 'bg-white/10 text-slate-300' : 'bg-slate-100 text-slate-500')}`}>
          {doc.signed ? 'signed' : 'unsigned'}
        </span>
        {hasContent && (
          <ChevronRight className={`w-3.5 h-3.5 flex-shrink-0 transition-transform ${text.muted} ${open ? 'rotate-90' : ''}`} />
        )}
      </button>

      {open && parsed && (
        <div className={`px-3 pb-3 pt-1 border-t ${isDark ? 'border-white/10' : 'border-slate-100'}`}>
          {parsed.generated && (
            <p className={`text-[10px] mb-2 ${text.muted}`}>Generated {parsed.generated}</p>
          )}
          <div className="space-y-2.5">
            {parsed.sections.map((s, i) => (
              <SummarySection key={i} title={s.title} lines={s.lines} isDark={isDark} text={text} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function SummarySection({ title, lines, isDark, text }: {
  title: string; lines: string[]; isDark: boolean; text: any;
}) {
  // Detect key:value lines so demographics / visit details render as a tidy grid.
  const kvs = lines.map((l) => {
    const m = l.match(/^([A-Za-z][A-Za-z /()]{0,26}):\s+(.+)$/);
    return m ? { k: m[1], v: m[2] } : null;
  });
  const asGrid = lines.length > 0 && kvs.every(Boolean);
  const empty = lines.length === 1 && /^(no |none)/i.test(lines[0]);

  return (
    <div>
      <p className={`text-[10px] font-bold uppercase tracking-wide ${isDark ? 'text-cyan-300' : 'text-cyan-600'}`}>{titleCase(title)}</p>
      {empty ? (
        <p className={`text-[11px] italic mt-0.5 ${text.muted}`}>{lines[0]}</p>
      ) : asGrid ? (
        <dl className="mt-1 grid grid-cols-[minmax(90px,auto)_1fr] gap-x-3 gap-y-0.5">
          {kvs.map((p, i) => (
            <div key={i} className="contents">
              <dt className={`text-[11px] ${text.muted}`}>{p!.k}</dt>
              <dd className={`text-[11px] font-medium ${text.body}`}>{p!.v}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <ul className="mt-1 space-y-0.5">
          {lines.map((l, i) => (
            <li key={i} className={`text-[11px] leading-relaxed ${text.body}`}>{l}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ── util ─────────────────────────────────────────────────────────────────────

function ageFrom(dob: string | null): number | null {
  if (!dob) return null;
  const d = new Date(dob);
  if (isNaN(d.getTime())) return null;
  const now = new Date();
  let age = now.getFullYear() - d.getFullYear();
  const m = now.getMonth() - d.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < d.getDate())) age--;
  return age >= 0 && age < 200 ? age : null;
}
