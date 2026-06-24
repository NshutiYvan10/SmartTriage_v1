/* ════════════════════════════════════════════════════════════════════
   Propose-shift-swap modal (Phase E).

   Launched from any row representing one of the user's own active
   ShiftAssignments — typically the upcoming-shifts list on
   /my-schedule, but reusable elsewhere (e.g. day detail on the
   calendar). The modal turns the user's own assignment into the
   "requester" side and lets them pick exactly one partner assignment
   to swap with.

   Partner discovery model:
     • Pick a date → fetch every active assignment for that date at
       the same hospital via shiftApi.getByDate.
     • Hide:
         — the requester's own assignment (you can't swap with
           yourself)
         — any inactive row (already deactivated / replaced)
         — assignments belonging to a different role/designation
           combination that the backend would later reject as a
           "not like-for-like" swap. We keep this loose on the client:
           the backend has the final authority. Swaps across
           role/designation aren't blocked here so a NURSE can offer
           to swap with another NURSE in a different zone.

   Submit path:
     • POST /shifts/swaps with { requesterAssignmentId, partnerAssignmentId, requestReason? }
     • State after server response: REQUESTED → PENDING_PARTNER_ACCEPT
       (the backend already advances it; we just call onSubmitted).

   No optimistic UI. After submit we hand control back to the parent
   so it can re-fetch.
   ════════════════════════════════════════════════════════════════════ */

import { useEffect, useMemo, useState } from 'react';
import {
  ArrowRightLeft, X, Loader2, Sun, Moon, ShieldAlert, Check,
} from 'lucide-react';
import { shiftApi, swapApi } from '@/api';
import { useTheme } from '@/hooks/useTheme';
import type {
  ShiftAssignmentResponse,
} from '@/api/types';

interface ProposeSwapModalProps {
  /** The user's own assignment that becomes the "requester" side. */
  myAssignment: ShiftAssignmentResponse;
  onClose: () => void;
  onSubmitted: () => void | Promise<void>;
}

