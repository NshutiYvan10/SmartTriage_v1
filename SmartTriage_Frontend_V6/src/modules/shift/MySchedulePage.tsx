/* ════════════════════════════════════════════════════════════════════
   /my-schedule — self-service view for any authenticated staff member.

   Renders the four things every nurse / doctor / paramedic needs to
   see about themselves on a normal shift week:

     1. Next shift  ── the soonest assignment that is on or after today
     2. Upcoming shifts ── full forward-looking roster for me
     3. My leave  ── full leave history + a "Request leave" form
     4. My swaps  ── open swaps I'm involved in + history

   Self-service actions wired here:
     • Request leave  (POST  /shifts/leaves)
     • Cancel my pending leave  (POST /shifts/leaves/:id/cancel)
     • Accept / reject a swap I'm the named partner on
     • Cancel an open swap I created or am part of
     • Propose a NEW swap from any of my upcoming shifts (Phase E)

   Charge Nurse approval queue lives at /swap-approvals (Phase E).

   The page is intentionally read-mostly — anything destructive ends up
   as a server-validated POST that the user can confirm in plain
   language. No optimistic UI; we re-fetch after every mutation to keep
   state honest.
   ════════════════════════════════════════════════════════════════════ */

import { useEffect, useMemo, useState } from 'react';
import {
  CalendarClock, ClipboardList, AlertTriangle, ArrowRightLeft,
  CheckCircle2, XCircle, Plus, Loader2, UserMinus, Sun, Moon,
  ShieldAlert, ChevronDown, MapPin,
} from 'lucide-react';
import { leaveApi, swapApi, shiftApi } from '@/api';
import type {
  LeaveStatus, LeaveType,
  ShiftAssignmentResponse,
  ShiftSwapResponse,
  StaffLeaveResponse,
  SwapStatus,
} from '@/api/types';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';
import { ProposeSwapModal } from './ProposeSwapModal';
import { dialog } from '@/components/dialog';
import { ModalPortal } from '@/components/ModalPortal';

const LEAVE_TYPES: LeaveType[] = [
  'ANNUAL', 'SICK', 'MATERNITY', 'BEREAVEMENT', 'COMPASSIONATE', 'STUDY', 'OTHER',
];

