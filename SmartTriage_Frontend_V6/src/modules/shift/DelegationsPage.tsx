/* ════════════════════════════════════════════════════════════════════
   /delegations — Charge Nurse delegation management (Phase F).

   The point of this surface:
     • A Charge Nurse going on leave / off-shift hands the swap-
       approval (and other CN) authority to a trusted nurse for a
       bounded window. Without UI here, the V41 backend was reachable
       only by curl — which means in practice CN authority would
       silently fail to transfer when the named CN was away. Closing
       that loop is the whole reason for this page.

   What it shows:
     • Active delegations card  — every delegation currently in force
       at this hospital, with an "Acting since … until …" line and
       a Revoke button.
     • My issued delegations card — the authenticated user's own
       history of delegations they've handed out (so a CN can audit
       what they've authorized).
     • Issue-delegation button → opens a modal that lets a CN /
       admin pick a NURSE-role colleague, choose a window, write a
       reason, and submit.

   Authorization:
     • Page-level grant: HOSPITAL_ADMIN / SUPER_ADMIN by role,
       Charge Nurses by designation. Mutation endpoints are guarded
       server-side.

   Display rule for "Active":
     • The backend's `currentlyActive` flag is authoritative — it
       considers startsAt <= now, endsAt is null/future, and revokedAt
       is null. We trust it and just render the active list as-is.
   ════════════════════════════════════════════════════════════════════ */

import { useEffect, useMemo, useState } from 'react';
import {
  ShieldCheck, RefreshCw, Plus, X, Loader2, AlertTriangle,
  UserCheck, Clock, Ban, Inbox, ChevronDown, Search,
} from 'lucide-react';
import { delegationApi, userApi } from '@/api';
import type {
  ChargeNurseDelegationResponse,
  CreateChargeNurseDelegationRequest,
  UserResponse,
} from '@/api/types';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';

