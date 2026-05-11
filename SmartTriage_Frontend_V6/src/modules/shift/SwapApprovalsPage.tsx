/* ════════════════════════════════════════════════════════════════════
   /swap-approvals — Charge Nurse approval queue (Phase E).

   What it shows:
     • Every swap at this hospital that has cleared the partner-accept
       gate and is waiting for Charge Nurse approval (status ==
       PENDING_CHARGE_APPROVAL). These are the swaps where both
       parties have agreed; the CN is the final clinical-safety check
       before the user-exchange runs.

   What the CN sees per row:
     • Both sides (date / period / zone / function / user / role)
     • Optional reason from the requester
     • Optional partner note from the accept step
     • Approve / Reject buttons

   Approve path:
     • POST /shifts/swaps/{id}/charge-approve
     • Backend runs the atomic user exchange on the two
       ShiftAssignment rows inside a single @Transactional. The shift-
       lead badge intentionally does NOT travel with the swap.

   Reject path:
     • POST /shifts/swaps/{id}/charge-reject  with a required note.
     • Status moves to REJECTED (terminal). Both parties keep their
       original assignments.

   Auth:
     • The page is granted to NURSE / HOSPITAL_ADMIN / SUPER_ADMIN at
       the role-page level. Mutation endpoints are guarded server-side
       by @shiftAssignmentAuthz.canApproveSwap which checks for
       Charge Nurse designation OR active delegation. Non-CN nurses
       opening this page will see the queue but get a 403 on action.

   No live updates yet — manual "Refresh" button. Adding WebSocket
   push for swap state changes is a future iteration.
   ════════════════════════════════════════════════════════════════════ */

import { useEffect, useState } from 'react';
import {
  ArrowRightLeft, RefreshCw, CheckCircle2, XCircle, Loader2, AlertTriangle,
  Sun, Moon, ShieldCheck, Inbox,
} from 'lucide-react';
import { swapApi } from '@/api';
import type { ShiftSwapResponse, SwapAssignmentSnapshot } from '@/api/types';
import { useAuthStore } from '@/store/authStore';

export function SwapApprovalsPage() {
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  // HOSPITAL_ADMIN sees the queue read-only; only CHARGE_NURSE may decide.
  const isReadOnly = user?.role === 'HOSPITAL_ADMIN'
    && user?.designation !== 'CHARGE_NURSE';

  const [queue, setQueue] = useState<ShiftSwapResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const refresh = async () => {
    if (!hospitalId) return;
    setLoading(true);
    setErr(null);
    try {
      const rows = await swapApi.chargeQueue(hospitalId);
      // Backend returns the full PENDING_CHARGE_APPROVAL set; sort
      // oldest-first so the staff that's been waiting longest are at
      // the top — that's the queue discipline we want.
      rows.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      setQueue(rows);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load swap queue');
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
          <ShieldCheck className="w-5 h-5 text-gray-500" />
          <div>
            <div className="text-[11px] font-bold uppercase text-gray-400">Charge Nurse</div>
            <h1 className="text-xl font-bold text-gray-900 tracking-tight">Swap approvals</h1>
          </div>
          <span className="ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-200 text-xs font-bold">
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
          <div className="text-sm font-bold text-gray-700">No swaps awaiting approval</div>
          <div className="text-xs text-gray-500 mt-1">
            New requests appear here once both staff members have agreed.
          </div>
        </div>
      )}

      <ul className="space-y-3">
        {queue.map((s) => (
          <SwapApprovalRow key={s.id} swap={s} onChange={refresh} readOnly={isReadOnly} />
        ))}
      </ul>
    </div>
  );
}

/* ─── Single approval row ─── */

function SwapApprovalRow({
  swap, onChange, readOnly,
}: {
  swap: ShiftSwapResponse;
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

  const approve = () => wrap(() => swapApi.chargeApprove(swap.id));
  const reject = () => {
    const note = window.prompt('Reason for rejecting (visible to both staff):');
    if (note === null) return;                  // user cancelled prompt
    if (!note.trim()) {
      setErr('A rejection reason is required.');
      return;
    }
    return wrap(() => swapApi.chargeReject(swap.id, { note: note.trim() }));
  };

  return (
    <li className="bg-white border border-gray-200 rounded-2xl p-4">
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className="text-[11px] text-gray-500">
          Submitted {formatRel(swap.createdAt)}
          {swap.partnerRespondedAt && (
            <>
              <span className="mx-1.5 text-gray-300">·</span>
              Partner accepted {formatRel(swap.partnerRespondedAt)}
            </>
          )}
        </div>
        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold border bg-blue-50 text-blue-700 border-blue-200">
          Awaits CN
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_1fr] gap-3 items-stretch">
        <SidePanel side="Requester" snap={swap.requesterSide} />
        <div className="flex items-center justify-center text-gray-400">
          <ArrowRightLeft className="w-5 h-5" />
        </div>
        <SidePanel side="Partner" snap={swap.partnerSide} />
      </div>

      {(swap.requestReason || swap.partnerResponseNote) && (
        <div className="mt-3 space-y-1 text-[12px]">
          {swap.requestReason && (
            <div className="text-gray-700">
              <span className="text-[10px] font-bold uppercase text-gray-400 mr-1.5">
                Requester reason
              </span>
              <span className="italic">“{swap.requestReason}”</span>
            </div>
          )}
          {swap.partnerResponseNote && (
            <div className="text-gray-700">
              <span className="text-[10px] font-bold uppercase text-gray-400 mr-1.5">
                Partner note
              </span>
              <span className="italic">“{swap.partnerResponseNote}”</span>
            </div>
          )}
        </div>
      )}

      {err && (
        <div className="mt-3 text-[11px] text-rose-700 bg-rose-50 border border-rose-200 px-2 py-1.5 rounded flex items-center gap-1.5">
          <AlertTriangle className="w-3.5 h-3.5" />
          {err}
        </div>
      )}

      <div className="mt-4 flex items-center justify-end gap-2 pt-3 border-t border-gray-100">
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
              Approve swap
            </button>
          </>
        )}
      </div>
    </li>
  );
}

function SidePanel({
  side, snap,
}: {
  side: 'Requester' | 'Partner';
  snap: SwapAssignmentSnapshot;
}) {
  return (
    <div className="border border-gray-200 rounded-lg p-3 bg-gray-50">
      <div className="text-[10px] font-bold uppercase text-gray-400 mb-1">{side}</div>
      <div className="text-sm font-semibold text-gray-900">{snap.userName}</div>
      <div className="text-[11px] text-gray-500 mt-1.5 flex items-center gap-1.5 flex-wrap">
        {snap.shiftPeriod === 'DAY'
          ? <Sun className="w-3 h-3" />
          : <Moon className="w-3 h-3" />}
        <span className="font-mono">{snap.shiftDate}</span>
        <span className="text-gray-300">·</span>
        <span className="font-bold">{snap.shiftPeriod}</span>
        <span className="text-gray-300">·</span>
        <span className="font-bold">{snap.zone}</span>
        <span className="text-gray-300">·</span>
        <span>{snap.shiftFunction.replace(/_/g, ' ')}</span>
      </div>
    </div>
  );
}

/* Compact relative-time formatter — "3h ago" / "2 days ago" / actual
   date when older than a week. Avoids pulling in a date lib for one
   string. */
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