function fmtIso(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function todayIso() { return fmtIso(new Date()); }

/* ─── Presentation-only date formatting helpers ─── */

function addDaysIso(baseIso: string, days: number): string {
  const d = new Date(baseIso + 'T00:00:00');
  d.setDate(d.getDate() + days);
  return fmtIso(d);
}

/** "Today" / "Tomorrow" chip text for a date, or null when neither. */
function relDayLabel(iso: string): 'Today' | 'Tomorrow' | null {
  const today = todayIso();
  if (iso === today) return 'Today';
  if (iso === addDaysIso(today, 1)) return 'Tomorrow';
  return null;
}

/** "Mon 13 Jul" */
function fmtDayHeader(iso: string): string {
  const d = new Date(iso + 'T00:00:00');
  const wk = d.toLocaleDateString('en-US', { weekday: 'short' });
  const mo = d.toLocaleDateString('en-US', { month: 'short' });
  return `${wk} ${d.getDate()} ${mo}`;
}

/** "13–17 Jul" · "28 Jul – 2 Aug" · "30 Dec 2026 – 2 Jan 2027" */
function fmtRange(startIso: string, endIso: string): string {
  const s = new Date(startIso + 'T00:00:00');
  const e = new Date(endIso + 'T00:00:00');
  const mo = (d: Date) => d.toLocaleDateString('en-US', { month: 'short' });
  if (startIso === endIso) return `${s.getDate()} ${mo(s)} ${s.getFullYear()}`;
  if (s.getFullYear() !== e.getFullYear()) {
    return `${s.getDate()} ${mo(s)} ${s.getFullYear()} – ${e.getDate()} ${mo(e)} ${e.getFullYear()}`;
  }
  if (s.getMonth() !== e.getMonth()) {
    return `${s.getDate()} ${mo(s)} – ${e.getDate()} ${mo(e)} ${e.getFullYear()}`;
  }
  return `${s.getDate()}–${e.getDate()} ${mo(s)} ${s.getFullYear()}`;
}

/* ═══════════════════════ Page ═══════════════════════ */

export function MySchedulePage() {
  const user = useAuthStore((s) => s.user);
  const { glassCard, isDark, text } = useTheme();

  const [shifts, setShifts] = useState<ShiftAssignmentResponse[]>([]);
  const [leaves, setLeaves] = useState<StaffLeaveResponse[]>([]);
  const [openSwaps, setOpenSwaps] = useState<ShiftSwapResponse[]>([]);
  const [historySwaps, setHistorySwaps] = useState<ShiftSwapResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const [showLeaveForm, setShowLeaveForm] = useState(false);
  const [proposeFrom, setProposeFrom] = useState<ShiftAssignmentResponse | null>(null);

  const refresh = async () => {
    if (!user?.id) return;
    setLoading(true);
    setErr(null);
    try {
      const [shiftRows, myLeaves, swapsOpen, swapsHistory] = await Promise.all([
        shiftApi.getUserHistory(user.id),
        leaveApi.listMine(),
        swapApi.myOpen(),
        swapApi.myHistory(),
      ]);
      setShifts(shiftRows);
      setLeaves(myLeaves);
      setOpenSwaps(swapsOpen);
      setHistorySwaps(swapsHistory);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load schedule');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [user?.id]);

  const upcomingShifts = useMemo(() => {
    const today = todayIso();
    return shifts
      .filter((s) => s.shiftDate >= today)
      .sort((a, b) =>
        a.shiftDate === b.shiftDate
          ? a.shiftPeriod.localeCompare(b.shiftPeriod)
          : a.shiftDate.localeCompare(b.shiftDate),
      );
  }, [shifts]);

  const nextShift = upcomingShifts[0];

  if (!user) {
    return <div className={`p-8 text-sm ${text.muted}`}>Not signed in.</div>;
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <CalendarClock className="w-5 h-5 text-cyan-300" />
              </div>
              <div>
                <div className="text-white/50 text-xs font-bold uppercase">My schedule</div>
                <div className="text-lg font-bold text-white tracking-tight">{user.fullName}</div>
              </div>
            </div>
            <button
              onClick={() => setShowLeaveForm(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-cyan-600 text-white text-xs font-bold hover:bg-cyan-700 transition-colors"
            >
              <Plus className="w-3.5 h-3.5" />
              Request leave
            </button>
          </div>
        </div>

      {err && (
        <div className="bg-rose-500/20 border border-rose-500/30 text-rose-300 px-3 py-2 rounded-xl text-xs flex items-center gap-2">
          <AlertTriangle className="w-3.5 h-3.5" />
          {err}
        </div>
      )}

      <NextShiftBanner shift={nextShift} loading={loading} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <UpcomingShiftsCard
          shifts={upcomingShifts}
          loading={loading}
          onProposeSwap={(s) => setProposeFrom(s)}
        />
        <SwapsCard
          userId={user.id}
          open={openSwaps}
          history={historySwaps}
          loading={loading}
          onChange={refresh}
        />
      </div>

      <MyLeaveCard
        leaves={leaves}
        userId={user.id}
        loading={loading}
        onChange={refresh}
      />

      {showLeaveForm && (
        <RequestLeaveModal
          onClose={() => setShowLeaveForm(false)}
          onSubmitted={async () => {
            setShowLeaveForm(false);
            await refresh();
          }}
        />
      )}

      {proposeFrom && (
        <ProposeSwapModal
          myAssignment={proposeFrom}
          onClose={() => setProposeFrom(null)}
          onSubmitted={async () => {
            setProposeFrom(null);
            await refresh();
          }}
        />
      )}
      </div>
    </div>
  );
}

/* ═══════════════════════ Next-shift banner ═══════════════════════ */

function NextShiftBanner({ shift, loading }: { shift?: ShiftAssignmentResponse; loading: boolean }) {
  const { glassCard, text } = useTheme();
  if (loading && !shift) {
    return (
      <div className={`rounded-2xl p-5 text-sm flex items-center gap-2 ${text.muted}`} style={glassCard}>
        <Loader2 className="w-4 h-4 animate-spin" />
        Loading…
      </div>
    );
  }
  if (!shift) {
    return (
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className={`text-[11px] font-bold uppercase mb-1 ${text.muted}`}>Next shift</div>
        <div className={`text-sm ${text.body}`}>No upcoming shifts on the books.</div>
      </div>
    );
  }
  const isToday = shift.shiftDate === todayIso();
  return (
    <div className="rounded-2xl overflow-hidden" style={glassCard}>
      <div className={`px-6 py-5 ${isToday ? 'bg-gradient-to-r from-blue-700 to-blue-600' : 'bg-gradient-to-r from-slate-800 to-slate-700'}`}>
        <div className="flex items-center justify-between">
          <div>
            <div className="text-xs text-white/60 font-medium mb-0.5">
              {isToday ? 'Today’s shift' : 'Next shift'}
            </div>
            <div className="text-lg font-bold text-white tracking-tight">
              {new Date(shift.shiftDate + 'T00:00:00').toLocaleDateString('en-US', {
                weekday: 'long', month: 'short', day: 'numeric',
              })}
              <span className="mx-2 text-white/40">·</span>
              {shift.shiftPeriod === 'DAY' ? '07:00 – 19:00' : '19:00 – 07:00'}
            </div>
          </div>
          <div className="text-right">
            <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-lg bg-white/15 text-white text-xs font-bold">
              {shift.shiftPeriod === 'DAY' ? <Sun className="w-3.5 h-3.5" /> : <Moon className="w-3.5 h-3.5" />}
              {shift.shiftPeriod}
            </span>
          </div>
        </div>
        <div className="mt-3 flex items-center gap-3 text-sm text-white/70">
          <span className="font-bold text-white">{shift.zone}</span>
          <span className="text-white/40">·</span>
          <span>{shift.shiftFunction.replace(/_/g, ' ')}</span>
          {shift.isShiftLead && (
            <span className="px-1.5 py-0.5 rounded bg-violet-500/30 text-violet-100 text-[10px] font-bold uppercase">
              Shift Lead
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

/* ═══════════════════════ Upcoming shifts ═══════════════════════ */

function UpcomingShiftsCard({
  shifts, loading, onProposeSwap,
}: {
  shifts: ShiftAssignmentResponse[];
  loading: boolean;
  onProposeSwap: (s: ShiftAssignmentResponse) => void;
}) {
  const { glassCard, glassInner, isDark, text } = useTheme();

  /* Presentation-only grouping of the same capped list the card always showed */
  const dayGroups = useMemo(() => {
    const groups: { date: string; rows: ShiftAssignmentResponse[] }[] = [];
    for (const s of shifts.slice(0, 14)) {
      const last = groups[groups.length - 1];
      if (last && last.date === s.shiftDate) last.rows.push(s);
      else groups.push({ date: s.shiftDate, rows: [s] });
    }
    return groups;
  }, [shifts]);

  /* Next 7 calendar days, flagged with any scheduled periods */
  const weekStrip = useMemo(() => {
    const today = todayIso();
    return Array.from({ length: 7 }, (_, i) => {
      const iso = addDaysIso(today, i);
      const dayShifts = shifts.filter((s) => s.shiftDate === iso);
      return {
        iso,
        isToday: i === 0,
        hasDay: dayShifts.some((s) => s.shiftPeriod === 'DAY'),
        hasNight: dayShifts.some((s) => s.shiftPeriod !== 'DAY'),
      };
    });
  }, [shifts]);

  return (
    <section className="rounded-2xl p-5" style={glassCard}>
      <div className="flex items-center gap-2 mb-3">
        <CalendarClock className={`w-4 h-4 ${text.muted}`} />
        <h2 className={`text-sm font-bold ${text.heading}`}>Upcoming shifts</h2>
        <span className={`text-[10px] font-semibold ${text.muted}`}>({shifts.length})</span>
      </div>

      {/* Next-7-days strip */}
      <div className="grid grid-cols-7 gap-1.5 mb-4">
        {weekStrip.map((d) => {
          const scheduled = d.hasDay || d.hasNight;
          const date = new Date(d.iso + 'T00:00:00');
          return (
            <div
              key={d.iso}
              title={`${fmtDayHeader(d.iso)}${scheduled ? ' — on shift' : ''}`}
              className={`rounded-lg px-1 py-1.5 text-center border ${
                scheduled
                  ? 'bg-cyan-500/15 border-cyan-500/30'
                  : isDark ? 'bg-white/[0.03] border-white/5' : 'bg-slate-50/60 border-slate-200/60'
              } ${d.isToday ? 'ring-1 ring-cyan-400/60' : ''}`}
            >
              <div className={`text-[9px] font-bold uppercase tracking-wider ${
                d.isToday ? (isDark ? 'text-cyan-300' : 'text-cyan-600') : text.muted
              }`}>
                {date.toLocaleDateString('en-US', { weekday: 'short' }).slice(0, 2)}
              </div>
              <div className={`text-xs font-bold leading-tight ${scheduled ? text.heading : text.muted}`}>
                {date.getDate()}
              </div>
              <div className="h-3 flex items-center justify-center gap-0.5">
                {d.hasDay && <Sun className="w-2.5 h-2.5 text-amber-500" />}
                {d.hasNight && <Moon className="w-2.5 h-2.5 text-indigo-400" />}
              </div>
            </div>
          );
        })}
      </div>

      {loading && shifts.length === 0 && (
        <div className={`text-xs ${text.muted}`}>Loading…</div>
      )}
      {!loading && shifts.length === 0 && (
        <div className={`text-xs italic ${text.muted}`}>Nothing scheduled.</div>
      )}

      <div className="space-y-3.5">
        {dayGroups.map((g) => {
          const rel = relDayLabel(g.date);
          return (
            <div key={g.date}>
              <div className="flex items-center gap-2 mb-1.5">
                <span className={`text-[11px] font-bold uppercase tracking-wider ${text.label}`}>
                  {fmtDayHeader(g.date)}
                </span>
                {rel && (
                  <span
                    className="inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-bold uppercase tracking-wider text-cyan-600"
                    style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}
                  >
                    {rel}
                  </span>
                )}
              </div>
              <ul className="space-y-1.5">
                {g.rows.map((s) => (
                  <li key={s.id} className="rounded-xl px-3 py-2 flex items-center gap-3" style={glassInner}>
                    <span
                      className={`w-7 h-7 rounded-lg flex items-center justify-center shrink-0 ${
                        s.shiftPeriod === 'DAY' ? 'bg-amber-500/15 text-amber-500' : 'bg-indigo-500/15 text-indigo-400'
                      }`}
                    >
                      {s.shiftPeriod === 'DAY' ? <Sun className="w-3.5 h-3.5" /> : <Moon className="w-3.5 h-3.5" />}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className={`text-xs font-semibold ${text.heading}`}>
                        {s.shiftPeriod === 'DAY' ? '07:00 – 19:00' : '19:00 – 07:00'}
                        <span className={`ml-1.5 font-medium ${text.muted}`}>{s.shiftPeriod}</span>
                      </div>
                      <div className={`mt-1 flex items-center gap-1.5 text-xs ${text.body}`}>
                        <span
                          className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-semibold ${text.label}`}
                          style={{ background: 'rgba(14,165,233,0.08)', border: '1px solid rgba(14,165,233,0.2)' }}
                        >
                          <MapPin className="w-2.5 h-2.5" />
                          {s.zone}
                        </span>
                        <span className="truncate">{s.shiftFunction.replace(/_/g, ' ')}</span>
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5 shrink-0">
                      {s.isShiftLead && (
                        <span className="text-[9px] font-bold uppercase tracking-wider text-violet-400 bg-violet-500/15 border border-violet-500/30 px-1.5 py-0.5 rounded">
                          Shift Lead
                        </span>
                      )}
                      {s.active && (
                        <button
                          onClick={() => onProposeSwap(s)}
                          className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold text-cyan-400 bg-cyan-500/20 hover:bg-cyan-500/30 border border-cyan-500/30 transition-colors"
                          title="Propose a swap from this shift"
                        >
                          <ArrowRightLeft className="w-3 h-3" />
                          Swap
                        </button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </div>
    </section>
  );
}

/* ═══════════════════════ Swaps ═══════════════════════ */

function SwapsCard({
  userId, open, history, loading, onChange,
}: {
  userId: string;
  open: ShiftSwapResponse[];
  history: ShiftSwapResponse[];
  loading: boolean;
  onChange: () => Promise<void>;
}) {
  const { glassCard, glassInner, text } = useTheme();

  /* Presentation-only ordering: swaps waiting on MY decision surface first */
  const needsMe = open.filter((s) => s.partnerSide.userId === userId && s.status === 'PENDING_PARTNER_ACCEPT');
  const rest = open.filter((s) => !(s.partnerSide.userId === userId && s.status === 'PENDING_PARTNER_ACCEPT'));
  const pastSwaps = history.filter((h) => !open.some((o) => o.id === h.id));

  return (
    <section className="rounded-2xl p-5" style={glassCard}>
      <div className="flex items-center gap-2 mb-3">
        <ArrowRightLeft className={`w-4 h-4 ${text.muted}`} />
        <h2 className={`text-sm font-bold ${text.heading}`}>My swap requests</h2>
        <span className={`text-[10px] font-semibold ${text.muted}`}>({open.length} open)</span>
        {needsMe.length > 0 && (
          <span
            className="ml-auto inline-flex items-center gap-1 px-2 py-0.5 rounded-lg text-[9px] font-bold uppercase tracking-wider text-amber-600"
            style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.25)' }}
          >
            <AlertTriangle className="w-3 h-3" />
            {needsMe.length} need{needsMe.length === 1 ? 's' : ''} your decision
          </span>
        )}
      </div>

      {loading && open.length === 0 && (
        <div className={`text-xs ${text.muted}`}>Loading…</div>
      )}
      {!loading && open.length === 0 && (
        <div className={`text-xs italic mb-3 ${text.muted}`}>No open swap requests.</div>
      )}

      <ul className="space-y-3">
        {[...needsMe, ...rest].map((s) => (
          <SwapRow key={s.id} swap={s} userId={userId} onChange={onChange} />
        ))}
      </ul>

      {pastSwaps.length > 0 && (
        <details className="group mt-4">
          <summary className={`list-none [&::-webkit-details-marker]:hidden flex items-center gap-1.5 cursor-pointer select-none text-[11px] font-bold uppercase tracking-wider ${text.label}`}>
            <ChevronDown className={`w-3.5 h-3.5 transition-transform group-open:rotate-180 ${text.muted}`} />
            History
            <span className={`text-[10px] font-semibold normal-case tracking-normal ${text.muted}`}>({pastSwaps.length})</span>
          </summary>
          <ul className="mt-2 space-y-1.5">
            {pastSwaps.slice(0, 10).map((s) => (
              <li
                key={s.id}
                className={`text-xs flex items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 ${text.body}`}
                style={glassInner}
              >
                <span className="truncate">{describeSwap(s, userId)}</span>
                <SwapStatusPill status={s.status} />
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

function SwapRow({
  swap, userId, onChange,
}: {
  swap: ShiftSwapResponse;
  userId: string;
  onChange: () => Promise<void>;
}) {
  const { glassInner, text } = useTheme();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const isPartner = swap.partnerSide.userId === userId;
  const isRequester = swap.requesterSide.userId === userId;
  const needsMyDecision = isPartner && swap.status === 'PENDING_PARTNER_ACCEPT';

  const wrap = async (fn: () => Promise<unknown>) => {
    setBusy(true); setErr(null);
    try { await fn(); await onChange(); }
    catch (e: unknown) { setErr(e instanceof Error ? e.message : 'Action failed'); }
    finally { setBusy(false); }
  };

  return (
    <li
      className={`rounded-xl p-3 text-sm ${needsMyDecision ? 'ring-1 ring-amber-400/50' : ''}`}
      style={glassInner}
    >
      {needsMyDecision && (
        <div className="mb-2 flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-wider text-amber-500">
          <AlertTriangle className="w-3 h-3" />
          Awaiting your decision
        </div>
      )}
      <div className="flex items-start justify-between gap-3">
        <div className={`text-xs flex-1 min-w-0 ${text.body}`}>
          <div className={`text-sm font-semibold ${text.heading}`}>
            {isRequester ? 'You ↔ ' + swap.partnerSide.userName : swap.requesterSide.userName + ' ↔ You'}
          </div>
          <div className={`text-xs mt-1 ${text.muted}`}>
            {fmtDayHeader(swap.requesterSide.shiftDate)} {swap.requesterSide.shiftPeriod} · {swap.requesterSide.zone}
            <span className="mx-1.5">↔</span>
            {fmtDayHeader(swap.partnerSide.shiftDate)} {swap.partnerSide.shiftPeriod} · {swap.partnerSide.zone}
          </div>
          {swap.requestReason && (
            <div className={`text-xs mt-1 italic ${text.muted}`}>“{swap.requestReason}”</div>
          )}
        </div>
        <SwapStatusPill status={swap.status} />
      </div>

      {err && (
        <div className="mt-2 text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1 rounded">
          {err}
        </div>
      )}

      {/* Action buttons by role + state */}
      <div className={`flex flex-wrap gap-1.5 ${needsMyDecision ? 'mt-3' : 'mt-2'}`}>
        {isPartner && swap.status === 'PENDING_PARTNER_ACCEPT' && (
          <>
            <ActionButton
              variant="primary"
              size="md"
              busy={busy}
              icon={<CheckCircle2 className="w-3.5 h-3.5" />}
              onClick={() => wrap(() => swapApi.partnerAccept(swap.id))}
            >
              Accept
            </ActionButton>
            <ActionButton
              variant="danger"
              size="md"
              busy={busy}
              icon={<XCircle className="w-3.5 h-3.5" />}
              onClick={async () => {
                const note = await dialog.prompt({
                  title: 'Decline swap', tone: 'danger', confirmLabel: 'Decline',
                  message: 'Reason for declining (visible to requester):', placeholder: 'Optional',
                });
                if (note === null) return;
                return wrap(() => swapApi.partnerReject(swap.id, { note }));
              }}
            >
              Decline
            </ActionButton>
          </>
        )}
        {(isRequester || isPartner) && !isTerminal(swap.status) && (
          <ActionButton
            variant="ghost"
            busy={busy}
            icon={<XCircle className="w-3 h-3" />}
            onClick={async () => {
              if (!(await dialog.confirm({ title: 'Cancel swap', tone: 'danger', confirmLabel: 'Cancel request', message: 'Cancel this swap request?' }))) return;
              return wrap(() => swapApi.cancel(swap.id));
            }}
          >
            Cancel
          </ActionButton>
        )}
      </div>
    </li>
  );
}

function describeSwap(s: ShiftSwapResponse, userId: string): string {
  const other = s.requesterSide.userId === userId
    ? s.partnerSide.userName
    : s.requesterSide.userName;
  return `${fmtDayHeader(s.requesterSide.shiftDate)} ${s.requesterSide.shiftPeriod} · with ${other}`;
}

function isTerminal(s: SwapStatus) {
  return s === 'APPROVED' || s === 'REJECTED' || s === 'CANCELLED';
}

function SwapStatusPill({ status }: { status: SwapStatus }) {
  const cfg: Record<SwapStatus, { label: string; cls: string; bg: string; border: string }> = {
    REQUESTED: { label: 'Requested', cls: 'text-slate-600', bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' },
    PENDING_PARTNER_ACCEPT: { label: 'Awaits partner', cls: 'text-amber-600', bg: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' },
    PENDING_CHARGE_APPROVAL: { label: 'Awaits CN', cls: 'text-cyan-600', bg: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' },
    APPROVED: { label: 'Approved', cls: 'text-emerald-600', bg: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' },
    REJECTED: { label: 'Rejected', cls: 'text-rose-600', bg: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' },
    CANCELLED: { label: 'Cancelled', cls: 'text-slate-600', bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' },
  };
  const c = cfg[status];
  return (
    <span
      className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${c.cls}`}
      style={{ background: c.bg, border: c.border }}
    >
      {c.label}
    </span>
  );
}

/* ═══════════════════════ Leave card + modal ═══════════════════════ */

function MyLeaveCard({
  leaves, userId, loading, onChange,
}: {
  leaves: StaffLeaveResponse[];
  userId: string;
  loading: boolean;
  onChange: () => Promise<void>;
}) {
  const { glassCard, text } = useTheme();
  return (
    <section className="rounded-2xl p-5" style={glassCard}>
      <div className="flex items-center gap-2 mb-3">
        <ClipboardList className={`w-4 h-4 ${text.muted}`} />
        <h2 className={`text-sm font-bold ${text.heading}`}>My leave</h2>
        <span className={`text-[10px] font-semibold ${text.muted}`}>({leaves.length})</span>
      </div>

      {loading && leaves.length === 0 && (
        <div className={`text-xs ${text.muted}`}>Loading…</div>
      )}
      {!loading && leaves.length === 0 && (
        <div className={`text-xs italic ${text.muted}`}>No leave history.</div>
      )}

      <ul>
        {leaves.map((l) => (
          <LeaveRow key={l.id} leave={l} userId={userId} onChange={onChange} />
        ))}
      </ul>
    </section>
  );
}

function LeaveRow({
  leave, userId, onChange,
}: {
  leave: StaffLeaveResponse;
  userId: string;
  onChange: () => Promise<void>;
}) {
  const { isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const isOwner = leave.userId === userId;
  const cancellable = isOwner
    && (leave.leaveStatus === 'REQUESTED' || leave.leaveStatus === 'APPROVED');

  return (
    <li className="py-3 text-sm" style={{ borderTop: borderStyle }}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className={`text-sm font-semibold ${text.heading}`}>{prettyType(leave.leaveType)}</span>
            <LeaveStatusPill status={leave.leaveStatus} />
          </div>
          <div className={`text-xs mt-1 flex items-center gap-1.5 ${text.body}`}>
            <CalendarClock className={`w-3 h-3 ${text.muted}`} />
            {fmtRange(leave.startsOn, leave.endsOn)}
          </div>
          {leave.reason && (
            <div className={`text-xs mt-1 italic ${text.muted}`}>“{leave.reason}”</div>
          )}
          {leave.rejectionReason && (
            <div className="text-xs text-rose-300 mt-1">
              Rejected: {leave.rejectionReason}
            </div>
          )}
        </div>
        {cancellable && (
          <ActionButton
            variant="ghost"
            busy={busy}
            icon={<XCircle className="w-3 h-3" />}
            onClick={async () => {
              if (!(await dialog.confirm({ title: 'Cancel leave', tone: 'danger', confirmLabel: 'Cancel leave', message: 'Cancel this leave?' }))) return;
              setBusy(true); setErr(null);
              try { await leaveApi.cancel(leave.id); await onChange(); }
              catch (e: unknown) { setErr(e instanceof Error ? e.message : 'Action failed'); }
              finally { setBusy(false); }
            }}
          >
            Cancel
          </ActionButton>
        )}
      </div>
      {err && (
        <div className="mt-1 text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1 rounded">
          {err}
        </div>
      )}
    </li>
  );
}

function LeaveStatusPill({ status }: { status: LeaveStatus }) {
  const cfg: Record<LeaveStatus, { label: string; cls: string; bg: string; border: string }> = {
    REQUESTED: { label: 'Pending', cls: 'text-amber-600', bg: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' },
    APPROVED: { label: 'Approved', cls: 'text-emerald-600', bg: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' },
    REJECTED: { label: 'Rejected', cls: 'text-rose-600', bg: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' },
    CANCELLED: { label: 'Cancelled', cls: 'text-slate-600', bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' },
  };
  const c = cfg[status];
  return (
    <span
      className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${c.cls}`}
      style={{ background: c.bg, border: c.border }}
    >
      {c.label}
    </span>
  );
}

function prettyType(t: LeaveType): string {
  switch (t) {
    case 'ANNUAL': return 'Annual leave';
    case 'SICK': return 'Sick leave';
    case 'MATERNITY': return 'Maternity leave';
    case 'BEREAVEMENT': return 'Bereavement leave';
    case 'COMPASSIONATE': return 'Compassionate leave';
    case 'STUDY': return 'Study leave';
    case 'OTHER': return 'Other leave';
  }
}

/* ─── Request leave modal ─── */

function RequestLeaveModal({
  onClose, onSubmitted,
}: {
  onClose: () => void;
  onSubmitted: () => Promise<void>;
}) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const [leaveType, setLeaveType] = useState<LeaveType>('ANNUAL');
  const [startsOn, setStartsOn] = useState(todayIso());
  const [endsOn, setEndsOn]     = useState(todayIso());
  const [reason, setReason]     = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    setSubmitting(true); setErr(null);
    try {
      if (endsOn < startsOn) {
        throw new Error('End date cannot be before start date');
      }
      await leaveApi.create({
        leaveType,
        startsOn,
        endsOn,
        reason: reason.trim() || undefined,
      });
      await onSubmitted();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Submission failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalPortal>
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }}>
      <div className="rounded-2xl overflow-hidden shadow-2xl animate-scale-in w-full max-w-md p-5 space-y-4" style={glassCard}>
        <div className="flex items-center gap-2">
          <UserMinus className={`w-4 h-4 ${text.muted}`} />
          <h2 className={`text-base font-bold ${text.heading}`}>Request leave</h2>
        </div>

        <FormRow label="Leave type">
          <select
            className={`w-full text-sm rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            style={glassInner}
            value={leaveType}
            onChange={(e) => setLeaveType(e.target.value as LeaveType)}
          >
            {LEAVE_TYPES.map((t) => (
              <option key={t} value={t}>{prettyType(t)}</option>
            ))}
          </select>
        </FormRow>

        <div className="grid grid-cols-2 gap-3">
          <FormRow label="Start">
            <input
              type="date"
              className={`w-full text-sm rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              style={glassInner}
              value={startsOn}
              onChange={(e) => setStartsOn(e.target.value)}
            />
          </FormRow>
          <FormRow label="End">
            <input
              type="date"
              className={`w-full text-sm rounded-lg px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              style={glassInner}
              value={endsOn}
              onChange={(e) => setEndsOn(e.target.value)}
            />
          </FormRow>
        </div>

        <FormRow label="Reason (optional, but recommended for SICK / COMPASSIONATE)">
          <textarea
            className={`w-full text-sm rounded-lg px-2 py-1.5 min-h-[60px] focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            style={glassInner}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. flu symptoms; daughter's wedding"
          />
        </FormRow>

        {err && (
          <div className="text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1.5 rounded flex items-center gap-1.5">
            <ShieldAlert className="w-3.5 h-3.5" />
            {err}
          </div>
        )}

        <div className="flex justify-end gap-2 pt-2" style={{ borderTop: borderStyle }}>
          <button
            onClick={onClose}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold bg-white/10 hover:bg-white/15 ${text.body}`}
            disabled={submitting}
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={submitting}
            className="px-3 py-1.5 rounded-xl bg-cyan-600 text-white text-xs font-bold hover:bg-cyan-700 inline-flex items-center gap-1.5 disabled:opacity-50"
          >
            {submitting && <Loader2 className="w-3 h-3 animate-spin" />}
            Submit request
          </button>
        </div>
      </div>
    </div>
    </ModalPortal>
  );
}

function FormRow({ label, children }: { label: string; children: React.ReactNode }) {
  const { text } = useTheme();
  return (
    <label className="block">
      <div className={`text-[11px] font-bold uppercase mb-1 ${text.label}`}>{label}</div>
      {children}
    </label>
  );
}

/* ─── Action button helper ─── */

function ActionButton({
  variant, busy, icon, children, onClick, size = 'sm',
}: {
  variant: 'primary' | 'danger' | 'ghost';
  busy: boolean;
  icon: React.ReactNode;
  children: React.ReactNode;
  onClick: () => void | Promise<void>;
  size?: 'sm' | 'md';
}) {
  const cls = variant === 'primary'
    ? 'bg-cyan-600 text-white hover:bg-cyan-700'
    : variant === 'danger'
    ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30 hover:bg-rose-500/30'
    : 'bg-white/10 text-slate-300 border border-white/10 hover:bg-white/15';
  const pad = size === 'md'
    ? 'gap-1.5 px-3 py-1.5 text-xs'
    : 'gap-1 px-2 py-1 text-[11px]';
  return (
    <button
      disabled={busy}
      onClick={onClick}
      className={`inline-flex items-center rounded-lg font-bold transition-colors disabled:opacity-50 ${pad} ${cls}`}
    >
      {busy ? <Loader2 className="w-3 h-3 animate-spin" /> : icon}
      {children}
    </button>
  );
}
