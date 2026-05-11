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

export function DelegationsPage() {
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
            <h1 className="text-xl font-bold text-gray-900 tracking-tight">Delegations</h1>
          </div>
          <span className="ml-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-violet-50 text-violet-700 border border-violet-200 text-xs font-bold">
            <UserCheck className="w-3 h-3" />
            {active.length} active
          </span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => void refresh()}
            disabled={loading}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
            Refresh
          </button>
          {!isReadOnly && (
            <button
              onClick={() => setShowIssueForm(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-bold hover:bg-blue-700 transition-colors"
            >
              <Plus className="w-3.5 h-3.5" />
              Issue delegation
            </button>
          )}
        </div>
      </header>

      {err && (
        <div className="bg-rose-50 border border-rose-200 text-rose-700 px-3 py-2 rounded-lg text-xs flex items-center gap-2">
          <AlertTriangle className="w-3.5 h-3.5" />
          {err}
        </div>
      )}

      {/* Active delegations */}
      <section className="bg-white rounded-2xl border border-gray-200 p-5">
        <div className="flex items-center gap-2 mb-3">
          <UserCheck className="w-4 h-4 text-emerald-600" />
          <h2 className="text-sm font-bold text-gray-900">Active right now</h2>
          <span className="text-[10px] font-semibold text-gray-400">({active.length})</span>
        </div>
        {loading && active.length === 0 && (
          <div className="text-xs text-gray-400">Loading…</div>
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
      <section className="bg-white rounded-2xl border border-gray-200 p-5">
        <div className="flex items-center gap-2 mb-3">
          <Clock className="w-4 h-4 text-gray-500" />
          <h2 className="text-sm font-bold text-gray-900">Issued by me</h2>
          <span className="text-[10px] font-semibold text-gray-400">({myIssued.length})</span>
        </div>
        {loading && myIssued.length === 0 && (
          <div className="text-xs text-gray-400">Loading…</div>
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
  );
}

/* ─── Empty-state ─── */

function EmptyState({ label, sub }: { label: string; sub?: string }) {
  return (
    <div className="text-center py-6">
      <Inbox className="w-7 h-7 text-gray-300 mx-auto mb-2" />
      <div className="text-xs font-bold text-gray-700">{label}</div>
      {sub && <div className="text-[11px] text-gray-500 mt-0.5">{sub}</div>}
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
    <li className="border border-gray-200 rounded-lg p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-sm">
            <span className="font-bold text-gray-900">{delegation.delegatingUserName}</span>
            <span className="text-gray-400 mx-1.5">→</span>
            <span className="font-bold text-gray-900">{delegation.delegateUserName}</span>
          </div>
          <div className="text-[11px] text-gray-500 mt-1 flex items-center gap-1.5 flex-wrap">
            <Clock className="w-3 h-3" />
            <span>From {fmtDateTime(delegation.startsAt)}</span>
            {delegation.endsAt ? (
              <>
                <span className="text-gray-300">·</span>
                <span>until {fmtDateTime(delegation.endsAt)}</span>
              </>
            ) : (
              <>
                <span className="text-gray-300">·</span>
                <span className="italic">no end date</span>
              </>
            )}
          </div>
          {delegation.reason && (
            <div className="text-[12px] text-gray-700 mt-1.5 italic">
              “{delegation.reason}”
            </div>
          )}
          {delegation.revokedAt && (
            <div className="text-[11px] text-rose-700 mt-1.5">
              Revoked {fmtDateTime(delegation.revokedAt)}
              {delegation.revokedByName && <> by {delegation.revokedByName}</>}
              {delegation.revocationReason && <>: “{delegation.revocationReason}”</>}
            </div>
          )}
        </div>

        <div className="shrink-0 flex flex-col items-end gap-1.5">
          <span className={[
            'px-2 py-0.5 rounded-full text-[10px] font-bold border',
            status.classes,
          ].join(' ')}>
            {status.label}
          </span>
          {!readOnly && active && delegation.currentlyActive && !delegation.revokedAt && (
            <button
              onClick={revoke}
              disabled={busy}
              className="inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold text-rose-700 bg-rose-50 hover:bg-rose-100 border border-rose-200 disabled:opacity-50"
            >
              {busy ? <Loader2 className="w-3 h-3 animate-spin" /> : <Ban className="w-3 h-3" />}
              Revoke
            </button>
          )}
        </div>
      </div>
      {err && (
        <div className="mt-2 text-[11px] text-rose-700 bg-rose-50 border border-rose-200 px-2 py-1 rounded">
          {err}
        </div>
      )}
    </li>
  );
}

interface StatusInfo { label: string; classes: string; }

function describeStatus(d: ChargeNurseDelegationResponse): StatusInfo {
  if (d.revokedAt) {
    return {
      label: 'Revoked',
      classes: 'bg-rose-50 text-rose-700 border-rose-200',
    };
  }
  if (d.currentlyActive) {
    return {
      label: 'Active',
      classes: 'bg-emerald-50 text-emerald-700 border-emerald-200',
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
      classes: 'bg-blue-50 text-blue-700 border-blue-200',
    };
  }
  if (end !== null && end < now) {
    return {
      label: 'Expired',
      classes: 'bg-gray-50 text-gray-500 border-gray-200',
    };
  }
  return {
    label: 'Inactive',
    classes: 'bg-gray-50 text-gray-500 border-gray-200',
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
    <div className="fixed inset-0 bg-black/40 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-2xl border border-gray-200 w-full max-w-2xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-100">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-gray-500" />
            <h2 className="text-base font-bold text-gray-900">Issue acting-CN delegation</h2>
          </div>
          <button
            onClick={onClose}
            disabled={submitting}
            className="p-1 rounded hover:bg-gray-100 disabled:opacity-50"
            aria-label="Close"
          >
            <X className="w-4 h-4 text-gray-500" />
          </button>
        </div>

        {/* Body */}
        <div className="px-5 py-4 space-y-4 overflow-y-auto">
          {/* Delegate picker */}
          <section>
            <label className="block">
              <div className="text-[11px] font-bold uppercase text-gray-500 mb-1">
                Delegate to (NURSE role only)
              </div>
              <div className="relative">
                <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400" />
                <input
                  type="text"
                  className="w-full text-sm border border-gray-200 rounded-lg pl-7 pr-2 py-1.5"
                  placeholder="Search by name or email…"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                />
              </div>
            </label>

            <div className="mt-2 max-h-56 overflow-y-auto border border-gray-200 rounded-lg divide-y divide-gray-100 bg-gray-50">
              {loadingUsers && (
                <div className="px-3 py-2 text-xs text-gray-400 flex items-center gap-1.5">
                  <Loader2 className="w-3 h-3 animate-spin" />
                  Loading colleagues…
                </div>
              )}
              {usersErr && (
                <div className="px-3 py-2 text-[11px] text-rose-700">
                  {usersErr}
                </div>
              )}
              {!loadingUsers && filtered.length === 0 && !usersErr && (
                <div className="px-3 py-2 text-xs text-gray-400 italic">
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
                        ? 'bg-blue-50 ring-1 ring-blue-200'
                        : 'bg-white hover:bg-gray-50',
                    ].join(' ')}
                  >
                    <div className="font-semibold text-gray-900">
                      {u.firstName} {u.lastName}
                      {u.designationLabel && (
                        <span className="ml-2 text-[10px] font-bold text-gray-500 bg-gray-100 px-1.5 py-0.5 rounded">
                          {u.designationLabel}
                        </span>
                      )}
                    </div>
                    <div className="text-[11px] text-gray-500 mt-0.5">
                      {u.email}
                      {u.department && <span className="ml-2">· {u.department}</span>}
                    </div>
                  </button>
                );
              })}
            </div>

            {selected && (
              <div className="text-[11px] text-blue-700 mt-1.5 inline-flex items-center gap-1">
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
                className="w-full text-sm border border-gray-200 rounded-lg px-2 py-1.5"
                value={startsAt}
                onChange={(e) => setStartsAt(e.target.value)}
              />
            </FormRow>
            <FormRow label="Ends at (optional)">
              <input
                type="datetime-local"
                className="w-full text-sm border border-gray-200 rounded-lg px-2 py-1.5"
                value={endsAt}
                onChange={(e) => setEndsAt(e.target.value)}
                placeholder="Leave blank for open-ended"
              />
            </FormRow>
          </section>

          <FormRow label="Reason (required, audit-visible)">
            <textarea
              className="w-full text-sm border border-gray-200 rounded-lg px-2 py-1.5 min-h-[60px]"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. Annual leave 6–12 May; cover swap and leave approvals."
            />
          </FormRow>

          {submitErr && (
            <div className="text-[11px] text-rose-700 bg-rose-50 border border-rose-200 px-2 py-1.5 rounded flex items-center gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5" />
              {submitErr}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-gray-100 flex items-center justify-between bg-gray-50 rounded-b-2xl">
          <div className="text-[11px] text-gray-500">
            The delegate inherits Charge Nurse authority on swap and leave decisions during this window.
          </div>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              disabled={submitting}
              className="px-3 py-1.5 rounded-lg text-xs font-bold text-gray-600 hover:bg-gray-100 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              onClick={submit}
              disabled={submitting || !delegateUserId || !reason.trim()}
              className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-bold hover:bg-blue-700 inline-flex items-center gap-1.5 disabled:opacity-50 disabled:cursor-not-allowed"
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
  return (
    <label className="block">
      <div className="text-[11px] font-bold uppercase text-gray-500 mb-1">{label}</div>
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
