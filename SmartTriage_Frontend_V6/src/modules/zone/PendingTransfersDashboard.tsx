/* ═══════════════════════════════════════════════════════════════
   PendingTransfersDashboard — charge-nurse cross-zone view of
   every pending zone transfer in the hospital.

   Phase 3 of the zone-routing workflow. Charge nurse / shift lead
   sees this; zoned doctors don't (they get only their zone's
   pending intake on My Patients via Phase 2's banner).

   Design:
     - Auto-refresh every 30 s — pending transfers should turn over
       fast in a busy ED, and a stale dashboard hides overdue ones
     - Overdue highlighting based on SATS windows for the target
       category. RED has 10 min to accept, ORANGE 30 min, YELLOW
       60 min. Past that the row goes red and the count surfaces
       at the top.
     - Acceptance / decline / RESUS_IN_PLACE actions inline, same
       state machine as the per-visit banner. Charge nurse can
       intervene without navigating to the visit page.
     - Click-through to the patient's full visit detail for cases
       that need more context.

   Drives nothing in clinical-decision logic — visibility + nudges
   only. The state machine still gates every move, including any
   the charge nurse triggers.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  RefreshCw, AlertTriangle, ArrowRight, Stethoscope, CheckCircle2,
  XCircle, Loader2, Clock, ShieldAlert, Users,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { useMyShift } from '@/hooks/useMyShift';
import { zoneTransferApi, type ZoneTransferResponse } from '@/api/zoneTransfers';

/**
 * SATS acceptance windows in minutes — the slowest the charge nurse
 * should tolerate a pending transfer for the target zone before
 * escalating. Mirrors the SATS max-wait-by-category SmartTriage uses
 * elsewhere; an overdue pending transfer means the receiving zone
 * hasn't taken the patient inside the wait time the protocol allows
 * for the patient to be seen at all.
 */
const ACCEPTANCE_WINDOW_MINUTES: Record<string, number> = {
  RESUS: 10,
  ACUTE: 30,
  GENERAL: 60,
  AMBULATORY: 60,
  PEDIATRIC: 30,
  // Neonates are physiologically fragile across categories — even a
  // GREEN-coded neonate shouldn't sit in a pending-transfer queue
  // for an hour. Use the tightest window so the dashboard surfaces
  // overdue neonatal transfers as urgently as RESUS ones.
  NEONATAL: 10,
};

function minutesAgo(iso: string): number {
  const t = new Date(iso).getTime();
  return Math.max(0, (Date.now() - t) / 60000);
}

function isOverdue(transfer: ZoneTransferResponse): boolean {
  const window = ACCEPTANCE_WINDOW_MINUTES[transfer.toZone];
  if (window == null) return false;
  return minutesAgo(transfer.initiatedAt) > window;
}

