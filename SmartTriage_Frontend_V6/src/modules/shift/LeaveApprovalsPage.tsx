/* ════════════════════════════════════════════════════════════════════
   /leave-approvals — Charge Nurse / admin approval queue for staff
   leave requests (Phase F).

   What it shows:
     • Every leave row at this hospital with status REQUESTED,
       sorted oldest-first so the staff who've waited longest
       float to the top.
     • Per row: requester name, type (Annual/Sick/etc.), date range,
       free-text reason, requested-at timestamp, days-of-leave count.

   Decisions:
     • Approve  → POST /shifts/leaves/{id}/approve   (note optional)
     • Reject   → POST /shifts/leaves/{id}/reject    (note required)

   Authorization:
     • Page-level grant: HOSPITAL_ADMIN / SUPER_ADMIN by role,
       Charge Nurses by designation (RoleGuard.allowDesignations).
     • Mutation endpoints are guarded server-side; non-CN nurses
       opening this page (shouldn't happen via sidebar but is
       possible by URL-typing) will see the queue but get 403 on
       action.

   No live updates — manual "Refresh" button. Adding WebSocket push
   is a future iteration.
   ════════════════════════════════════════════════════════════════════ */

import { useEffect, useState } from 'react';
import {
  ClipboardList, RefreshCw, CheckCircle2, XCircle, Loader2, AlertTriangle,
  Inbox, UserMinus, CalendarRange,
} from 'lucide-react';
import { leaveApi } from '@/api';
import type { LeaveType, StaffLeaveResponse } from '@/api/types';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';

