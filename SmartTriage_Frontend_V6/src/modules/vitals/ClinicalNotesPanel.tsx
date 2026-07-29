/**
 * ClinicalNotesPanel — read/write surface for clinical notes on a single visit.
 *
 * Renders the visit's clinical notes in chronological (ascending) order so a
 * clinician picking up handover reads the narrative top-to-bottom. Notes are
 * append-only: an inline composer creates a new note, and each existing note
 * exposes a "Correct" action that creates a correction row via the supersede
 * endpoint. The original is never mutated; the chain is rendered so readers
 * can see "Note A → corrected by Note B".
 *
 * Subscribes to {@code /topic/visit/{visitId}/notes} for real-time fan-in
 * from other clinicians editing the same visit.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  FileText, Send, AlertCircle, History, Lock, Loader2, ShieldCheck,
} from 'lucide-react';
import { clinicalNoteApi } from '@/api/clinicalNotes';
import { subscribeToClinicalNotes } from '@/api/websocket';
import type { ClinicalNoteResponse, NoteType, Role } from '@/api/types';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';

// All NoteType values, in roughly the order a clinician encounters them.
const NOTE_TYPES: { value: NoteType; label: string }[] = [
  { value: 'TRIAGE_NOTE',                     label: 'Triage note' },
  { value: 'HISTORY_OF_PRESENTING_COMPLAINT', label: 'History of presenting complaint' },
  { value: 'PHYSICAL_FINDINGS',               label: 'Physical findings' },
  { value: 'PAST_MEDICAL_HISTORY',            label: 'Past medical history' },
  { value: 'ALLERGIES',                       label: 'Allergies' },
  { value: 'CURRENT_MEDICATIONS',             label: 'Current medications' },
  { value: 'REVIEW_OF_SYSTEMS',               label: 'Review of systems' },
  { value: 'SOCIAL_HISTORY',                  label: 'Social history' },
  { value: 'FAMILY_HISTORY',                  label: 'Family history' },
  { value: 'NURSING_NOTE',                    label: 'Nursing note' },
  { value: 'DOCTOR_NOTE',                     label: "Doctor's note" },
  { value: 'PROGRESS_NOTE',                   label: 'Progress note' },
  { value: 'TREATMENT_PLAN',                  label: 'Treatment plan' },
  { value: 'HANDOVER',                        label: 'Handover' },
  { value: 'DISCHARGE_SUMMARY',               label: 'Discharge summary' },
  { value: 'OTHER',                           label: 'Other' },
];

const NOTE_TYPE_LABEL: Record<NoteType, string> = NOTE_TYPES.reduce(
  (acc, t) => ({ ...acc, [t.value]: t.label }),
  {} as Record<NoteType, string>,
);

/**
 * Visual identity per note family. The accent stripe + type chip let a
 * clinician scan the narrative by COLOUR (assessment vs nursing vs plan)
 * before reading a word — the same trick the vitals tiles use.
 */
const TYPE_ACCENT: Record<string, { bar: string; chipDark: string; chipLight: string }> = {
  ASSESSMENT: { bar: '#f59e0b', chipDark: 'bg-amber-500/15 text-amber-300',   chipLight: 'bg-amber-100 text-amber-800' },
  NURSING:    { bar: '#10b981', chipDark: 'bg-emerald-500/15 text-emerald-300', chipLight: 'bg-emerald-100 text-emerald-800' },
  DOCTOR:     { bar: '#06b6d4', chipDark: 'bg-cyan-500/15 text-cyan-300',     chipLight: 'bg-cyan-100 text-cyan-800' },
  PLAN:       { bar: '#8b5cf6', chipDark: 'bg-violet-500/15 text-violet-300', chipLight: 'bg-violet-100 text-violet-800' },
  OTHER:      { bar: '#64748b', chipDark: 'bg-slate-500/15 text-slate-300',   chipLight: 'bg-slate-200 text-slate-700' },
};

function accentFor(type: NoteType) {
  switch (type) {
    case 'TRIAGE_NOTE':
    case 'HISTORY_OF_PRESENTING_COMPLAINT':
    case 'PHYSICAL_FINDINGS':
    case 'REVIEW_OF_SYSTEMS':
      return TYPE_ACCENT.ASSESSMENT;
    case 'NURSING_NOTE':
      return TYPE_ACCENT.NURSING;
    case 'DOCTOR_NOTE':
    case 'PROGRESS_NOTE':
      return TYPE_ACCENT.DOCTOR;
    case 'TREATMENT_PLAN':
    case 'HANDOVER':
    case 'DISCHARGE_SUMMARY':
      return TYPE_ACCENT.PLAN;
    default:
      return TYPE_ACCENT.OTHER;
  }
}

