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
import { FileText, Send, AlertCircle, History, Lock, Loader2 } from 'lucide-react';
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
  const cardCls = `rounded-xl shadow-md p-4 ${
    isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'
  }`;
  const headerTextCls = isDark ? 'text-white' : 'text-gray-900';
  const subtleTextCls = isDark ? 'text-slate-400' : 'text-gray-500';
  const inputCls = `w-full px-3 py-2 text-sm rounded-lg border ${
    isDark
      ? 'bg-white/5 border-white/10 text-white placeholder:text-slate-500'
      : 'bg-white border-gray-200 text-gray-900 placeholder:text-gray-400'
  } focus:outline-none focus:ring-2 focus:ring-blue-500/40`;

  return (
    <div className={`${cardCls} ${className ?? ''}`}>
      <div className="flex items-center justify-between mb-3">
        <h3 className={`text-sm font-bold flex items-center gap-2 ${headerTextCls}`}>
          <FileText className="w-4 h-4" />
          Clinical Notes
        </h3>
        <span className={`text-xs ${subtleTextCls}`}>
          {notes.length} {notes.length === 1 ? 'note' : 'notes'}
        </span>
      </div>

      {/* ── Composer ─────────────────────────────────────────────────── */}
      <form onSubmit={handleSubmit} className="mb-4 space-y-2">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
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
          placeholder={authUser ? `Note content — signing as ${authUser.fullName}` : 'Note content'}
          value={composerContent}
          onChange={(e) => setComposerContent(e.target.value)}
          disabled={submitting}
        />
        {submitError && (
          <div className="flex items-center gap-1.5 text-xs text-red-500">
            <AlertCircle className="w-3.5 h-3.5" />
            {submitError}
          </div>
        )}
        <div className="flex items-center justify-between">
          <p className={`text-[11px] ${subtleTextCls} flex items-center gap-1`}>
            <Lock className="w-3 h-3" />
            Notes are append-only. Corrections create a new entry; the original is preserved.
          </p>
          <button
            type="submit"
            disabled={!composerContent.trim() || submitting || !authUser}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-xl transition-colors ${
              composerContent.trim() && !submitting && authUser
                ? 'bg-cyan-600 hover:bg-cyan-700 text-white'
                : 'bg-gray-300 text-gray-500 cursor-not-allowed'
            }`}
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
            {submitting ? 'Saving…' : 'Save note'}
          </button>
        </div>
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
        <div className={`text-center py-8 ${subtleTextCls}`}>
          <FileText className="w-12 h-12 mx-auto mb-3 opacity-30" />
          <p className="text-sm">No clinical notes yet for this visit.</p>
        </div>
      ) : (
        <div ref={listRef} className="space-y-2 max-h-[60vh] overflow-y-auto pr-1">
          {notes.map((note) => {
            const isSuperseded = supersededIds.has(note.id);
            const original = note.supersedesId ? noteById.get(note.supersedesId) : null;
            const isEditingThis = supersedingId === note.id;

            const itemBg = isDark
              ? (isSuperseded ? 'bg-white/[0.02]' : 'bg-white/[0.04]')
              : (isSuperseded ? 'bg-gray-50' : 'bg-white');
            const itemBorder = isDark
              ? (isSuperseded ? 'border-white/5' : 'border-white/10')
              : (isSuperseded ? 'border-gray-200' : 'border-gray-200');

            return (
              <div
                key={note.id}
                className={`rounded-lg border p-3 ${itemBg} ${itemBorder} ${isSuperseded ? 'opacity-70' : ''}`}
              >
                {/* Header line */}
                <div className="flex flex-wrap items-center gap-2 mb-1">
                  <span className={`text-xs font-bold ${headerTextCls}`}>
                    {NOTE_TYPE_LABEL[note.noteType] ?? note.noteType}
                  </span>
                  {note.section && (
                    <span className={`text-[10px] uppercase tracking-wider ${subtleTextCls}`}>
                      · {note.section}
                    </span>
                  )}
                  {note.authorRole && (
                    <span className="text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-blue-100 text-blue-700">
                      {roleLabel(note.authorRole)}
                    </span>
                  )}
                  {note.supersedesId && (
                    <span className="text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-amber-100 text-amber-800 inline-flex items-center gap-1">
                      <History className="w-3 h-3" />
                      Correction
                    </span>
                  )}
                  {isSuperseded && (
                    <span className="text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-gray-200 text-gray-700">
                      Superseded
                    </span>
                  )}
                  <span className={`text-[11px] ml-auto ${subtleTextCls}`}>
                    {formatRecordedAt(note.recordedAt)}
                  </span>
                </div>

                {/* Author + correction-of pointer */}
                <div className={`text-[11px] mb-1.5 ${subtleTextCls}`}>
                  {note.recordedByName || '—'}
                  {original && (
                    <span className="ml-2">
                      · corrects “{original.content.slice(0, 60)}{original.content.length > 60 ? '…' : ''}”
                    </span>
                  )}
                </div>

                {/* Body */}
                <p
                  className={`text-sm whitespace-pre-wrap ${
                    isDark ? 'text-slate-200' : 'text-gray-800'
                  } ${isSuperseded ? 'line-through decoration-1' : ''}`}
                >
                  {note.content}
                </p>

                {/* Inline supersede composer */}
                {isEditingThis && (
                  <div className={`mt-2 p-2 rounded-md border ${
                    isDark ? 'border-white/10 bg-white/[0.03]' : 'border-amber-200 bg-amber-50'
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
                    <div className="flex items-center justify-end gap-2 mt-1.5">
                      <button
                        type="button"
                        onClick={cancelSupersede}
                        disabled={supersedeBusy}
                        className={`text-xs px-2.5 py-1 rounded-xl ${
                          isDark ? 'text-slate-300 hover:bg-white/5' : 'text-gray-600 hover:bg-gray-100'
                        }`}
                      >
                        Cancel
                      </button>
                      <button
                        type="button"
                        onClick={() => submitSupersede(note)}
                        disabled={!supersedeContent.trim() || supersedeBusy}
                        className={`inline-flex items-center gap-1.5 text-xs px-2.5 py-1 rounded-xl font-semibold ${
                          supersedeContent.trim() && !supersedeBusy
                            ? 'bg-cyan-600 hover:bg-cyan-700 text-white'
                            : 'bg-gray-300 text-gray-500 cursor-not-allowed'
                        }`}
                      >
                        {supersedeBusy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <History className="w-3.5 h-3.5" />}
                        {supersedeBusy ? 'Saving…' : 'Save correction'}
                      </button>
                    </div>
                  </div>
                )}

                {/* Per-row actions */}
                {!isEditingThis && !isSuperseded && canCorrect && (
                  <div className="mt-2 flex items-center justify-end">
                    <button
                      type="button"
                      onClick={() => startSupersede(note)}
                      className={`text-[11px] inline-flex items-center gap-1 px-2 py-0.5 rounded-xl ${
                        isDark
                          ? 'text-amber-300 hover:bg-amber-500/10'
                          : 'text-amber-700 hover:bg-amber-100'
                      }`}
                      title="Append a correction; the original stays in the record"
                    >
                      <History className="w-3 h-3" />
                      Correct
                    </button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default ClinicalNotesPanel;