export function LeaveApprovalsPage() {
  const { glassCard, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  // HOSPITAL_ADMIN sees the queue read-only; only CHARGE_NURSE may decide.
  const isReadOnly = user?.role === 'HOSPITAL_ADMIN'
    && user?.designation !== 'CHARGE_NURSE';

  const [queue, setQueue] = useState<StaffLeaveResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const refresh = async () => {
    if (!hospitalId) return;
    setLoading(true);
    setErr(null);
    try {
      const rows = await leaveApi.listPending(hospitalId);
      // Backend already sorts oldest-first, but we guarantee it
      // here so client-side reorderings don't sneak in.
      rows.sort((a, b) => a.requestedAt.localeCompare(b.requestedAt));
      setQueue(rows);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load leave queue');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hospitalId]);

  if (!hospitalId) {
    return (
      <div className={`p-8 text-sm ${text.muted}`}>
        No hospital is associated with your account.
      </div>
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center shrink-0">
                <ClipboardList className="w-5 h-5 text-cyan-300" />
              </div>
              <div>
                <div className="text-[11px] font-bold uppercase text-white/50">Charge Nurse</div>
                <h1 className="text-lg font-bold text-white tracking-tight">Leave approvals</h1>
              </div>
              <span
                className="ml-2 inline-flex items-center gap-1 px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-amber-600"
                style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
              >
                <Inbox className="w-3 h-3" />
                {queue.length} pending
              </span>
            </div>
            <button
              onClick={() => void refresh()}
              disabled={loading}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-semibold text-white disabled:opacity-50"
            >
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              Refresh
            </button>
          </div>
        </div>

        {err && (
          <div className="bg-rose-500/20 border border-rose-500/30 text-rose-300 px-3 py-2 rounded-xl text-xs flex items-center gap-2">
            <AlertTriangle className="w-3.5 h-3.5" />
            {err}
          </div>
        )}

        {queue.length === 0 && !loading && !err && (
          <div className="rounded-3xl p-10 text-center" style={glassCard}>
            <Inbox className={`w-8 h-8 ${text.muted} mx-auto mb-2`} />
            <div className={`text-sm font-bold ${text.heading}`}>No leave requests awaiting approval</div>
            <div className={`text-xs ${text.muted} mt-1`}>
              New requests appear here as soon as staff submit them.
            </div>
          </div>
        )}

        <ul className="space-y-3">
          {queue.map((l) => (
            <LeaveApprovalRow key={l.id} leave={l} onChange={refresh} readOnly={isReadOnly} />
          ))}
        </ul>
      </div>
    </div>
  );
}

/* ─── Single approval row ─── */

function LeaveApprovalRow({
  leave, onChange, readOnly,
}: {
  leave: StaffLeaveResponse;
  onChange: () => Promise<void>;
  readOnly: boolean;
}) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const wrap = async (fn: () => Promise<unknown>) => {
    setBusy(true); setErr(null);
    try { await fn(); await onChange(); }
    catch (e: unknown) { setErr(e instanceof Error ? e.message : 'Action failed'); }
    finally { setBusy(false); }
  };

  const approve = () => {
    // Optional note for approval — we don't force one. Most CN
    // approvals are silent rubber-stamps.
    const note = window.prompt('Optional note for approval (visible to requester, leave blank for none):');
    // null means user hit cancel; treat as abort.
    if (note === null) return;
    return wrap(() => leaveApi.approve(leave.id, note.trim() ? { note: note.trim() } : undefined));
  };

  const reject = () => {
    const note = window.prompt('Reason for rejecting (required, visible to requester):');
    if (note === null) return;
    if (!note.trim()) {
      setErr('A rejection reason is required.');
      return;
    }
    return wrap(() => leaveApi.reject(leave.id, { note: note.trim() }));
  };

  return (
    <li className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <UserMinus className={`w-4 h-4 ${text.muted} shrink-0`} />
          <div className="min-w-0">
            <div className={`text-sm font-bold ${text.heading} truncate`}>{leave.userName}</div>
            <div className={`text-[11px] ${text.muted}`}>
              Submitted {formatRel(leave.requestedAt)}
              {leave.requestedByName && leave.requestedById !== leave.userId && (
                <> · on behalf, by <span className="font-semibold">{leave.requestedByName}</span></>
              )}
            </div>
          </div>
        </div>
        <span
          className={`shrink-0 inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${severityClasses(leave.leaveType).className}`}
          style={severityClasses(leave.leaveType).style}
        >
          {prettyType(leave.leaveType)}
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-3">
        <DateBox label="Starts" iso={leave.startsOn} />
        <DateBox label="Ends" iso={leave.endsOn} />
        <DurationBox startsOn={leave.startsOn} endsOn={leave.endsOn} />
      </div>

      {leave.reason && (
        <div className={`text-[12px] ${text.body} mb-3`}>
          <span className={`text-[10px] font-bold uppercase ${text.muted} mr-1.5`}>Reason</span>
          <span className="italic">“{leave.reason}”</span>
        </div>
      )}

      {err && (
        <div className="text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1.5 rounded-lg flex items-center gap-1.5 mb-3">
          <AlertTriangle className="w-3.5 h-3.5" />
          {err}
        </div>
      )}

      <div className="flex items-center justify-end gap-2 pt-3" style={{ borderTop: borderStyle }}>
        {readOnly ? (
          <span className={`text-[11px] italic ${text.muted}`}>
            Decisions are made by the Charge Nurse.
          </span>
        ) : (
          <>
            <button
              onClick={reject}
              disabled={busy}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold text-rose-300 bg-rose-500/20 hover:bg-rose-500/30 border border-rose-500/30 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3 h-3 animate-spin" /> : <XCircle className="w-3 h-3" />}
              Reject
            </button>
            <button
              onClick={approve}
              disabled={busy}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3 h-3 animate-spin" /> : <CheckCircle2 className="w-3 h-3" />}
              Approve leave
            </button>
          </>
        )}
      </div>
    </li>
  );
}

/* ─── Cells ─── */

function DateBox({ label, iso }: { label: string; iso: string }) {
  const { glassInner, text } = useTheme();
  return (
    <div className="rounded-xl p-2.5" style={glassInner}>
      <div className={`text-[10px] font-bold uppercase ${text.muted}`}>{label}</div>
      <div className={`text-sm font-semibold ${text.heading} mt-0.5`}>
        {new Date(iso + 'T00:00:00').toLocaleDateString('en-US', {
          weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
        })}
      </div>
    </div>
  );
}

function DurationBox({ startsOn, endsOn }: { startsOn: string; endsOn: string }) {
  const { glassInner, text } = useTheme();
  const days = inclusiveDayCount(startsOn, endsOn);
  return (
    <div className="rounded-xl p-2.5 flex items-center gap-2" style={glassInner}>
      <CalendarRange className={`w-4 h-4 ${text.muted}`} />
      <div>
        <div className={`text-[10px] font-bold uppercase ${text.muted}`}>Duration</div>
        <div className={`text-sm font-semibold ${text.heading} mt-0.5`}>
          {days} {days === 1 ? 'day' : 'days'}
        </div>
      </div>
    </div>
  );
}

/* ─── Helpers ─── */

/** Inclusive day count between two YYYY-MM-DD strings. */
function inclusiveDayCount(startIso: string, endIso: string): number {
  const start = new Date(startIso + 'T00:00:00').getTime();
  const end = new Date(endIso + 'T00:00:00').getTime();
  if (end < start) return 0;
  return Math.round((end - start) / 86_400_000) + 1;
}

function severityClasses(t: LeaveType): { className: string; style: { background: string; border: string } } {
  switch (t) {
    case 'SICK':
    case 'BEREAVEMENT':
      return { className: 'text-rose-600', style: { background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' } };
    case 'MATERNITY':
    case 'COMPASSIONATE':
      return { className: 'text-violet-600', style: { background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' } };
    case 'STUDY':
      return { className: 'text-cyan-600', style: { background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' } };
    case 'ANNUAL':
      return { className: 'text-emerald-600', style: { background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' } };
    case 'OTHER':
    default:
      return { className: 'text-slate-600', style: { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' } };
  }
}

function prettyType(t: LeaveType): string {
  switch (t) {
    case 'ANNUAL': return 'Annual';
    case 'SICK': return 'Sick';
    case 'MATERNITY': return 'Maternity';
    case 'BEREAVEMENT': return 'Bereavement';
    case 'COMPASSIONATE': return 'Compassionate';
    case 'STUDY': return 'Study';
    case 'OTHER': return 'Other';
  }
}

function formatRel(iso: string): string {
  const then = new Date(iso).getTime();
  const now = Date.now();
  const diffMs = now - then;
  if (diffMs < 0) return 'just now';
  const min = Math.floor(diffMs / 60_000);
  if (min < 1) return 'just now';
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day}d ago`;
  return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}