const CORRECTION_ALLOWED: ReadonlySet<string> = new Set([
  'DOCTOR', 'NURSE', 'SUPER_ADMIN',
]);

function formatRecordedAt(iso: string | undefined | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

function roleLabel(role: Role | null | undefined): string {
  if (!role) return '';
  // Prettier than the underscore-shouty enum value.
  return role.replace(/_/g, ' ').toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function initialsOf(name: string | null | undefined): string {
  if (!name) return '·';
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '·';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

interface ClinicalNotesPanelProps {
  /** Visit UUID. (In this app, patientId in the route IS the visitId.) */
  visitId: string;
  /** Optional outer className for spacing inside the host tab. */
  className?: string;
}

export function ClinicalNotesPanel({ visitId, className }: ClinicalNotesPanelProps) {
  const { isDark, glassCard } = useTheme();
  const authUser = useAuthStore((s) => s.user);
  const canCorrect = !!authUser && CORRECTION_ALLOWED.has(authUser.role);

  const [notes, setNotes] = useState<ClinicalNoteResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Composer state
  const [composerType, setComposerType] = useState<NoteType>('PROGRESS_NOTE');
  const [composerSection, setComposerSection] = useState('');
  const [composerContent, setComposerContent] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Per-row supersede state
  const [supersedingId, setSupersedingId] = useState<string | null>(null);
  const [supersedeContent, setSupersedeContent] = useState('');
  const [supersedeBusy, setSupersedeBusy] = useState(false);
  const [supersedeError, setSupersedeError] = useState<string | null>(null);

  // Auto-scroll to newest on append.
  const listRef = useRef<HTMLDivElement | null>(null);

  // Initial fetch.
  useEffect(() => {
    if (!visitId) return;
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    clinicalNoteApi.getAllByVisit(visitId)
      .then((rows) => {
        if (cancelled) return;
        // Backend returns ascending; defensive sort just in case.
        const sorted = [...rows].sort(
          (a, b) => new Date(a.recordedAt).getTime() - new Date(b.recordedAt).getTime(),
        );
        setNotes(sorted);
      })
      .catch((err) => {
        if (cancelled) return;
        setLoadError(err?.message ?? 'Failed to load clinical notes');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [visitId]);

  // Real-time fan-in. Other clinicians' creates and corrections land here.
  const handleIncoming = useCallback((incoming: ClinicalNoteResponse) => {
    setNotes((prev) => {
      if (prev.some((n) => n.id === incoming.id)) return prev; // de-dup own echoes
      return [...prev, incoming];
    });
  }, []);

  useEffect(() => {
    if (!visitId) return;
    const unsub = subscribeToClinicalNotes(visitId, handleIncoming);
    return () => unsub();
  }, [visitId, handleIncoming]);

  // Set of original-note ids that have been superseded by a later row.
  // Used to render the "Superseded" badge on the original.
  const supersededIds = useMemo(() => {
    const s = new Set<string>();
    for (const n of notes) if (n.supersedesId) s.add(n.supersedesId);
    return s;
  }, [notes]);

  const noteById = useMemo(() => {
    const m = new Map<string, ClinicalNoteResponse>();
    for (const n of notes) m.set(n.id, n);
    return m;
  }, [notes]);

  const activeCount = notes.length - supersededIds.size;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!composerContent.trim() || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const created = await clinicalNoteApi.create({
        visitId,
        noteType: composerType,
        content: composerContent.trim(),
        section: composerSection.trim() || undefined,
      });
      // Optimistic append (WS will likely arrive too — handleIncoming de-dups).
      setNotes((prev) => prev.some((n) => n.id === created.id) ? prev : [...prev, created]);
      setComposerContent('');
      setComposerSection('');
      // Scroll to the new note.
      requestAnimationFrame(() => {
        listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' });
      });
    } catch (err: any) {
      setSubmitError(err?.message ?? 'Failed to save note');
    } finally {
      setSubmitting(false);
    }
  };

  const startSupersede = (note: ClinicalNoteResponse) => {
    setSupersedingId(note.id);
    setSupersedeContent(note.content);
    setSupersedeError(null);
  };

  const cancelSupersede = () => {
    setSupersedingId(null);
    setSupersedeContent('');
    setSupersedeError(null);
  };

  const submitSupersede = async (original: ClinicalNoteResponse) => {
    if (!supersedeContent.trim() || supersedeBusy) return;
    setSupersedeBusy(true);
    setSupersedeError(null);
    try {
      const correction = await clinicalNoteApi.supersede(original.id, {
        visitId,
        noteType: original.noteType,
        content: supersedeContent.trim(),
        section: original.section || undefined,
      });
      setNotes((prev) => prev.some((n) => n.id === correction.id) ? prev : [...prev, correction]);
      cancelSupersede();
    } catch (err: any) {
      setSupersedeError(err?.message ?? 'Failed to save correction');
    } finally {
      setSupersedeBusy(false);
    }
  };

  // ── Styling helpers ────────────────────────────────────────────────────
  const headerTextCls = isDark ? 'text-white' : 'text-slate-900';
  const subtleTextCls = isDark ? 'text-slate-400' : 'text-slate-500';
  const inputCls = `w-full px-3 py-2 text-sm rounded-xl border transition-colors ${
    isDark
      ? 'bg-white/5 border-white/10 text-white placeholder:text-slate-500'
      : 'bg-white border-slate-200 text-slate-900 placeholder:text-slate-400'
  } focus:outline-none focus:ring-2 focus:ring-cyan-500/40 focus:border-cyan-500/40`;
  const primaryBtnCls = (enabled: boolean) =>
    `inline-flex items-center gap-1.5 px-3.5 py-2 text-xs font-bold rounded-xl transition-all ${
      enabled
        ? 'bg-cyan-600 hover:bg-cyan-700 text-white shadow-lg shadow-cyan-600/20'
        : (isDark ? 'bg-white/10 text-slate-500' : 'bg-slate-200 text-slate-400') + ' cursor-not-allowed'
    }`;

  return (
    // NOTE: glassCard from useTheme is a CSSProperties OBJECT — it must go
    // through `style`, never string-concatenated into className (the old
    // panel did exactly that, rendering "[object Object]" as a class and
    // losing the glass look entirely).
    <div
      className={`rounded-2xl p-4 ${isDark ? 'border border-white/10' : 'bg-white border border-slate-200 shadow-sm'} ${className ?? ''}`}
      style={isDark ? glassCard : undefined}
      data-panel="clinical-notes"
    >
      {/* ── Header ──────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-2.5">
          <span className={`w-8 h-8 rounded-xl flex items-center justify-center ${
            isDark ? 'bg-cyan-500/15 text-cyan-300' : 'bg-cyan-50 text-cyan-600'
          }`}>
            <FileText className="w-4 h-4" />
          </span>
          <div>
            <h3 className={`text-sm font-bold leading-tight ${headerTextCls}`}>Clinical Notes</h3>
            <p className={`text-[10px] font-semibold uppercase tracking-wider ${subtleTextCls}`}>
              Append-only record
            </p>
          </div>
        </div>
        <div className="text-right">
          <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-xl text-[11px] font-bold ${
            isDark ? 'bg-white/10 text-slate-200' : 'bg-slate-100 text-slate-700'
          }`}>
            {activeCount} {activeCount === 1 ? 'note' : 'notes'}
          </span>
          {supersededIds.size > 0 && (
            <p className={`text-[10px] mt-1 ${subtleTextCls}`}>
              +{supersededIds.size} superseded
            </p>
          )}
        </div>
      </div>

      {/* ── Composer ─────────────────────────────────────────────────── */}
      <form onSubmit={handleSubmit} className={`rounded-xl border p-3 mb-4 ${
        isDark ? 'border-white/10 bg-white/[0.03]' : 'border-slate-200 bg-slate-50/60'
      }`}>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mb-2">
          <select
            className={inputCls}
            value={composerType}
            onChange={(e) => setComposerType(e.target.value as NoteType)}
            disabled={submitting}
          >
            {NOTE_TYPES.map((t) => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
          <input
            type="text"
            className={inputCls}
            placeholder="Section (optional)"
            value={composerSection}
            onChange={(e) => setComposerSection(e.target.value)}
            maxLength={100}
            disabled={submitting}
          />
        </div>
        <textarea
          className={`${inputCls} min-h-[88px] resize-y`}
          placeholder="Write the note…"
          value={composerContent}
          onChange={(e) => setComposerContent(e.target.value)}
          disabled={submitting}
        />
        {submitError && (
          <div className="flex items-center gap-1.5 text-xs text-red-500 mt-2">
            <AlertCircle className="w-3.5 h-3.5" />
            {submitError}
          </div>
        )}
        <div className="flex items-center justify-between gap-3 mt-2.5">
          {authUser ? (
            <span className="inline-flex items-center gap-2 min-w-0">
              <span className={`w-6 h-6 rounded-full flex items-center justify-center text-[10px] font-bold shrink-0 ${
                isDark ? 'bg-cyan-500/20 text-cyan-300' : 'bg-cyan-100 text-cyan-700'
              }`}>
                {initialsOf(authUser.fullName)}
              </span>
              <span className={`text-[11px] truncate ${subtleTextCls}`}>
                Signing as <span className={`font-semibold ${isDark ? 'text-slate-200' : 'text-slate-700'}`}>{authUser.fullName}</span>
                {authUser.role ? ` · ${roleLabel(authUser.role as Role)}` : ''}
              </span>
            </span>
          ) : <span />}
          <button
            type="submit"
            disabled={!composerContent.trim() || submitting || !authUser}
            className={primaryBtnCls(!!composerContent.trim() && !submitting && !!authUser)}
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
            {submitting ? 'Saving…' : 'Save note'}
          </button>
        </div>
        <p className={`text-[10px] mt-2 flex items-center gap-1 ${subtleTextCls}`}>
          <Lock className="w-3 h-3 shrink-0" />
          Append-only — corrections create a new entry; the original is preserved.
        </p>
      </form>

      {/* ── List ─────────────────────────────────────────────────────── */}
      {loading ? (
        <div className={`text-center py-8 ${subtleTextCls}`}>
          <Loader2 className="w-6 h-6 mx-auto mb-2 animate-spin opacity-60" />
          <p className="text-sm">Loading clinical notes…</p>
        </div>
      ) : loadError ? (
        <div className="flex items-center gap-2 text-sm text-red-500 py-4">
          <AlertCircle className="w-4 h-4" />
          {loadError}
        </div>
      ) : notes.length === 0 ? (
        <div className={`text-center py-10 ${subtleTextCls}`}>
          <span className={`w-14 h-14 mx-auto mb-3 rounded-2xl border-2 border-dashed flex items-center justify-center ${
            isDark ? 'border-white/15' : 'border-slate-200'
          }`}>
            <FileText className="w-6 h-6 opacity-40" />
          </span>
          <p className={`text-sm font-semibold ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>
            No notes yet for this visit
          </p>
          <p className="text-xs mt-1">The first entry starts the clinical narrative.</p>
        </div>
      ) : (
        <div ref={listRef} className="space-y-2.5 max-h-[60vh] overflow-y-auto pr-1">
          {notes.map((note) => {
            const isSuperseded = supersededIds.has(note.id);
            const original = note.supersedesId ? noteById.get(note.supersedesId) : null;
            const isEditingThis = supersedingId === note.id;
            const accent = accentFor(note.noteType);

            return (
              <div
                key={note.id}
                className={`relative rounded-xl border overflow-hidden transition-opacity ${
                  isDark
                    ? (isSuperseded ? 'bg-white/[0.02] border-white/5' : 'bg-white/[0.04] border-white/10')
                    : (isSuperseded ? 'bg-slate-50 border-slate-200' : 'bg-white border-slate-200 shadow-sm')
                } ${isSuperseded ? 'opacity-60' : ''}`}
              >
                {/* Type accent stripe */}
                <span
                  className="absolute left-0 top-0 bottom-0 w-1"
                  style={{ background: accent.bar, opacity: isSuperseded ? 0.35 : 0.9 }}
                />
                <div className="p-3 pl-4">
                  {/* Header line */}
                  <div className="flex flex-wrap items-center gap-1.5 mb-1.5">
                    <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md ${
                      isDark ? accent.chipDark : accent.chipLight
                    }`}>
                      {NOTE_TYPE_LABEL[note.noteType] ?? note.noteType}
                    </span>
                    {note.section && (
                      <span className={`text-[10px] font-semibold ${subtleTextCls}`}>
                        {note.section}
                      </span>
                    )}
                    {note.supersedesId && (
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-md inline-flex items-center gap-1 ${
                        isDark ? 'bg-amber-500/15 text-amber-300' : 'bg-amber-100 text-amber-800'
                      }`}>
                        <History className="w-3 h-3" />
                        Correction
                      </span>
                    )}
                    {isSuperseded && (
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-md ${
                        isDark ? 'bg-white/10 text-slate-400' : 'bg-slate-200 text-slate-600'
                      }`}>
                        Superseded
                      </span>
                    )}
                    <span className={`text-[11px] ml-auto tabular-nums ${subtleTextCls}`}>
                      {formatRecordedAt(note.recordedAt)}
                    </span>
                  </div>

                  {/* Author */}
                  <div className="flex items-center gap-1.5 mb-1.5">
                    <span className={`w-5 h-5 rounded-full flex items-center justify-center text-[9px] font-bold shrink-0 ${
                      isDark ? 'bg-white/10 text-slate-300' : 'bg-slate-100 text-slate-600'
                    }`}>
                      {initialsOf(note.recordedByName)}
                    </span>
                    <span className={`text-[11px] font-semibold ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>
                      {note.recordedByName || 'Unknown author'}
                    </span>
                    {note.authorRole && (
                      <span className={`text-[10px] ${subtleTextCls}`}>· {roleLabel(note.authorRole)}</span>
                    )}
                    {note.authorRole && (
                      <ShieldCheck className={`w-3 h-3 ${isDark ? 'text-emerald-400/70' : 'text-emerald-500/70'}`} aria-label="Server-attributed author" />
                    )}
                  </div>

                  {/* Correction-of pointer */}
                  {original && (
                    <p className={`text-[11px] mb-1 italic ${subtleTextCls}`}>
                      ↳ corrects “{original.content.slice(0, 60)}{original.content.length > 60 ? '…' : ''}”
                    </p>
                  )}

                  {/* Body */}
                  <p
                    className={`text-sm whitespace-pre-wrap leading-relaxed ${
                      isDark ? 'text-slate-200' : 'text-slate-800'
                    } ${isSuperseded ? 'line-through decoration-1' : ''}`}
                  >
                    {note.content}
                  </p>

                  {/* Inline supersede composer */}
                  {isEditingThis && (
                    <div className={`mt-2.5 p-2.5 rounded-xl border ${
                      isDark ? 'border-amber-500/20 bg-amber-500/[0.06]' : 'border-amber-200 bg-amber-50'
                    }`}>
                      <p className={`text-[11px] mb-1.5 ${subtleTextCls}`}>
                        The original will be preserved. Submitting writes a new entry that supersedes it.
                      </p>
                      <textarea
                        className={`${inputCls} min-h-[72px] resize-y`}
                        value={supersedeContent}
                        onChange={(e) => setSupersedeContent(e.target.value)}
                        disabled={supersedeBusy}
                      />
                      {supersedeError && (
                        <div className="flex items-center gap-1.5 text-xs text-red-500 mt-1">
                          <AlertCircle className="w-3.5 h-3.5" />
                          {supersedeError}
                        </div>
                      )}
                      <div className="flex items-center justify-end gap-2 mt-2">
                        <button
                          type="button"
                          onClick={cancelSupersede}
                          disabled={supersedeBusy}
                          className={`text-xs px-2.5 py-1.5 rounded-xl font-semibold ${
                            isDark ? 'text-slate-300 hover:bg-white/5' : 'text-slate-600 hover:bg-slate-100'
                          }`}
                        >
                          Cancel
                        </button>
                        <button
                          type="button"
                          onClick={() => submitSupersede(note)}
                          disabled={!supersedeContent.trim() || supersedeBusy}
                          className={primaryBtnCls(!!supersedeContent.trim() && !supersedeBusy)}
                        >
                          {supersedeBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <History className="w-3.5 h-3.5" />}
                          {supersedeBusy ? 'Saving…' : 'Save correction'}
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Per-row actions */}
                  {!isEditingThis && !isSuperseded && canCorrect && (
                    <div className="mt-1.5 flex items-center justify-end">
                      <button
                        type="button"
                        onClick={() => startSupersede(note)}
                        className={`text-[11px] font-semibold inline-flex items-center gap-1 px-2 py-1 rounded-lg transition-colors ${
                          isDark
                            ? 'text-amber-300/80 hover:text-amber-300 hover:bg-amber-500/10'
                            : 'text-amber-700/80 hover:text-amber-800 hover:bg-amber-100'
                        }`}
                        title="Append a correction; the original stays in the record"
                      >
                        <History className="w-3 h-3" />
                        Correct
                      </button>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default ClinicalNotesPanel;
