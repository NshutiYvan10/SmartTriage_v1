/* ═══════════════════════════════════════════════════════════════
   ClinicalSignsTab — read + record the patient's clinical-sign timeline.

   Three views in one tab:
     1. Current State — every sign that's ever been positive on this
        visit, grouped by category, each showing its latest status,
        when last observed, and by whom.
     2. Update Sign(s) — searchable picker + status enum + optional
        glucose + notes. Supports recording several signs at the same
        observation time, useful on a ward round.
     3. Timeline — chronological feed of every event ever recorded.

   The data model is event-log: every change is a row, current state is
   the latest event per (visit, sign_code). The triage submission
   auto-bootstraps a baseline event for every positive flag, so this tab
   opens populated rather than empty.
   ═══════════════════════════════════════════════════════════════ */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { format } from 'date-fns';
import {
  Activity, AlertTriangle, Heart, Stethoscope, Sparkles, Plus, X,
  Send, Loader2, Search, ChevronDown, ChevronUp, Clock, History, RefreshCw,
} from 'lucide-react';
import {
  clinicalSignsApi,
  type ClinicalSignEventResponse,
  type ClinicalSignStatus,
  type ClinicalSignCategory,
  type RecordClinicalSignEntry,
} from '@/api/clinicalSigns';
import {
  CLINICAL_SIGN_DEFINITIONS,
  SIGN_BY_CODE,
  CATEGORY_LABEL,
  CATEGORY_ORDER,
  CATEGORY_TONE,
  type ClinicalSignDefinition,
} from './clinicalSignDefinitions';
import { SignSparkline } from './SignSparkline';
import { ChangesSinceTriageCard } from './ChangesSinceTriageCard';
import { buildSignTimelines } from './clinicalSignDiff';

const STATUS_TONE: Record<ClinicalSignStatus, { label: string; className: string }> = {
  PRESENT:    { label: 'PRESENT',    className: 'text-red-300 bg-red-500/20 border-red-500/30' },
  ABSENT:     { label: 'ABSENT',     className: 'text-emerald-300 bg-emerald-500/20 border-emerald-500/30' },
  IMPROVING:  { label: 'IMPROVING',  className: 'text-cyan-300 bg-cyan-500/20 border-cyan-500/30' },
  WORSENING:  { label: 'WORSENING',  className: 'text-orange-300 bg-orange-500/20 border-orange-500/30' },
  UNKNOWN:    { label: 'UNKNOWN',    className: 'text-slate-300 bg-slate-500/20 border-slate-500/30' },
};

const ALL_STATUSES: ClinicalSignStatus[] = ['PRESENT', 'ABSENT', 'IMPROVING', 'WORSENING', 'UNKNOWN'];

interface Props {
  visitId: string;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
  /**
   * Optional callback fired after any successful clinical-sign batch
   * write. Recording a sign on the backend can produce a server-side
   * auto re-triage (Round 3+5), which changes
   * visit.currentTriageCategory and creates new TriageRecord +
   * ClinicalAlert rows. The parent surface (VisitDetailPage) wires
   * this to its own loadData() so the visit/triage/alerts state
   * refreshes from authoritative storage after every sign update.
   *
   * Without this, the doctor sees the new sign in the timeline but
   * the page header / Overview banner / Triage tab still show the
   * stale category — which is the most direct cause of the "engine
   * isn't firing" bug.
   */
  onVisitMayHaveChanged?: () => void;
}