function fmtIso(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function todayIso() { return fmtIso(new Date()); }

export function ProposeSwapModal({
  myAssignment, onClose, onSubmitted,
}: ProposeSwapModalProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark
    ? '1px solid rgba(2,132,199,0.12)'
    : '1px solid rgba(203,213,225,0.3)';

  /* The date we're searching partners on — defaults to my own shift's
     date so the most common case (offer my Monday for someone else's
     Monday) is one click away. */
  const [searchDate, setSearchDate] = useState<string>(myAssignment.shiftDate);
  const [candidates, setCandidates] = useState<ShiftAssignmentResponse[]>([]);
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [fetchErr, setFetchErr] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitErr, setSubmitErr] = useState<string | null>(null);

  /* Fetch candidate roster for the chosen date. */
  useEffect(() => {
    if (!searchDate) return;
    let cancelled = false;
    setLoadingCandidates(true);
    setFetchErr(null);
    shiftApi
      .getByDate(myAssignment.hospitalId, searchDate)
      .then((rows) => {
        if (cancelled) return;
        setCandidates(rows);
        // Reset selection if the previously selected partner isn't in
        // the new result set.
        if (selectedId && !rows.some(r => r.id === selectedId)) {
          setSelectedId(null);
        }
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setFetchErr(e instanceof Error ? e.message : 'Failed to load roster');
      })
      .finally(() => {
        if (!cancelled) setLoadingCandidates(false);
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchDate, myAssignment.hospitalId]);

  const filteredCandidates = useMemo(() => {
    return candidates
      .filter((c) => c.id !== myAssignment.id)   // not yourself
      .filter((c) => c.active)                    // only active rows
      .sort((a, b) => {
        // Group by period (DAY first), then zone, then user name.
        if (a.shiftPeriod !== b.shiftPeriod) {
          return a.shiftPeriod === 'DAY' ? -1 : 1;
        }
        if (a.zone !== b.zone) return a.zone.localeCompare(b.zone);
        return a.userName.localeCompare(b.userName);
      });
  }, [candidates, myAssignment.id]);

  const selected = filteredCandidates.find(c => c.id === selectedId) ?? null;

  const submit = async () => {
    if (!selected) return;
    setSubmitting(true);
    setSubmitErr(null);
    try {
      await swapApi.propose({
        requesterAssignmentId: myAssignment.id,
        partnerAssignmentId: selected.id,
        requestReason: reason.trim() || undefined,
      });
      await onSubmitted();
    } catch (e: unknown) {
      setSubmitErr(e instanceof Error ? e.message : 'Submission failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/40 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <div
        style={glassCard}
        className="rounded-2xl overflow-hidden shadow-2xl animate-scale-in w-full max-w-2xl max-h-[90vh] flex flex-col"
      >
        {/* ── Header ── */}
        <div className="flex items-center justify-between bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
          <div className="flex items-center gap-2">
            <div className="w-9 h-9 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <ArrowRightLeft className="w-4 h-4 text-cyan-300" />
            </div>
            <h2 className="text-base font-bold text-white">Propose shift swap</h2>
          </div>
          <button
            onClick={onClose}
            disabled={submitting}
            className="w-9 h-9 rounded-xl flex items-center justify-center text-white/70 hover:bg-white/10 disabled:opacity-50"
            aria-label="Close"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* ── Body (scrollable) ── */}
        <div className="px-5 py-4 space-y-4 overflow-y-auto">
          {/* Your shift summary */}
          <section>
            <div className={`text-[11px] font-bold uppercase ${text.muted} mb-1.5`}>Your shift</div>
            <ShiftLine assignment={myAssignment} highlight glassInner={glassInner} text={text} />
          </section>

          {/* Date picker */}
          <section>
            <label className="block">
              <div className={`text-[11px] font-bold uppercase ${text.label} mb-1`}>
                Find partner shifts on date
              </div>
              <input
                type="date"
                style={glassInner}
                className={`w-full text-sm rounded-lg px-2 py-1.5 ${text.body} focus:outline-none focus:ring-2 focus:ring-cyan-500/20`}
                value={searchDate}
                onChange={(e) => setSearchDate(e.target.value)}
                min={todayIso()}
              />
            </label>
            <div className={`text-[11px] ${text.muted} mt-1`}>
              Defaults to your shift’s date. Pick another date to swap across days.
            </div>
          </section>

          {/* Candidate list */}
          <section>
            <div className="flex items-center justify-between mb-1.5">
              <div className={`text-[11px] font-bold uppercase ${text.label}`}>
                Pick a partner ({filteredCandidates.length})
              </div>
              {loadingCandidates && (
                <Loader2 className={`w-3.5 h-3.5 animate-spin ${text.muted}`} />
              )}
            </div>

            {fetchErr && (
              <div className="text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1.5 rounded mb-2">
                {fetchErr}
              </div>
            )}

            {!loadingCandidates && filteredCandidates.length === 0 && !fetchErr && (
              <div className={`text-xs ${text.muted} italic`}>
                No active assignments on this date — try another day.
              </div>
            )}

            <ul className="space-y-1.5 max-h-72 overflow-y-auto">
              {filteredCandidates.map((c) => {
                const isSelected = selectedId === c.id;
                return (
                  <li key={c.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedId(c.id)}
                      style={isSelected ? undefined : glassInner}
                      className={[
                        'w-full text-left px-3 py-2 rounded-xl border transition-colors',
                        isSelected
                          ? 'bg-cyan-500/20 border-cyan-500/30 ring-1 ring-cyan-500/30'
                          : 'border-transparent hover:bg-white/5',
                      ].join(' ')}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex-1 min-w-0">
                          <div className={`text-sm font-semibold ${text.heading} truncate`}>
                            {c.userName}
                          </div>
                          <div className={`text-[11px] ${text.body} mt-0.5 flex items-center gap-1.5`}>
                            {c.shiftPeriod === 'DAY'
                              ? <Sun className="w-3 h-3" />
                              : <Moon className="w-3 h-3" />}
                            <span>{c.shiftPeriod}</span>
                            <span className={text.muted}>·</span>
                            <span className={`font-bold ${text.label}`}>{c.zone}</span>
                            <span className={text.muted}>·</span>
                            <span>{c.shiftFunction.replace(/_/g, ' ')}</span>
                            {c.isShiftLead && (
                              <span className="ml-1 text-[9px] font-bold text-violet-300 bg-violet-500/20 border border-violet-500/30 px-1 py-0.5 rounded">
                                LEAD
                              </span>
                            )}
                          </div>
                          {c.userDesignationLabel && (
                            <div className={`text-[11px] ${text.muted} mt-0.5`}>
                              {c.userDesignationLabel}
                            </div>
                          )}
                        </div>
                        {isSelected && (
                          <div className="shrink-0 mt-1 inline-flex items-center justify-center w-5 h-5 rounded-full bg-cyan-600 text-white">
                            <Check className="w-3 h-3" />
                          </div>
                        )}
                      </div>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>

          {/* Selected pair preview */}
          {selected && (
            <section style={glassInner} className="rounded-xl p-3">
              <div className={`text-[11px] font-bold uppercase ${text.label} mb-2`}>
                Swap preview
              </div>
              <div className={`text-[12px] ${text.body} space-y-1`}>
                <div className="flex items-center gap-2">
                  <span className={`${text.muted} w-12 text-[10px] font-bold uppercase`}>You</span>
                  <ShiftLineInline assignment={myAssignment} text={text} />
                </div>
                <div className="flex items-center gap-2">
                  <span className={`${text.muted} w-12 text-[10px] font-bold uppercase`}>Partner</span>
                  <ShiftLineInline assignment={selected} text={text} />
                </div>
              </div>
              <div className={`text-[11px] ${text.body} mt-2 italic`}>
                On approval, you take {selected.userName}’s slot and they take yours.
                Shift-lead status, if any, stays with the original holder.
              </div>
            </section>
          )}

          {/* Reason */}
          <section>
            <label className="block">
              <div className={`text-[11px] font-bold uppercase ${text.label} mb-1`}>
                Reason (optional — visible to partner and Charge Nurse)
              </div>
              <textarea
                style={glassInner}
                className={`w-full text-sm rounded-lg px-2 py-1.5 min-h-[60px] ${text.body} focus:outline-none focus:ring-2 focus:ring-cyan-500/20`}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="e.g. doctor’s appointment in the morning"
              />
            </label>
          </section>

          {submitErr && (
            <div className="text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1.5 rounded flex items-center gap-1.5">
              <ShieldAlert className="w-3.5 h-3.5" />
              {submitErr}
            </div>
          )}
        </div>

        {/* ── Footer ── */}
        <div
          style={{ borderTop: borderStyle }}
          className="px-5 py-3 flex items-center justify-between"
        >
          <div className={`text-[11px] ${text.muted}`}>
            Goes to your partner first, then the Charge Nurse for final approval.
          </div>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              disabled={submitting}
              className={`px-3 py-1.5 rounded-xl text-xs font-bold ${text.body} bg-white/10 hover:bg-white/15 disabled:opacity-50`}
            >
              Cancel
            </button>
            <button
              onClick={submit}
              disabled={submitting || !selected}
              className="px-3 py-1.5 rounded-xl bg-cyan-600 text-white text-xs font-bold hover:bg-cyan-700 inline-flex items-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {submitting && <Loader2 className="w-3 h-3 animate-spin" />}
              Send swap request
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/* ─── Compact one-line summary used inside the preview ─── */

function ShiftLineInline({
  assignment, text,
}: {
  assignment: ShiftAssignmentResponse;
  text: ReturnType<typeof useTheme>['text'];
}) {
  return (
    <span className={`text-[12px] ${text.body}`}>
      <span className="font-mono">{assignment.shiftDate}</span>
      <span className={`mx-1.5 ${text.muted}`}>·</span>
      <span className="font-bold">{assignment.shiftPeriod}</span>
      <span className={`mx-1.5 ${text.muted}`}>·</span>
      <span className="font-bold">{assignment.zone}</span>
      <span className={`mx-1.5 ${text.muted}`}>·</span>
      <span>{assignment.shiftFunction.replace(/_/g, ' ')}</span>
    </span>
  );
}

/* ─── Card-style summary for the requester at top ─── */

function ShiftLine({
  assignment, highlight = false, glassInner, text,
}: {
  assignment: ShiftAssignmentResponse;
  highlight?: boolean;
  glassInner: ReturnType<typeof useTheme>['glassInner'];
  text: ReturnType<typeof useTheme>['text'];
}) {
  return (
    <div
      style={highlight ? undefined : glassInner}
      className={[
        'rounded-xl px-3 py-2',
        highlight ? 'bg-cyan-500/20 border border-cyan-500/30' : '',
      ].join(' ')}
    >
      <div className={`text-sm font-semibold ${text.heading}`}>
        {new Date(assignment.shiftDate + 'T00:00:00').toLocaleDateString('en-US', {
          weekday: 'long', month: 'short', day: 'numeric',
        })}
      </div>
      <div className={`text-[11px] ${text.body} mt-0.5 flex items-center gap-1.5`}>
        {assignment.shiftPeriod === 'DAY'
          ? <Sun className="w-3 h-3" />
          : <Moon className="w-3 h-3" />}
        <span>{assignment.shiftPeriod === 'DAY' ? '07:00 – 19:00' : '19:00 – 07:00'}</span>
        <span className={text.muted}>·</span>
        <span className={`font-bold ${text.label}`}>{assignment.zone}</span>
        <span className={text.muted}>·</span>
        <span>{assignment.shiftFunction.replace(/_/g, ' ')}</span>
        {assignment.isShiftLead && (
          <span className="ml-1 text-[9px] font-bold text-violet-300 bg-violet-500/20 border border-violet-500/30 px-1 py-0.5 rounded">
            LEAD
          </span>
        )}
      </div>
    </div>
  );
}