export function DelegationsPage() {
  const { glassCard, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  // HOSPITAL_ADMIN sees delegations read-only; only CHARGE_NURSE may
  // create or revoke them.
  const isReadOnly = user?.role === 'HOSPITAL_ADMIN'
    && user?.designation !== 'CHARGE_NURSE';

  const [active, setActive] = useState<ChargeNurseDelegationResponse[]>([]);
  const [myIssued, setMyIssued] = useState<ChargeNurseDelegationResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [showIssueForm, setShowIssueForm] = useState(false);

  const refresh = async () => {
    if (!hospitalId) return;
    setLoading(true);
    setErr(null);
    try {
      const [activeRows, issuedRows] = await Promise.all([
        delegationApi.listActive(hospitalId),
        delegationApi.listMyIssued(),
      ]);
      // Active first sorted by startsAt asc — oldest still-running
      // delegations float to top so a CN sees the longest-running
      // one first (likely the one they care most about reviewing).
      activeRows.sort((a, b) => a.startsAt.localeCompare(b.startsAt));
      // Issued — newest first, that's the natural history order.
      issuedRows.sort((a, b) => b.startsAt.localeCompare(a.startsAt));
      setActive(activeRows);
      setMyIssued(issuedRows);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load delegations');
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
        {/* Header banner */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                <ShieldCheck className="w-5 h-5 text-cyan-300" />
              </div>
              <div>
                <div className="text-white/50 text-xs font-bold uppercase tracking-wide">Charge Nurse</div>
                <h1 className="text-lg font-bold text-white tracking-tight">Delegations</h1>
              </div>
              <span
                className="ml-2 inline-flex items-center gap-1 px-2.5 py-0.5 rounded-lg text-[9px] font-bold uppercase tracking-wider text-cyan-600"
                style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}
              >
                <UserCheck className="w-3 h-3" />
                {active.length} active
              </span>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={() => void refresh()}
                disabled={loading}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-white/10 hover:bg-white/15 text-xs font-semibold text-white disabled:opacity-50"
              >
                {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
                Refresh
              </button>
              {!isReadOnly && (
                <button
                  onClick={() => setShowIssueForm(true)}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-cyan-600 text-white text-xs font-bold hover:bg-cyan-700 transition-colors"
                >
                  <Plus className="w-3.5 h-3.5" />
                  Issue delegation
                </button>
              )}
            </div>
          </div>
        </div>

        {err && (
          <div className="bg-rose-500/20 border border-rose-500/30 text-rose-300 px-3 py-2 rounded-xl text-xs flex items-center gap-2">
            <AlertTriangle className="w-3.5 h-3.5" />
            {err}
          </div>
        )}

        {/* Active delegations */}
        <section className="rounded-3xl p-5 animate-fade-up" style={glassCard}>
          <div className="flex items-center gap-2 mb-3">
            <UserCheck className="w-4 h-4 text-emerald-400" />
            <h2 className={`text-sm font-bold ${text.heading}`}>Active right now</h2>
            <span className={`text-[10px] font-semibold ${text.muted}`}>({active.length})</span>
          </div>
          {loading && active.length === 0 && (
            <div className={`text-xs ${text.muted}`}>Loading…</div>
          )}
          {!loading && active.length === 0 && (
            <EmptyState
              label="No active delegations."
              sub="Issue a delegation when you'll be off-shift so swap and leave decisions can still be made."
            />
          )}
          <ul className="space-y-3">
            {active.map((d) => (
              <DelegationRow key={d.id} delegation={d} active onChange={refresh} readOnly={isReadOnly} />
            ))}
          </ul>
        </section>

        {/* My history */}
        <section className="rounded-3xl p-5 animate-fade-up" style={glassCard}>
          <div className="flex items-center gap-2 mb-3">
            <Clock className={`w-4 h-4 ${text.muted}`} />
            <h2 className={`text-sm font-bold ${text.heading}`}>Issued by me</h2>
            <span className={`text-[10px] font-semibold ${text.muted}`}>({myIssued.length})</span>
          </div>
          {loading && myIssued.length === 0 && (
            <div className={`text-xs ${text.muted}`}>Loading…</div>
          )}
          {!loading && myIssued.length === 0 && (
            <EmptyState
              label="You haven't issued any delegations."
              sub="Anything you delegate from here will show up in this audit list."
            />
          )}
          <ul className="space-y-3">
            {myIssued.map((d) => (
              <DelegationRow key={d.id} delegation={d} active={false} onChange={refresh} readOnly={isReadOnly} />
            ))}
          </ul>
        </section>

        {showIssueForm && (
          <IssueDelegationModal
            hospitalId={hospitalId}
            onClose={() => setShowIssueForm(false)}
            onSubmitted={async () => {
              setShowIssueForm(false);
              await refresh();
            }}
          />
        )}
      </div>
    </div>
  );
}

/* ─── Empty-state ─── */

function EmptyState({ label, sub }: { label: string; sub?: string }) {
  const { text } = useTheme();
  return (
    <div className="text-center py-6">
      <Inbox className={`w-7 h-7 ${text.muted} mx-auto mb-2`} />
      <div className={`text-xs font-bold ${text.label}`}>{label}</div>
      {sub && <div className={`text-[11px] ${text.muted} mt-0.5`}>{sub}</div>}
    </div>
  );
}

/* ─── Delegation row ─── */

function DelegationRow({
  delegation, active, onChange, readOnly = false,
}: {
  delegation: ChargeNurseDelegationResponse;
  active: boolean;
  onChange: () => Promise<void>;
  readOnly?: boolean;
}) {
  const { glassInner, text } = useTheme();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const revoke = async () => {
    const note = window.prompt('Reason for revoking (visible to delegate, optional):');
    if (note === null) return;     // user cancelled
    setBusy(true); setErr(null);
    try {
      await delegationApi.revoke(
        delegation.id,
        note.trim() ? { revocationReason: note.trim() } : undefined,
      );
      await onChange();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Action failed');
    } finally {
      setBusy(false);
    }
  };

  const status = describeStatus(delegation);

  return (
    <li className="rounded-xl p-3" style={glassInner}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-sm">
            <span className={`font-bold ${text.heading}`}>{delegation.delegatingUserName}</span>
            <span className={`${text.muted} mx-1.5`}>→</span>
            <span className={`font-bold ${text.heading}`}>{delegation.delegateUserName}</span>
          </div>
          <div className={`text-[11px] ${text.muted} mt-1 flex items-center gap-1.5 flex-wrap`}>
            <Clock className="w-3 h-3" />
            <span>From {fmtDateTime(delegation.startsAt)}</span>
            {delegation.endsAt ? (
              <>
                <span className={text.muted}>·</span>
                <span>until {fmtDateTime(delegation.endsAt)}</span>
              </>
            ) : (
              <>
                <span className={text.muted}>·</span>
                <span className="italic">no end date</span>
              </>
            )}
          </div>
          {delegation.reason && (
            <div className={`text-[12px] ${text.body} mt-1.5 italic`}>
              “{delegation.reason}”
            </div>
          )}
          {delegation.revokedAt && (
            <div className="text-[11px] text-rose-300 mt-1.5">
              Revoked {fmtDateTime(delegation.revokedAt)}
              {delegation.revokedByName && <> by {delegation.revokedByName}</>}
              {delegation.revocationReason && <>: “{delegation.revocationReason}”</>}
            </div>
          )}
        </div>

        <div className="shrink-0 flex flex-col items-end gap-1.5">
          <span
            className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${status.textClass}`}
            style={status.style}
          >
            {status.label}
          </span>
          {!readOnly && active && delegation.currentlyActive && !delegation.revokedAt && (
            <button
              onClick={revoke}
              disabled={busy}
              className="inline-flex items-center gap-1 px-2 py-1 rounded-xl text-[10px] font-bold text-rose-300 bg-rose-500/20 hover:bg-rose-500/30 border border-rose-500/30 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3 h-3 animate-spin" /> : <Ban className="w-3 h-3" />}
              Revoke
            </button>
          )}
        </div>
      </div>
      {err && (
        <div className="mt-2 text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1 rounded">
          {err}
        </div>
      )}
    </li>
  );
}

interface StatusInfo { label: string; textClass: string; style: React.CSSProperties; }

function describeStatus(d: ChargeNurseDelegationResponse): StatusInfo {
  if (d.revokedAt) {
    return {
      label: 'Revoked',
      textClass: 'text-rose-600',
      style: { background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' },
    };
  }
  if (d.currentlyActive) {
    return {
      label: 'Active',
      textClass: 'text-emerald-600',
      style: { background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' },
    };
  }
  // Not active and not revoked → either future-dated or already
  // expired. Tell them apart by comparing to now.
  const start = new Date(d.startsAt).getTime();
  const end = d.endsAt ? new Date(d.endsAt).getTime() : null;
  const now = Date.now();
  if (start > now) {
    return {
      label: 'Scheduled',
      textClass: 'text-cyan-600',
      style: { background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' },
    };
  }
  if (end !== null && end < now) {
    return {
      label: 'Expired',
      textClass: 'text-slate-600',
      style: { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' },
    };
  }
  return {
    label: 'Inactive',
    textClass: 'text-slate-600',
    style: { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' },
  };
}

function fmtDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-US', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

/* ════════════════════════════════════════════════════════════════════
   Issue-delegation modal
   ════════════════════════════════════════════════════════════════════ */

function IssueDelegationModal({
  hospitalId, onClose, onSubmitted,
}: {
  hospitalId: string;
  onClose: () => void;
  onSubmitted: () => void | Promise<void>;
}) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark
    ? '1px solid rgba(2,132,199,0.12)'
    : '1px solid rgba(203,213,225,0.3)';
  const me = useAuthStore((s) => s.user);

  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [usersErr, setUsersErr] = useState<string | null>(null);

  const [delegateUserId, setDelegateUserId] = useState<string>('');
  const [filter, setFilter] = useState('');
  const [startsAt, setStartsAt] = useState<string>(toLocalInput(new Date()));
  const [endsAt, setEndsAt] = useState<string>('');                // optional
  const [reason, setReason] = useState<string>('');

  const [submitting, setSubmitting] = useState(false);
  const [submitErr, setSubmitErr] = useState<string | null>(null);

  /* Pull a generous page of hospital users; filter NURSE-role
     colleagues client-side. The backend's eligibility check is the
     authoritative gate — we just narrow the picker here so the CN
     doesn't waste time on an obvious no. */
  useEffect(() => {
    let cancelled = false;
    setLoadingUsers(true);
    setUsersErr(null);
    userApi
      .getByHospital(hospitalId, 0, 200)
      .then((page) => {
        if (cancelled) return;
        const candidates = page.content.filter(
          (u) =>
            u.role === 'NURSE' &&
            u.id !== me?.id &&                  // can't delegate to yourself
            u.accountStatus === 'ACTIVE',
        );
        candidates.sort((a, b) =>
          (a.firstName + a.lastName).localeCompare(b.firstName + b.lastName),
        );
        setUsers(candidates);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setUsersErr(e instanceof Error ? e.message : 'Failed to load colleagues');
      })
      .finally(() => {
        if (!cancelled) setLoadingUsers(false);
      });
    return () => { cancelled = true; };
  }, [hospitalId, me?.id]);

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return users;
    return users.filter(
      (u) =>
        u.firstName.toLowerCase().includes(q) ||
        u.lastName.toLowerCase().includes(q) ||
        u.email.toLowerCase().includes(q) ||
        (u.designationLabel && u.designationLabel.toLowerCase().includes(q)),
    );
  }, [users, filter]);

  const submit = async () => {
    setSubmitErr(null);

    if (!delegateUserId) {
      setSubmitErr('Pick a colleague to delegate to.');
      return;
    }
    if (!reason.trim()) {
      setSubmitErr('A reason is required so the audit log makes sense.');
      return;
    }
    if (endsAt && endsAt <= startsAt) {
      setSubmitErr('End time must be after start time.');
      return;
    }

    setSubmitting(true);
    try {
      const body: CreateChargeNurseDelegationRequest = {
        delegateUserId,
        // datetime-local input gives "YYYY-MM-DDTHH:mm" without
        // timezone. The backend accepts ISO; the browser's local TZ
        // is the right interpretation here (a CN entering "Mon 09:00"
        // means their wall clock).
        startsAt: new Date(startsAt).toISOString(),
        endsAt: endsAt ? new Date(endsAt).toISOString() : null,
        reason: reason.trim(),
      };
      await delegationApi.create(hospitalId, body);
      await onSubmitted();
    } catch (e: unknown) {
      setSubmitErr(e instanceof Error ? e.message : 'Submission failed');
    } finally {
      setSubmitting(false);
    }
  };

  const selected = users.find((u) => u.id === delegateUserId);

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
    >
      <div
        className="rounded-2xl overflow-hidden shadow-2xl animate-scale-in w-full max-w-2xl max-h-[90vh] flex flex-col"
        style={glassCard}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-5 py-4"
          style={{ borderBottom: borderStyle }}
        >
          <div className="flex items-center gap-2">
            <ShieldCheck className={`w-4 h-4 ${text.muted}`} />
            <h2 className={`text-base font-bold ${text.heading}`}>Issue acting-CN delegation</h2>
          </div>
          <button
            onClick={onClose}
            disabled={submitting}
            className={`w-9 h-9 rounded-xl flex items-center justify-center hover:bg-white/10 disabled:opacity-50 ${text.muted}`}
            aria-label="Close"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-4 overflow-y-auto">
          {/* Delegate picker */}
          <section>
            <label className="block">
              <div className={`text-[11px] font-bold uppercase ${text.label} mb-1`}>
                Delegate to (NURSE role only)
              </div>
              <div className="relative">
                <Search className={`absolute left-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 ${text.muted}`} />
                <input
                  type="text"
                  className={`w-full text-sm rounded-xl pl-7 pr-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                  style={glassInner}
                  placeholder="Search by name or email…"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                />
              </div>
            </label>

            <div className="mt-2 max-h-56 overflow-y-auto rounded-xl" style={glassInner}>
              {loadingUsers && (
                <div className={`px-3 py-2 text-xs ${text.muted} flex items-center gap-1.5`}>
                  <Loader2 className="w-3 h-3 animate-spin" />
                  Loading colleagues…
                </div>
              )}
              {usersErr && (
                <div className="px-3 py-2 text-[11px] text-rose-300">
                  {usersErr}
                </div>
              )}
              {!loadingUsers && filtered.length === 0 && !usersErr && (
                <div className={`px-3 py-2 text-xs ${text.muted} italic`}>
                  No matching nurses.
                </div>
              )}
              {filtered.map((u) => {
                const isSelected = delegateUserId === u.id;
                return (
                  <button
                    key={u.id}
                    type="button"
                    onClick={() => setDelegateUserId(u.id)}
                    className={[
                      'w-full text-left px-3 py-2 text-sm transition-colors',
                      isSelected
                        ? 'bg-cyan-500/20 ring-1 ring-cyan-500/30'
                        : 'hover:bg-white/5',
                    ].join(' ')}
                  >
                    <div className={`font-semibold ${text.heading}`}>
                      {u.firstName} {u.lastName}
                      {u.designationLabel && (
                        <span className={`ml-2 text-[10px] font-bold ${text.muted} bg-white/10 px-1.5 py-0.5 rounded`}>
                          {u.designationLabel}
                        </span>
                      )}
                    </div>
                    <div className={`text-[11px] ${text.muted} mt-0.5`}>
                      {u.email}
                      {u.department && <span className="ml-2">· {u.department}</span>}
                    </div>
                  </button>
                );
              })}
            </div>

            {selected && (
              <div className="text-[11px] text-cyan-400 mt-1.5 inline-flex items-center gap-1">
                <ChevronDown className="w-3 h-3" />
                Selected: <span className="font-bold">{selected.firstName} {selected.lastName}</span>
              </div>
            )}
          </section>

          {/* Window */}
          <section className="grid grid-cols-2 gap-3">
            <FormRow label="Starts at">
              <input
                type="datetime-local"
                className={`w-full text-sm rounded-xl px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                style={glassInner}
                value={startsAt}
                onChange={(e) => setStartsAt(e.target.value)}
              />
            </FormRow>
            <FormRow label="Ends at (optional)">
              <input
                type="datetime-local"
                className={`w-full text-sm rounded-xl px-2 py-1.5 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
                style={glassInner}
                value={endsAt}
                onChange={(e) => setEndsAt(e.target.value)}
                placeholder="Leave blank for open-ended"
              />
            </FormRow>
          </section>

          <FormRow label="Reason (required, audit-visible)">
            <textarea
              className={`w-full text-sm rounded-xl px-2 py-1.5 min-h-[60px] focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              style={glassInner}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. Annual leave 6–12 May; cover swap and leave approvals."
            />
          </FormRow>

          {submitErr && (
            <div className="text-[11px] text-rose-300 bg-rose-500/20 border border-rose-500/30 px-2 py-1.5 rounded flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" />
              {submitErr}
            </div>
          )}
        </div>

        {/* Footer */}
        <div
          className="px-5 py-3 flex items-center justify-between"
          style={{ borderTop: borderStyle }}
        >
          <div className={`text-[11px] ${text.muted}`}>
            The delegate inherits Charge Nurse authority on swap and leave decisions during this window.
          </div>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              disabled={submitting}
              className={`px-3 py-1.5 rounded-xl text-xs font-bold hover:bg-white/10 disabled:opacity-50 ${text.body}`}
            >
              Cancel
            </button>
            <button
              onClick={submit}
              disabled={submitting || !delegateUserId || !reason.trim()}
              className="px-3 py-1.5 rounded-xl bg-cyan-600 text-white text-xs font-bold hover:bg-cyan-700 inline-flex items-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {submitting && <Loader2 className="w-3 h-3 animate-spin" />}
              Issue delegation
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function FormRow({ label, children }: { label: string; children: React.ReactNode }) {
  const { text } = useTheme();
  return (
    <label className="block">
      <div className={`text-[11px] font-bold uppercase ${text.label} mb-1`}>{label}</div>
      {children}
    </label>
  );
}

/** Format a Date as the "YYYY-MM-DDTHH:mm" value an `<input type="datetime-local">` expects. */
function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return [
    d.getFullYear(), '-', pad(d.getMonth() + 1), '-', pad(d.getDate()),
    'T', pad(d.getHours()), ':', pad(d.getMinutes()),
  ].join('');
}