export function ClinicalSignsTab({ visitId, glassCard, glassInner, isDark, text, onVisitMayHaveChanged }: Props) {
  // ── Data ──
  const [currentState, setCurrentState] = useState<ClinicalSignEventResponse[]>([]);
  const [history, setHistory] = useState<ClinicalSignEventResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // ── View state ──
  const [view, setView] = useState<'current' | 'timeline'>('current');
  const [showRecord, setShowRecord] = useState(false);

  const loadAll = useCallback(async () => {
    if (!visitId) return;
    setLoading(true);
    setError(null);
    try {
      const [cs, hs] = await Promise.allSettled([
        clinicalSignsApi.getCurrentState(visitId),
        clinicalSignsApi.getHistory(visitId),
      ]);
      if (cs.status === 'fulfilled') setCurrentState(Array.isArray(cs.value) ? cs.value : []);
      if (hs.status === 'fulfilled') setHistory(Array.isArray(hs.value) ? hs.value : []);
      if (cs.status === 'rejected' && hs.status === 'rejected') {
        setError('Unable to load clinical signs. Please retry.');
      }
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { loadAll(); }, [loadAll]);

  // ── Group current state by category for the panel ──
  const currentByCategory = useMemo(() => {
    const grouped: Record<ClinicalSignCategory, ClinicalSignEventResponse[]> = {
      EMERGENCY: [], PEDIATRIC_EMERGENCY: [], MSAT_VU: [], MSAT_URG: [], SPECIAL: [],
    };
    for (const e of currentState) {
      grouped[e.signCategory].push(e);
    }
    return grouped;
  }, [currentState]);

  // Per-sign chronological event list — built once from the full
  // history and passed down so the sparkline next to each sign card
  // doesn't have to re-derive its own slice.
  const timelines = useMemo(() => buildSignTimelines(history), [history]);

  // Has anything ever been recorded? — drives empty-state copy.
  const hasAnyEvents = currentState.length > 0;

  return (
    <div className="space-y-4">
      {/* ── Header ── */}
      <div className="flex items-center justify-between">
        <div>
          <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Clinical Signs</h3>
          <p className={`text-xs mt-0.5 ${text.muted}`}>
            Track how emergency signs and mSAT discriminators evolve since triage.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={loadAll}
            className={`p-2 rounded-lg ${text.muted} hover:bg-white/5 transition-colors`}
            title="Refresh"
            aria-label="Refresh"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
          <button
            onClick={() => setShowRecord(!showRecord)}
            className="inline-flex items-center gap-2 px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all"
          >
            <Plus className="w-3.5 h-3.5" /> Record Update
          </button>
        </div>
      </div>

      {error && (
        <div className="rounded-2xl p-3 border border-red-500/30 bg-red-500/10 flex items-center gap-2">
          <AlertTriangle className="w-4 h-4 text-red-300 flex-shrink-0" />
          <span className="text-xs text-red-300 font-medium">{error}</span>
        </div>
      )}

      {/* ── Update form ── */}
      {showRecord && (
        <RecordPanel
          visitId={visitId}
          existingEvents={currentState}
          onClose={() => setShowRecord(false)}
          onRecorded={() => {
            setShowRecord(false);
            loadAll();
            // The backend may have just auto-bumped the visit's triage
            // category in response to one of the recorded signs. Tell
            // the parent surface to re-fetch authoritative visit state
            // so the new category appears everywhere on the page.
            onVisitMayHaveChanged?.();
          }}
          glassCard={glassCard}
          glassInner={glassInner}
          isDark={isDark}
          text={text}
        />
      )}

      {/* ── View toggle ── */}
      <div className="flex items-center gap-2">
        <button
          onClick={() => setView('current')}
          className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-colors border ${
            view === 'current'
              ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md border-transparent'
              : `${text.body} hover:bg-white/5 border-transparent`
          }`}
        >
          Current State
        </button>
        <button
          onClick={() => setView('timeline')}
          className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-colors border ${
            view === 'timeline'
              ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md border-transparent'
              : `${text.body} hover:bg-white/5 border-transparent`
          }`}
        >
          Timeline ({history.length})
        </button>
      </div>

      {loading && !hasAnyEvents ? (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <Loader2 className="w-6 h-6 mx-auto mb-2 text-cyan-500 animate-spin" />
          <p className={text.muted}>Loading clinical signs…</p>
        </div>
      ) : !hasAnyEvents ? (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <Stethoscope className="w-8 h-8 mx-auto mb-2 text-slate-400" />
          <p className={`text-sm font-semibold ${text.heading}`}>No clinical signs recorded yet.</p>
          <p className={`text-xs mt-1 ${text.muted}`}>
            New triage submissions auto-record a baseline. Use "Record Update" to flag new or resolved signs.
          </p>
        </div>
      ) : view === 'current' ? (
        <>
          {/* Diff card — sits above Current State so the doctor sees
              "what's changed since triage" before scrolling into the
              full sign list. Renders a quiet "no changes" line when
              nothing has moved. */}
          <ChangesSinceTriageCard
            history={history}
            glassCard={glassCard}
            glassInner={glassInner}
            isDark={isDark}
            text={text}
          />
          <CurrentStateView
            currentByCategory={currentByCategory}
            timelines={timelines}
            glassCard={glassCard}
            glassInner={glassInner}
            isDark={isDark}
            text={text}
          />
        </>
      ) : (
        <TimelineView
          history={history}
          glassCard={glassCard}
          isDark={isDark}
          text={text}
        />
      )}
    </div>
  );
}

// ═══════ Current State view ═══════

function CurrentStateView({
  currentByCategory, timelines, glassCard, glassInner, isDark, text,
}: {
  currentByCategory: Record<ClinicalSignCategory, ClinicalSignEventResponse[]>;
  timelines: Map<string, ClinicalSignEventResponse[]>;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}) {
  // SPECIAL category collapses by default when there's a lot of it —
  // it's typically the noisiest, least clinically actionable bucket
  // (long-tail signs that rarely drive a treatment decision). The
  // doctor opens it with one click when they need to scan it.
  // Categories with ≤4 entries always render expanded; otherwise
  // SPECIAL starts collapsed and the others stay open.
  const [collapsed, setCollapsed] = useState<Record<ClinicalSignCategory, boolean>>({
    EMERGENCY: false,
    PEDIATRIC_EMERGENCY: false,
    MSAT_VU: false,
    MSAT_URG: false,
    SPECIAL: (currentByCategory.SPECIAL?.length ?? 0) > 4,
  });

  return (
    <div className="space-y-3">
      {CATEGORY_ORDER.map((category) => {
        const events = currentByCategory[category];
        if (!events || events.length === 0) return null;
        const tone = CATEGORY_TONE[category];
        const isCollapsed = collapsed[category];
        return (
          <div key={category} className="rounded-2xl overflow-hidden" style={glassCard}>
            <button
              type="button"
              onClick={() => setCollapsed((prev) => ({ ...prev, [category]: !prev[category] }))}
              className={`w-full flex items-center gap-2 px-4 py-2.5 border-b ${tone.border} ${tone.bg} hover:brightness-110 transition`}
              aria-expanded={!isCollapsed}
            >
              <span className={`w-2 h-2 rounded-full ${tone.dot}`} />
              <h4 className={`text-xs font-extrabold uppercase tracking-wider ${tone.text}`}>
                {CATEGORY_LABEL[category]}
              </h4>
              <span className={`ml-auto text-[10px] font-bold ${text.muted} flex items-center gap-1.5`}>
                {events.length} sign{events.length === 1 ? '' : 's'}
                {isCollapsed
                  ? <ChevronDown className="w-3 h-3" />
                  : <ChevronUp className="w-3 h-3" />}
              </span>
            </button>
            {!isCollapsed && (
              <div className="p-3 space-y-2">
                {events.map((e) => {
                  const def = SIGN_BY_CODE[e.signCode];
                  const statusTone = STATUS_TONE[e.status];
                  const tl = timelines.get(e.signCode) ?? [];
                  return (
                    <div key={e.id} className="rounded-xl p-3 flex items-start gap-3" style={glassInner}>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className={`text-sm font-bold ${text.heading}`}>
                            {def?.label ?? e.signCode}
                          </span>
                          <span className={`text-[10px] font-extrabold uppercase tracking-wider px-2 py-0.5 rounded-md border ${statusTone.className}`}>
                            {statusTone.label}
                          </span>
                          {e.isBaseline && tl.length === 1 && (
                            <span className="inline-flex items-center text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg text-slate-600" style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                              Baseline only
                            </span>
                          )}
                          {tl.length >= 2 && (
                            <span className="inline-flex items-center text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg text-cyan-600" style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}>
                              {tl.length} events
                            </span>
                          )}
                          {e.numericValue != null && (
                            <span className="inline-flex items-center text-[10px] font-mono px-1.5 py-0.5 rounded-lg text-amber-600" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>
                              {def?.numericLabel?.replace(/\(.+\)/, '').trim() || 'value'}: {e.numericValue}
                            </span>
                          )}
                        </div>
                        {/* Per-sign sparkline — colored dots, oldest left,
                            latest right. Hover for timestamp / status /
                            recordedBy. Hidden when there's only one event
                            to keep the layout calm. */}
                        {tl.length >= 2 && (
                          <div className="mt-1.5">
                            <SignSparkline events={tl} isDark={isDark} />
                          </div>
                        )}
                        {e.notes && (
                          <p className={`text-[11px] mt-1 ${text.body}`}>{e.notes}</p>
                        )}
                        <div className={`text-[10px] mt-1 ${text.muted} flex items-center gap-2`}>
                          <Clock className="w-3 h-3" />
                          {e.recordedAt ? format(new Date(e.recordedAt), 'dd MMM yyyy HH:mm') : '—'}
                          {e.recordedByName && <span>· by {e.recordedByName}</span>}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ═══════ Timeline view ═══════

function TimelineView({
  history, glassCard, isDark, text,
}: {
  history: ClinicalSignEventResponse[];
  glassCard: React.CSSProperties;
  isDark: boolean;
  text: any;
}) {
  // Newest first for the feed.
  const feed = [...history].sort((a, b) =>
    new Date(b.recordedAt).getTime() - new Date(a.recordedAt).getTime()
  );

  // Group consecutive events by recorded_at — a doctor recording a batch
  // of changes lands as one cluster on the timeline.
  type Cluster = { recordedAt: string; recordedByName: string | null; events: ClinicalSignEventResponse[] };
  const clusters: Cluster[] = [];
  for (const e of feed) {
    const last = clusters[clusters.length - 1];
    if (last && last.recordedAt === e.recordedAt && last.recordedByName === e.recordedByName) {
      last.events.push(e);
    } else {
      clusters.push({ recordedAt: e.recordedAt, recordedByName: e.recordedByName, events: [e] });
    }
  }

  return (
    <div className="rounded-2xl p-4 space-y-3" style={glassCard}>
      {clusters.map((c, i) => {
        const isFirst = i === 0;
        const isBaselineCluster = c.events.every((e) => e.isBaseline);
        return (
          <div key={i} className="border-l-2 border-cyan-500/30 pl-4 pb-2 relative">
            <span className="absolute -left-1.5 top-1 w-3 h-3 rounded-full bg-cyan-500 ring-4 ring-cyan-500/15" />
            <div className="flex items-center gap-2 flex-wrap">
              <span className={`text-xs font-bold ${text.heading}`}>
                {format(new Date(c.recordedAt), 'dd MMM yyyy HH:mm')}
              </span>
              {c.recordedByName && (
                <span className={`text-[10px] ${text.muted}`}>by {c.recordedByName}</span>
              )}
              {isBaselineCluster && (
                <span className="inline-flex items-center text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg text-slate-600" style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                  Baseline at triage
                </span>
              )}
              {isFirst && !isBaselineCluster && (
                <span className="inline-flex items-center text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg text-cyan-600" style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}>
                  Latest
                </span>
              )}
            </div>
            <div className="mt-2 space-y-1">
              {c.events.map((e) => {
                const def = SIGN_BY_CODE[e.signCode];
                const statusTone = STATUS_TONE[e.status];
                return (
                  <div key={e.id} className="flex items-center gap-2 flex-wrap">
                    <span className={`text-xs font-semibold ${text.heading}`}>
                      {def?.label ?? e.signCode}
                    </span>
                    <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded border ${statusTone.className}`}>
                      {statusTone.label}
                    </span>
                    {e.numericValue != null && (
                      <span className="inline-flex items-center text-[10px] font-mono px-1.5 py-0.5 rounded-lg text-amber-600" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>
                        {e.numericValue}
                      </span>
                    )}
                    {e.notes && (
                      <span className={`text-[11px] ${text.body}`}>· {e.notes}</span>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ═══════ Record panel ═══════
//
// Collects one or more sign updates for the same observation timestamp.
// Each entry: pick a sign (with searchable list including all 54), set
// status, optionally set numeric value (only shown when the sign carries
// one), optionally add notes. The entire batch lands on the same recorded_at.
//

function RecordPanel({
  visitId, existingEvents, onClose, onRecorded, glassCard, glassInner, isDark, text,
}: {
  visitId: string;
  existingEvents: ClinicalSignEventResponse[];
  onClose: () => void;
  onRecorded: () => void;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}) {
  // Working entries — initialised empty, doctor adds rows.
  const [entries, setEntries] = useState<RecordClinicalSignEntry[]>([
    { signCode: '', status: 'PRESENT', numericValue: null, notes: '' },
  ]);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const updateEntry = (idx: number, patch: Partial<RecordClinicalSignEntry>) => {
    setEntries((prev) => prev.map((e, i) => (i === idx ? { ...e, ...patch } : e)));
  };
  const addEntry = () => setEntries((prev) => [...prev, { signCode: '', status: 'PRESENT', numericValue: null, notes: '' }]);
  const removeEntry = (idx: number) => setEntries((prev) => prev.filter((_, i) => i !== idx));

  // Quick-pick: signs already recorded on this visit (any status). Helps the
  // doctor update an existing sign rather than scroll the full 54-item list.
  const knownSignCodes = useMemo(() => {
    return Array.from(new Set(existingEvents.map((e) => e.signCode)));
  }, [existingEvents]);

  const canSubmit = entries.length > 0
    && entries.every((e) => !!e.signCode && !!e.status)
    && !submitting;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      await clinicalSignsApi.recordBatch({
        visitId,
        events: entries.map((e) => ({
          signCode: e.signCode,
          status: e.status,
          numericValue: e.numericValue ?? null,
          notes: e.notes?.trim() || null,
        })),
      });
      onRecorded();
    } catch (err) {
      setSubmitError((err as Error)?.message || 'Failed to record clinical sign update.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="rounded-2xl p-5 animate-fade-up space-y-3" style={glassCard}>
      <div className="flex items-center justify-between">
        <h4 className={`text-sm font-bold flex items-center gap-2 ${text.heading}`}>
          <Sparkles className="w-4 h-4 text-cyan-500" />
          Record Clinical Sign Update{entries.length > 1 ? 's' : ''}
        </h4>
        <button
          type="button"
          onClick={onClose}
          className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`}
          aria-label="Close"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <p className={`text-[11px] ${text.muted}`}>
        All entries below land on the same observation timestamp. Use{' '}
        <strong className={text.heading}>UNKNOWN</strong> when you cannot assess the sign — the system never
        equates that to absent.
      </p>

      {/* Entry rows */}
      <div className="space-y-3">
        {entries.map((entry, idx) => (
          <SignEntryRow
            key={idx}
            index={idx}
            entry={entry}
            canRemove={entries.length > 1}
            knownSignCodes={knownSignCodes}
            onChange={(patch) => updateEntry(idx, patch)}
            onRemove={() => removeEntry(idx)}
            glassInner={glassInner}
            isDark={isDark}
            text={text}
          />
        ))}
      </div>

      <button
        type="button"
        onClick={addEntry}
        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-cyan-500/10 text-cyan-300 hover:bg-cyan-500/20 transition-colors"
      >
        <Plus className="w-3 h-3" /> Add another sign
      </button>

      {submitError && (
        <p className="text-[11px] font-medium text-red-300">{submitError}</p>
      )}

      <div className="flex items-center gap-3 pt-2">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="inline-flex items-center gap-2 px-5 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
          Save {entries.length > 1 ? `${entries.length} updates` : 'update'}
        </button>
        <button type="button" onClick={onClose} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>
          Cancel
        </button>
      </div>
    </div>
  );
}

function SignEntryRow({
  index, entry, canRemove, knownSignCodes, onChange, onRemove, glassInner, isDark, text,
}: {
  index: number;
  entry: RecordClinicalSignEntry;
  canRemove: boolean;
  knownSignCodes: string[];
  onChange: (patch: Partial<RecordClinicalSignEntry>) => void;
  onRemove: () => void;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}) {
  const [search, setSearch] = useState('');
  const [showPicker, setShowPicker] = useState(!entry.signCode);
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  const def: ClinicalSignDefinition | undefined = entry.signCode ? SIGN_BY_CODE[entry.signCode] : undefined;

  // Filter the catalog for the picker — exact-match first, prefix-match
  // next, then substring. Already-recorded signs surface at the top so
  // updating an existing trajectory is one tap.
  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    const matches = (sd: ClinicalSignDefinition) =>
      !q
      || sd.label.toLowerCase().includes(q)
      || sd.code.toLowerCase().includes(q);
    const list = CLINICAL_SIGN_DEFINITIONS.filter(matches);
    // Sort: known-on-visit first, then form order.
    return list.sort((a, b) => {
      const aKnown = knownSignCodes.includes(a.code) ? 0 : 1;
      const bKnown = knownSignCodes.includes(b.code) ? 0 : 1;
      if (aKnown !== bKnown) return aKnown - bKnown;
      return CLINICAL_SIGN_DEFINITIONS.indexOf(a) - CLINICAL_SIGN_DEFINITIONS.indexOf(b);
    });
  }, [search, knownSignCodes]);

  return (
    <div className="rounded-xl p-3" style={{ ...glassInner, border: borderStyle }}>
      <div className="flex items-center justify-between mb-2">
        <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
          Entry {index + 1}
        </span>
        {canRemove && (
          <button
            type="button"
            onClick={onRemove}
            className={`p-1 rounded ${text.muted} hover:bg-white/5`}
            aria-label="Remove entry"
            title="Remove"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* Sign picker */}
      <div className="space-y-2">
        {def && !showPicker ? (
          <div className="flex items-start gap-2">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-sm font-bold ${text.heading}`}>{def.label}</span>
                <span className={`text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${CATEGORY_TONE[def.category].bg} ${CATEGORY_TONE[def.category].text}`}>
                  {CATEGORY_LABEL[def.category]}
                </span>
                {knownSignCodes.includes(def.code) && (
                  <span className="inline-flex items-center text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg text-cyan-600" style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}>
                    On record
                  </span>
                )}
              </div>
            </div>
            <button
              type="button"
              onClick={() => setShowPicker(true)}
              className={`p-1.5 rounded ${text.muted} hover:bg-white/5`}
              aria-label="Change sign"
              title="Change sign"
            >
              <RefreshCw className="w-3.5 h-3.5" />
            </button>
          </div>
        ) : (
          <div className="space-y-1.5">
            <div className="relative">
              <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search clinical signs (e.g. chest pain, convulsions)…"
                autoFocus={!def}
                style={glassInner}
                className={`w-full pl-9 pr-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`}
              />
            </div>
            <div className="rounded-lg max-h-40 overflow-y-auto" style={{ border: borderStyle }}>
              {filtered.length === 0 ? (
                <p className={`text-xs text-center py-3 ${text.muted}`}>No matches</p>
              ) : filtered.slice(0, 30).map((sd) => {
                const isOnRecord = knownSignCodes.includes(sd.code);
                return (
                  <button
                    key={sd.code}
                    type="button"
                    onClick={() => {
                      onChange({ signCode: sd.code, numericValue: null });
                      setShowPicker(false);
                      setSearch('');
                    }}
                    className="w-full text-left px-3 py-1.5 hover:bg-cyan-500/10 transition-colors border-b border-white/5 last:border-0"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-xs font-semibold ${text.heading}`}>{sd.label}</span>
                      <div className="flex items-center gap-1.5">
                        {isOnRecord && (
                          <span className="inline-flex items-center text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg text-cyan-600" style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}>On record</span>
                        )}
                        <span className={`text-[9px] font-bold uppercase ${CATEGORY_TONE[sd.category].text}`}>
                          {sd.category.replace(/_/g, ' ')}
                        </span>
                      </div>
                    </div>
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Status enum chips */}
        {def && (
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1 ${text.label}`}>Status</label>
            <div className="flex flex-wrap gap-1.5">
              {ALL_STATUSES.map((s) => {
                const tone = STATUS_TONE[s];
                const active = entry.status === s;
                return (
                  <button
                    key={s}
                    type="button"
                    onClick={() => onChange({ status: s })}
                    className={`px-2.5 py-1 text-[11px] font-bold rounded-lg transition-colors border ${
                      active ? tone.className : `${isDark ? 'text-slate-300 border-white/10' : 'text-slate-500 border-slate-300/40'} hover:bg-white/5`
                    }`}
                  >
                    {tone.label}
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {/* Numeric value — only for signs that carry one */}
        {def?.carriesNumeric && (
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1 ${text.label}`}>
              {def.numericLabel || 'Numeric value'}
            </label>
            <input
              type="number"
              step="0.1"
              value={entry.numericValue ?? ''}
              onChange={(e) => onChange({ numericValue: e.target.value === '' ? null : Number(e.target.value) })}
              placeholder="e.g. 4.2"
              style={glassInner}
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`}
            />
          </div>
        )}

        {/* Notes */}
        {def && (
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1 ${text.label}`}>
              Notes <span className={`font-normal normal-case ${text.muted}`}>(optional)</span>
            </label>
            <textarea
              value={entry.notes ?? ''}
              onChange={(e) => onChange({ notes: e.target.value })}
              placeholder="What changed, what was done, what to watch."
              rows={2}
              style={glassInner}
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 resize-none ${text.body} ${isDark ? 'placeholder-slate-500' : 'placeholder-slate-400'}`}
            />
          </div>
        )}
      </div>
    </div>
  );
}