export function PendingTransfersDashboard() {
  const navigate = useNavigate();
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const { isShiftLead, isLoading: shiftLoading } = useMyShift();

  const [transfers, setTransfers] = useState<ZoneTransferResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0); // forces overdue recompute

  const hospitalId = user?.hospitalId ?? '';

  const loadTransfers = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    setError(null);
    try {
      const list = await zoneTransferApi.pendingForHospital(hospitalId);
      setTransfers(Array.isArray(list) ? list : []);
    } catch (err: any) {
      console.error('[PendingTransfersDashboard] failed to load', err);
      setError(err?.message ?? 'Failed to load pending transfers');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadTransfers(); }, [loadTransfers]);

  // Auto-refresh every 30 s. Pending transfers should turn over fast;
  // a 30-second cadence catches new ones without burning the network.
  useEffect(() => {
    const interval = setInterval(() => loadTransfers(), 30_000);
    return () => clearInterval(interval);
  }, [loadTransfers]);

  // Re-render every minute so the overdue badge ticks even if no
  // new transfers arrive. Cheap; no fetch.
  useEffect(() => {
    const interval = setInterval(() => setTick((t) => t + 1), 60_000);
    return () => clearInterval(interval);
  }, []);

  const overdueCount = useMemo(
    () => transfers.filter(isOverdue).length,
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [transfers, tick],
  );

  // Sort: overdue first, then oldest pending, then newest.
  const sorted = useMemo(() => {
    return [...transfers].sort((a, b) => {
      const ao = isOverdue(a) ? 0 : 1;
      const bo = isOverdue(b) ? 0 : 1;
      if (ao !== bo) return ao - bo;
      return new Date(a.initiatedAt).getTime() - new Date(b.initiatedAt).getTime();
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [transfers, tick]);

  const onAct = async (
    transferId: string,
    action: 'accept' | 'decline' | 'resus' | 'cancel',
    reason?: string,
  ) => {
    setBusyId(transferId);
    setError(null);
    try {
      if (action === 'accept') await zoneTransferApi.accept(transferId);
      else if (action === 'decline') await zoneTransferApi.decline(transferId, reason ?? 'Charge-nurse decline');
      else if (action === 'resus') await zoneTransferApi.markResusInPlace(transferId);
      else await zoneTransferApi.cancel(transferId, reason ?? 'Charge-nurse cancel');
      await loadTransfers();
    } catch (e: any) {
      setError(e?.message ?? 'Action failed');
    } finally {
      setBusyId(null);
    }
  };

  if (shiftLoading) {
    return (
      <div className="p-6 flex items-center gap-2 text-sm text-slate-500">
        <Loader2 className="w-4 h-4 animate-spin" /> Resolving shift…
      </div>
    );
  }

  // Access gate: a Charge Nurse (Designation.CHARGE_NURSE) is responsible
  // for the unit's transfers as a permanent appointment — they need this
  // dashboard whether or not they happen to hold the shift-lead badge for
  // an active shift right now. The shift-lead-badge holder also keeps
  // access (covers acting charge nurses on a single shift). HOSPITAL_ADMIN
  // gets governance access as the on-site administrator who answers for
  // cross-zone transfer state. SUPER_ADMIN is deliberately excluded:
  // super-admin is a system-level role (multi-hospital configuration,
  // MoH reporting), not an operational floor role, and pending zone
  // transfers are a floor-level concern. Regular nurses and zoned
  // doctors continue to see only their own zone via the My Patients
  // banner — unchanged behaviour.
  const isChargeNurse = user?.designation === 'CHARGE_NURSE';
  const isHospitalAdmin = user?.role === 'HOSPITAL_ADMIN';
  const canViewDashboard = isChargeNurse || isShiftLead || isHospitalAdmin;
  if (!canViewDashboard) {
    return (
      <div className="p-6 rounded-2xl border border-amber-500/40 bg-amber-50">
        <div className="flex items-start gap-3">
          <ShieldAlert className="w-5 h-5 text-amber-700 flex-shrink-0 mt-0.5" />
          <div>
            <p className="text-sm font-bold text-amber-900">Charge-nurse role required</p>
            <p className="text-xs mt-0.5 text-amber-800">
              The pending-transfers dashboard is visible to charge nurses,
              the active shift lead, and hospital admins. Zoned doctors see
              pending transfers into their own zone via the My Patients
              banner.
            </p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-full p-5 animate-fade-in">
      <div className="space-y-5">
        {/* Header */}
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-amber-500 to-amber-700 flex items-center justify-center shadow-lg shadow-amber-500/20">
              <Users className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className={`text-xl font-bold tracking-tight leading-tight ${text.heading}`}>
                Pending Zone Transfers
              </h1>
              <p className={`text-sm mt-0.5 font-medium ${text.muted}`}>
                {transfers.length} pending across the ED
                {overdueCount > 0 && (
                  <span className="ml-2 text-red-600 font-bold">
                    · {overdueCount} overdue
                  </span>
                )}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={loadTransfers}
            className="inline-flex items-center gap-2 px-3 py-2 text-xs font-bold rounded-lg bg-white/60 hover:bg-white/80"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
        </div>

        {error && (
          <div className="rounded-xl p-3 border border-red-500/30 bg-red-500/10 text-red-700 text-xs">
            {error}
          </div>
        )}

        {/* List */}
        {loading && transfers.length === 0 ? (
          <div className="rounded-2xl p-10 text-center" style={glassCard}>
            <Loader2 className="w-6 h-6 mx-auto mb-2 text-amber-500 animate-spin" />
            <p className={`text-sm ${text.muted}`}>Loading pending transfers…</p>
          </div>
        ) : sorted.length === 0 ? (
          <div className="rounded-2xl p-10 text-center" style={glassCard}>
            <CheckCircle2 className="w-8 h-8 mx-auto mb-2 text-emerald-500" />
            <p className={`text-sm font-semibold ${text.heading}`}>All clear.</p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              No pending zone transfers in the ED right now.
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {sorted.map((t) => {
              const overdue = isOverdue(t);
              const ageMinutes = Math.round(minutesAgo(t.initiatedAt));
              const satsWindow = ACCEPTANCE_WINDOW_MINUTES[t.toZone];
              const acting = busyId === t.id;
              return (
                <div
                  key={t.id}
                  className={`rounded-2xl p-4 border ${overdue ? 'border-red-500/50 bg-red-500/5' : 'border-amber-500/30 bg-amber-500/5'}`}
                  style={glassInner}
                >
                  <div className="flex flex-wrap items-start gap-3">
                    <div className="flex-1 min-w-[240px]">
                      <div className="flex items-center gap-2 flex-wrap">
                        <button
                          type="button"
                          onClick={() => navigate(`/visit/${t.visitId}`)}
                          className={`text-sm font-bold ${text.heading} hover:underline`}
                        >
                          {t.patientName ?? t.visitNumber}
                        </button>
                        <span className={`text-[10px] font-mono ${text.muted}`}>{t.visitNumber}</span>
                        {t.isPediatric && (
                          <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-pink-500/15 text-pink-700">
                            Peds
                          </span>
                        )}
                      </div>
                      <div className="mt-1.5 flex items-center gap-2 text-xs">
                        <span className="font-semibold text-slate-700">{t.fromZone ?? '—'}</span>
                        <ArrowRight className="w-3 h-3 text-slate-400" />
                        <span className={`font-bold ${overdue ? 'text-red-700' : 'text-amber-700'}`}>{t.toZone}</span>
                      </div>
                      {t.reason && (
                        <p className={`text-[11px] mt-1 ${text.body}`}>{t.reason}</p>
                      )}
                      {t.proposedClinicianName && (
                        <p className={`text-[10px] mt-0.5 ${text.muted}`}>
                          Proposed receiver: <span className="font-semibold">{t.proposedClinicianName}</span>
                        </p>
                      )}
                      <div className={`mt-1.5 flex items-center gap-1.5 text-[11px] ${overdue ? 'text-red-700 font-bold' : text.muted}`}>
                        <Clock className="w-3 h-3" />
                        Pending {ageMinutes} min
                        {satsWindow != null && (
                          <>
                            <span className={text.muted}>·</span>
                            <span>SATS window: {satsWindow} min</span>
                          </>
                        )}
                        {overdue && (
                          <>
                            <span>·</span>
                            <AlertTriangle className="w-3 h-3" />
                            <span>OVERDUE</span>
                          </>
                        )}
                      </div>
                    </div>

                    <div className="flex flex-wrap gap-2">
                      <button
                        type="button"
                        disabled={acting}
                        onClick={() => onAct(t.id, 'accept')}
                        className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] font-bold rounded-md bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50"
                        title="Charge nurse acknowledges and takes the patient"
                      >
                        <CheckCircle2 className="w-3 h-3" />
                        Accept
                      </button>
                      <button
                        type="button"
                        disabled={acting}
                        onClick={() => onAct(t.id, 'resus')}
                        className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] font-bold rounded-md bg-amber-600 text-white hover:bg-amber-700 disabled:opacity-50"
                        title="Treat at higher acuity in the current physical location"
                      >
                        <Stethoscope className="w-3 h-3" />
                        In place
                      </button>
                      <button
                        type="button"
                        disabled={acting}
                        onClick={() => {
                          const reason = window?.prompt('Decline reason (e.g. Resus full):');
                          if (reason && reason.trim()) onAct(t.id, 'decline', reason.trim());
                        }}
                        className="inline-flex items-center gap-1 px-2.5 py-1 text-[11px] font-bold rounded-md bg-white text-red-700 border border-red-500/40 hover:bg-red-50 disabled:opacity-50"
                      >
                        <XCircle className="w-3 h-3" />
                        Decline
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

export default PendingTransfersDashboard;
