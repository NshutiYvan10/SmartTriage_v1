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

export function LeaveApprovalsPage() {
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
      <div className="p-8 text-sm text-gray-500">
        No hospital is associated with your account.
      </div>
    );
  }

  return (
    <div className="p-5 space-y-5">
      <header className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <ClipboardList className="w-5 h-5 text-gray-500" />
          <div>
            <div className="text-[11px] font-bold uppercase text-gray-400">Charge Nurse</div>
            <h1 className="text-xl font-bold text-gray-900 tracking-tight">Leave approvals</h1>
          </div>
          <span className="ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 border border-amber-200 text-xs font-bold">
            <Inbox className="w-3 h-3" />
            {queue.length} pending
          </span>
        </div>
        <button
          onClick={() => void refresh()}
          disabled={loading}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
          Refresh
        </button>
      </header>

      {err && (
        <div className="bg-rose-50 border border-rose-200 text-rose-700 px-3 py-2 rounded-lg text-xs flex items-center gap-2">
          <AlertTriangle className="w-3.5 h-3.5" />
          {err}
        </div>
      )}

      {queue.length === 0 && !loading && !err && (
        <div className="bg-white rounded-2xl border border-gray-200 p-10 text-center">
          <Inbox className="w-8 h-8 text-gray-300 mx-auto mb-2" />
          <div className="text-sm font-bold text-gray-700">No leave requests awaiting approval</div>
          <div className="text-xs text-gray-500 mt-1">
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
    <li className="bg-white border border-gray-200 rounded-2xl p-4">
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <UserMinus className="w-4 h-4 text-gray-500 shrink-0" />
          <div className="min-w-0">
            <div className="text-sm font-bold text-gray-900 truncate">{leave.userName}</div>
            <div className="text-[11px] text-gray-500">
              Submitted {formatRel(leave.requestedAt)}
              {leave.requestedByName && leave.requestedById !== leave.userId && (
                <> · on behalf, by <span className="font-semibold">{leave.requestedByName}</span></>
              )}
            </div>
          </div>
        </div>
        <span className={[
          'shrink-0 px-2 py-0.5 rounded-full text-[10px] font-bold border',
          severityClasses(leave.leaveType),
        ].join(' ')}>
          {prettyType(leave.leaveType)}
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-3">
        <DateBox label="Starts" iso={leave.startsOn} />
        <DateBox label="Ends" iso={leave.endsOn} />
        <DurationBox startsOn={leave.startsOn} endsOn={leave.endsOn} />
      </div>

      {leave.reason && (
        <div className="text-[12px] text-gray-700 mb-3">
          <span className="text-[10px] font-bold uppercase text-gray-400 mr-1.5">Reason</span>
          <span className="italic">“{leave.reason}”</span>
        </div>
      )}

      {err && (
        <div className="text-[11px] text-rose-700 bg-rose-50 border border-rose-200 px-2 py-1.5 rounded flex items-center gap-1.5 mb-3">
          <AlertTriangle className="w-3.5 h-3.5" />
          {err}
        </div>
      )}

      <div className="flex items-center justify-end gap-2 pt-3 border-t border-gray-100">
        {readOnly ? (
          <span className="text-[11px] italic text-gray-500">
            Decisions are made by the Charge Nurse.
          </span>
        ) : (
          <>
            <button
              onClick={reject}
              disabled={busy}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3 h-3 animate-spin" /> : <XCircle className="w-3 h-3" />}
              Reject
            </button>
            <button
              onClick={approve}
              disabled={busy}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold text-white bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50"
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
  return (
    <div className="border border-gray-200 rounded-lg p-2.5 bg-gray-50">
      <div className="text-[10px] font-bold uppercase text-gray-400">{label}</div>
      <div className="text-sm font-semibold text-gray-900 mt-0.5">
        {new Date(iso + 'T00:00:00').toLocaleDateString('en-US', {
          weekday: 'short', month: 'short', day: 'numeric', year: 'numeric',
        })}
      </div>
    </div>
  );
}

function DurationBox({ startsOn, endsOn }: { startsOn: string; endsOn: string }) {
  const days = inclusiveDayCount(startsOn, endsOn);
  return (
    <div className="border border-gray-200 rounded-lg p-2.5 bg-gray-50 flex items-center gap-2">
      <CalendarRange className="w-4 h-4 text-gray-400" />
      <div>
        <div className="text-[10px] font-bold uppercase text-gray-400">Duration</div>
        <div className="text-sm font-semibold text-gray-900 mt-0.5">
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

function severityClasses(t: LeaveType): string {
  switch (t) {
    case 'SICK':
    case 'BEREAVEMENT':
      return 'bg-rose-50 text-rose-700 border-rose-200';
    case 'MATERNITY':
    case 'COMPASSIONATE':
      return 'bg-violet-50 text-violet-700 border-violet-200';
    case 'STUDY':
      return 'bg-blue-50 text-blue-700 border-blue-200';
    case 'ANNUAL':
      return 'bg-emerald-50 text-emerald-700 border-emerald-200';
    case 'OTHER':
    default:
      return 'bg-gray-50 text-gray-700 border-gray-200';
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
