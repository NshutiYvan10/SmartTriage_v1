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
import { useTheme } from '@/hooks/useTheme';

export function SwapApprovalsPage() {
  const { glassCard, text } = useTheme();
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
      <div className={`p-8 text-sm ${text.muted}`}>
        No hospital is associated with your account.
      </div>
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <ShieldCheck className="w-5 h-5 text-cyan-300" />
              </div>
              <div>
                <div className="text-white/50 text-xs font-bold uppercase">Charge Nurse</div>
                <h1 className="text-lg font-bold text-white tracking-tight">Swap approvals</h1>
              </div>
              <span className="ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-cyan-500/20 text-cyan-300 border border-cyan-500/30 text-xs font-bold">
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
            <Inbox className={`w-8 h-8 mx-auto mb-2 ${text.muted}`} />
            <div className={`text-sm font-bold ${text.heading}`}>No swaps awaiting approval</div>
            <div className={`text-xs mt-1 ${text.muted}`}>
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
  const { glassCard, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
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
    <li className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className={`text-[11px] ${text.muted}`}>
          Submitted {formatRel(swap.createdAt)}
          {swap.partnerRespondedAt && (
            <>
              <span className={`mx-1.5 ${text.muted}`}>·</span>
              Partner accepted {formatRel(swap.partnerRespondedAt)}
            </>
          )}
        </div>
        <span className="px-2 py-0.5 rounded-full text-[10px] font-bold border bg-amber-500/20 text-amber-300 border-amber-500/30">
          Awaits CN
        </span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_1fr] gap-3 items-stretch">
        <SidePanel side="Requester" snap={swap.requesterSide} />
        <div className={`flex items-center justify-center ${text.muted}`}>
          <ArrowRightLeft className="w-5 h-5" />
        </div>
        <SidePanel side="Partner" snap={swap.partnerSide} />
      </div>

      {(swap.requestReason || swap.partnerResponseNote) && (
        <div className="mt-3 space-y-1 text-[12px]">
          {swap.requestReason && (
            <div className={text.body}>
              <span className={`text-[10px] font-bold uppercase mr-1.5 ${text.muted}`}>
                Requester reason
              </span>
              <span className="italic">“{swap.requestReason}”</span>
            </div>
          )}
          {swap.partnerResponseNote && (
            <div className={text.body}>
              <span className={`text-[10px] font-bold uppercase mr-1.5 ${text.muted}`}>
                Partner note
              </span>
              <span className="italic">“{swap.partnerResponseNote}”</span>
            </div>
          )}
        </div>
      )}

      {err && (
        <div className="mt-3 text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1.5 rounded-xl flex items-center gap-1.5">
          <AlertTriangle className="w-3.5 h-3.5" />
          {err}
        </div>
      )}

      <div className="mt-4 flex items-center justify-end gap-2 pt-3" style={{ borderTop: borderStyle }}>
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
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold text-white bg-emerald-600 hover:bg-emerald-700 disabled:opacity-50"
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
  const { glassInner, text } = useTheme();
  return (
    <div className="rounded-xl p-3" style={glassInner}>
      <div className={`text-[10px] font-bold uppercase mb-1 ${text.muted}`}>{side}</div>
      <div className={`text-sm font-semibold ${text.heading}`}>{snap.userName}</div>
      <div className={`text-[11px] mt-1.5 flex items-center gap-1.5 flex-wrap ${text.body}`}>
        {snap.shiftPeriod === 'DAY'
          ? <Sun className="w-3 h-3" />
          : <Moon className="w-3 h-3" />}
        <span className="font-mono">{snap.shiftDate}</span>
        <span className={text.muted}>·</span>
        <span className="font-bold">{snap.shiftPeriod}</span>
        <span className={text.muted}>·</span>
        <span className="font-bold">{snap.zone}</span>
        <span className={text.muted}>·</span>
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
