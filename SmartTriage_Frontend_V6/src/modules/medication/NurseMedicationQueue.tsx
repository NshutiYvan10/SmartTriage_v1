/* ════════════════════════════════════════════════════════════════════
   NurseMedicationQueue — Workflow 3 dedicated nurse-facing surface.

   Before this page, a nurse only saw newly prescribed medications by
   opening each visit's detail page in turn. That was the silent
   failure: a STAT antibiotic ordered on one chart while the nurse
   was looking at another patient could sit unadministered for
   minutes with no signal anywhere.

   This page aggregates every PRESCRIBED medication across the
   hospital that hasn't been administered yet, sorts STAT → URGENT →
   ROUTINE then oldest first, and subscribes to
   /topic/medications/{hospitalId} so new orders land in real time
   (with a STAT toast when the priority is highest).

   Per row the nurse can:
     • Administer (single-click — also requires backend separation-
       of-duties: the prescriber cannot be the administerer).
     • Hold       (prompt for reason ≥ 3 chars, NPO / awaiting labs).
     • Refuse     (prompt for reason — patient declined).
     • Open the visit detail for full chart context.

   The SLA timer per row colours red once past the priority window
   (10/30/240 min). STAT past 10 min → red, dark + pulsing.

   Out of scope for this page (Round 4):
     • Countersign workflow — handled in visit-detail Medications
       tab where the row already shows the full chain.
     • Per-zone filtering — first version is hospital-wide; a future
       version can subset to the nurse's current zone assignment.
   ════════════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Pill, Clock, AlertTriangle, Loader2, RefreshCw, ExternalLink,
  CheckCircle2, Pause, XCircle, ShieldAlert,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { medicationApi } from '@/api/medications';
import { subscribeToMedications } from '@/api/websocket';
import type { MedicationPriority, MedicationResponse } from '@/api/types';
import { MEDICATION_PRIORITIES } from '@/api/types';
import { PatientContextLine } from '@/components/PatientContextLine';
import { useTheme } from '@/hooks/useTheme';
import { formatDistanceToNow } from 'date-fns';

interface SlaInfo {
  elapsedMin: number;
  slaMin: number;
  overdueBy: number;
  isOverdue: boolean;
}

function slaInfo(med: MedicationResponse): SlaInfo {
  const elapsedMs = Date.now() - new Date(med.prescribedAt).getTime();
  const elapsedMin = Math.floor(elapsedMs / 60_000);
  const priority = med.priority ?? 'ROUTINE';
  const slaMin = MEDICATION_PRIORITIES.find((p) => p.value === priority)?.slaMinutes ?? 240;
  const overdueBy = Math.max(0, elapsedMin - slaMin);
  return { elapsedMin, slaMin, overdueBy, isOverdue: overdueBy > 0 };
}

function priorityMeta(p: MedicationPriority | undefined) {
  return MEDICATION_PRIORITIES.find((row) => row.value === (p ?? 'ROUTINE'))
    ?? MEDICATION_PRIORITIES[MEDICATION_PRIORITIES.length - 1];
}

export function NurseMedicationQueue({ embedded = false }: { embedded?: boolean }) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const userId = user?.id;
  const navigate = useNavigate();

  const [queue, setQueue] = useState<MedicationResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  // In-app action error (replaces the old window.alert) + reason-capture
  // modal (replaces the old window.prompt for hold/refuse).
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [reasonModal, setReasonModal] = useState<{ medId: string; action: 'hold' | 'refuse' } | null>(null);
  const [reasonText, setReasonText] = useState('');

  // STAT-toast banner. We deliberately only flash on STAT (not URGENT)
  // because the toast is meant to interrupt the nurse on another
  // chart; URGENT shows up in the list but doesn't shout.
  const [statBanner, setStatBanner] = useState<MedicationResponse | null>(null);

  // Tick every 30s so SLA timers visibly advance without flooding
  // the React tree.
  const [, setNow] = useState(0);
  useEffect(() => {
    const t = window.setInterval(() => setNow((n) => n + 1), 30_000);
    return () => window.clearInterval(t);
  }, []);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading((prev) => prev || queue.length === 0);
    setRefreshing(true);
    setErr(null);
    try {
      const rows = await medicationApi.getQueue(hospitalId);
      setQueue(Array.isArray(rows) ? rows : []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load queue');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [hospitalId, queue.length]);

  useEffect(() => { void load(); }, [load]);

  // Real-time updates — debounced full reload because the row may
  // have changed status, priority, or notes; pruning by id alone
  // misses status flips that should remove the row.
  const reloadTimer = useRef<number | null>(null);
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToMedications(hospitalId, (incoming: MedicationResponse) => {
      // STAT push: flash a banner the nurse cannot miss. Suppress
      // when the incoming order was prescribed by the current user —
      // they already saw it on their own screen.
      if (incoming?.status === 'PRESCRIBED'
          && incoming.priority === 'STAT'
          && (!userId || incoming.prescribedById !== userId)) {
        setStatBanner(incoming);
      }
      if (reloadTimer.current) window.clearTimeout(reloadTimer.current);
      reloadTimer.current = window.setTimeout(() => load(), 250);
    });
    return () => unsub();
  }, [hospitalId, userId, load]);

  // ─── Actions ─────────────────────────────────────────────────────

  const callAction = useCallback(async (
    medId: string,
    action: 'administer' | 'hold' | 'refuse',
    reason?: string,
  ) => {
    setActionErr(null);
    try {
      if (action === 'administer') {
        await medicationApi.administer(medId, {
          medicationId: medId,
          administeredByName: user?.fullName ?? 'Nurse',
        });
      } else if (action === 'hold') {
        if (!reason) return;
        await medicationApi.hold(medId, reason);
      } else if (action === 'refuse') {
        if (!reason) return;
        await medicationApi.refuse(medId, reason);
      }
      // WebSocket will re-push, but call load() defensively so the
      // UI reflects the change even if the broadcast is lost.
      await load();
    } catch (e) {
      // Surface backend ClinicalBusinessException — especially the
      // separation-of-duties message ("the clinician who prescribed
      // this cannot also record administration").
      const message = e instanceof Error ? e.message : 'Action failed';
      setActionErr(message);
    }
  }, [user, load]);

  // ─── Render ──────────────────────────────────────────────────────

  const stat = useMemo(() => queue.filter((m) => m.priority === 'STAT'), [queue]);
  const urgent = useMemo(() => queue.filter((m) => m.priority === 'URGENT'), [queue]);
  const routine = useMemo(() => queue.filter((m) => m.priority === 'ROUTINE' || !m.priority), [queue]);

  const onRequestReason = (medId: string, action: 'hold' | 'refuse') => {
    setReasonText('');
    setReasonModal({ medId, action });
  };

  return (
    <div className={embedded ? '' : 'min-h-full'}>
      <div className={embedded ? 'space-y-4 animate-fade-in' : 'p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in'}>

        {/* Header — own-page only; embedded in the Med Board it inherits the board's chrome */}
        {!embedded && (
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center flex-shrink-0">
              <Pill className="w-5 h-5 text-cyan-300" />
            </div>
            <div className="min-w-0">
              <h1 className="text-lg font-bold text-white">
                Medication Queue
              </h1>
              <p className="text-sm text-white/50">
                Prescribed medications awaiting administration across the hospital. Sorted STAT first.
              </p>
            </div>
            <div className="ml-auto flex items-center gap-2">
              <button
                type="button"
                onClick={() => load()}
                disabled={refreshing}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-white/10 text-white hover:bg-white/20 disabled:opacity-50"
              >
                {refreshing ? <Loader2 className="w-3 h-3 animate-spin" /> : <RefreshCw className="w-3 h-3" />}
                Refresh
              </button>
            </div>
          </div>
        </div>
        )}

        {/* STAT toast */}
        {statBanner && (
          <div className="rounded-2xl bg-red-500/20 border border-red-500/30 p-3 flex items-start gap-3 animate-fade-up">
            <ShieldAlert className="w-5 h-5 text-red-400 flex-shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-extrabold text-red-300">
                STAT order: {statBanner.drugName}{statBanner.dose ? ` ${statBanner.dose}` : ''}
              </p>
              <p className="text-[11px] text-red-300/90 mt-0.5">
                Prescribed by {statBanner.prescribedByName ?? 'unknown'} ·
                {' '}{formatDistanceToNow(new Date(statBanner.prescribedAt))} ago.
                Give immediately — 10 min SLA.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setStatBanner(null)}
              className="text-red-400 hover:text-red-300"
              aria-label="Dismiss"
            >
              <XCircle className="w-5 h-5" />
            </button>
          </div>
        )}

        {/* Error */}
        {err && (
          <div className="rounded-lg bg-red-500/20 border border-red-500/30 px-3 py-2 text-[11px] text-red-300 inline-flex items-start gap-2">
            <AlertTriangle className="w-3.5 h-3.5 mt-0.5 flex-shrink-0" />
            <span>{err}</span>
          </div>
        )}

        {/* Action error — in-app, replaces the old window.alert */}
        {actionErr && (
          <div className="rounded-lg bg-red-500/20 border border-red-500/30 px-3 py-2 text-[11px] text-red-300 flex items-start gap-2">
            <AlertTriangle className="w-3.5 h-3.5 mt-0.5 flex-shrink-0" />
            <span className="flex-1">{actionErr}</span>
            <button type="button" onClick={() => setActionErr(null)} className="text-red-400 hover:text-red-300" aria-label="Dismiss">
              <XCircle className="w-4 h-4" />
            </button>
          </div>
        )}

        {/* Loading */}
        {loading && queue.length === 0 && (
          <div className={`text-sm ${text.muted} inline-flex items-center gap-2`}>
            <Loader2 className="w-4 h-4 animate-spin" /> Loading queue…
          </div>
        )}

        {/* Empty state */}
        {!loading && queue.length === 0 && (
          <div className="rounded-xl p-8 text-center" style={glassCard}>
            <CheckCircle2 className={`w-10 h-10 mx-auto mb-2 ${isDark ? 'text-emerald-400' : 'text-emerald-500'}`} />
            <p className={`text-sm font-bold ${text.heading}`}>Queue is clear</p>
            <p className={`text-xs ${text.muted} mt-1`}>
              No medications awaiting administration. New prescriptions appear here in real time.
            </p>
          </div>
        )}

        {/* Sections */}
        {stat.length > 0 && (
          <PriorityGroup
            title="STAT"
            subtitle="Give immediately — 10-minute SLA"
            tint="red"
            rows={stat}
            onAction={callAction}
            onRequestReason={onRequestReason}
            onOpenVisit={(visitId) => navigate(`/visit/${visitId}`)}
            currentUserId={userId}
          />
        )}
        {urgent.length > 0 && (
          <PriorityGroup
            title="Urgent"
            subtitle="Give within 30 minutes"
            tint="orange"
            rows={urgent}
            onAction={callAction}
            onRequestReason={onRequestReason}
            onOpenVisit={(visitId) => navigate(`/visit/${visitId}`)}
            currentUserId={userId}
          />
        )}
        {routine.length > 0 && (
          <PriorityGroup
            title="Routine"
            subtitle="Per scheduled frequency"
            tint="emerald"
            rows={routine}
            onAction={callAction}
            onRequestReason={onRequestReason}
            onOpenVisit={(visitId) => navigate(`/visit/${visitId}`)}
            currentUserId={userId}
          />
        )}

        {/* Reason capture — in-app modal, replaces window.prompt for hold/refuse */}
        {reasonModal && (
          <div
            className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
            style={{ background: 'var(--modal-backdrop)' }}
            onClick={() => setReasonModal(null)}
          >
            <div
              className="w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in"
              style={glassCard}
              onClick={(e) => e.stopPropagation()}
            >
              <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4 flex items-center justify-between">
                <h2 className="text-sm font-bold text-white">
                  {reasonModal.action === 'hold' ? 'Hold medication' : 'Record refusal'}
                </h2>
                <button type="button" onClick={() => setReasonModal(null)} aria-label="Close" className="text-white/70 hover:text-white">
                  <XCircle className="w-4 h-4" />
                </button>
              </div>
              <div className="p-5 space-y-3">
                <label className={`text-xs font-semibold ${text.label}`}>
                  {reasonModal.action === 'hold'
                    ? 'Hold reason (e.g. NPO before procedure, awaiting labs)'
                    : 'Refusal reason (patient declined / unable to take)'}
                </label>
                <textarea
                  value={reasonText}
                  onChange={(e) => setReasonText(e.target.value)}
                  rows={3}
                  autoFocus
                  placeholder="Minimum 3 characters"
                  className={`w-full px-3 py-2 rounded-xl text-sm ${text.body} focus:outline-none focus:ring-2 focus:ring-cyan-500/20`}
                  style={glassInner}
                />
                <div className="flex items-center justify-end gap-2 pt-2" style={{ borderTop: borderStyle }}>
                  <button type="button" onClick={() => setReasonModal(null)} className={`text-xs font-semibold ${text.body} hover:text-cyan-400 px-3 py-2`}>
                    Cancel
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      const r = reasonText.trim();
                      if (r.length >= 3) { void callAction(reasonModal.medId, reasonModal.action, r); setReasonModal(null); }
                    }}
                    disabled={reasonText.trim().length < 3}
                    className="inline-flex items-center gap-1.5 text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 px-4 py-2 rounded-xl transition-colors disabled:opacity-50"
                  >
                    Confirm
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* ════════════════════════════════════════════════════════════════════
   PriorityGroup — one section of the queue (STAT / URGENT / ROUTINE).
   ════════════════════════════════════════════════════════════════════ */

function PriorityGroup({
  title, subtitle, tint, rows, onAction, onRequestReason, onOpenVisit, currentUserId,
}: {
  title: string;
  subtitle: string;
  tint: 'red' | 'orange' | 'emerald';
  rows: MedicationResponse[];
  onAction: (id: string, action: 'administer' | 'hold' | 'refuse', reason?: string) => void;
  onRequestReason: (id: string, action: 'hold' | 'refuse') => void;
  onOpenVisit: (visitId: string) => void;
  currentUserId: string | undefined;
}) {
  const { text } = useTheme();
  const tintClasses =
    tint === 'red'    ? { header: 'text-red-400', dot: 'bg-red-500' } :
    tint === 'orange' ? { header: 'text-orange-400', dot: 'bg-orange-500' } :
                        { header: 'text-emerald-400', dot: 'bg-emerald-500' };

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span className={`inline-block w-2.5 h-2.5 rounded-full ${tintClasses.dot}`} />
        <h2 className={`text-xs font-extrabold uppercase tracking-wider ${tintClasses.header}`}>
          {title}
        </h2>
        <span className={`text-[10px] ${text.muted}`}>— {subtitle}</span>
        <span className={`ml-auto text-[10px] font-bold ${text.muted}`}>{rows.length}</span>
      </div>

      <ul className="space-y-1.5">
        {rows.map((med) => (
          <MedRow
            key={med.id}
            med={med}
            onAction={onAction}
            onRequestReason={onRequestReason}
            onOpenVisit={onOpenVisit}
            currentUserId={currentUserId}
          />
        ))}
      </ul>
    </div>
  );
}

/* ════════════════════════════════════════════════════════════════════
   MedRow — one queued medication.
   ════════════════════════════════════════════════════════════════════ */

function MedRow({
  med, onAction, onRequestReason, onOpenVisit, currentUserId,
}: {
  med: MedicationResponse;
  onAction: (id: string, action: 'administer' | 'hold' | 'refuse', reason?: string) => void;
  onRequestReason: (id: string, action: 'hold' | 'refuse') => void;
  onOpenVisit: (visitId: string) => void;
  currentUserId: string | undefined;
}) {
  const { glassInner, text } = useTheme();
  const meta = priorityMeta(med.priority);
  const sla = slaInfo(med);

  // Dark-aware dose-state pill — preserves the same semantic hue as the
  // light-only MEDICATION_PRIORITIES tint/overdueTint (red / orange /
  // emerald, escalating on overdue) but in the glass-surface palette.
  const priority = med.priority ?? 'ROUTINE';
  // Clean glass row with a semantic LEFT-accent bar for at-a-glance priority
  // (red=STAT, orange=URGENT, emerald=ROUTINE) instead of a fully-saturated
  // colored block. Overdue rows escalate to a red bar + red ring so the
  // safety signal stays unmissable.
  const hueRgb =
    priority === 'STAT' ? '239,68,68'
    : priority === 'URGENT' ? '249,115,22'
    : '16,185,129';
  const barRgb = sla.isOverdue ? '239,68,68' : hueRgb;

  // Front-end-side guard: warn the prescriber up-front instead of
  // letting them click and bounce off the backend 400. Backend
  // remains authoritative.
  const isOwnPrescription = currentUserId != null && med.prescribedById === currentUserId;

  const overrideBadges = [
    med.prescribedDespiteAllergy ? 'Allergy override' : null,
    med.prescribedDespiteInteraction ? 'Interaction override' : null,
  ].filter(Boolean);

  return (
    <li
      className={`rounded-xl p-3 ${text.body} ${sla.isOverdue ? 'ring-1 ring-red-500/40' : ''}`}
      style={{ ...glassInner, borderLeft: `4px solid rgba(${barRgb},0.75)` }}
    >
      <div className="flex items-start gap-3">
        <div
          className="flex-1 min-w-0 cursor-pointer"
          role="button"
          tabIndex={0}
          onClick={() => onOpenVisit(med.visitId)}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpenVisit(med.visitId); } }}
          title="Open patient chart"
        >
          {/* WHO + WHERE first — a nurse must know the patient and location
              before the drug, so the row is actionable without leaving it. */}
          <PatientContextLine
            patientName={med.patientName}
            zone={med.zone}
            bedLabel={med.bedLabel}
            visitNumber={med.visitNumber}
            className={`text-[11px] ${text.heading} mb-1`}
          />
          <div className="flex items-center gap-2 flex-wrap">
            <span className={`text-sm font-bold ${text.heading}`}>{med.drugName}</span>
            {med.dose && <span className={`text-sm ${text.body}`}>— {med.dose}</span>}
            {med.route && (
              <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg bg-white/10 border border-current">
                {med.route}
              </span>
            )}
            {med.priority && med.priority !== 'ROUTINE' && (
              <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-lg bg-white/10 border border-current">
                {meta.label}
              </span>
            )}
          </div>

          {med.frequency && (
            <div className="text-[11px] mt-0.5 opacity-90">{med.frequency}</div>
          )}

          <div className="text-[10px] mt-1 opacity-80 flex items-center gap-2 flex-wrap">
            <Clock className="w-3 h-3" />
            <span>
              {sla.elapsedMin} min since prescribed
              {sla.isOverdue && (
                <span className="ml-1 font-extrabold uppercase">
                  · {sla.overdueBy} min over SLA
                </span>
              )}
            </span>
            <span>·</span>
            <span>By {med.prescribedByName ?? 'unknown'}</span>
          </div>

          {overrideBadges.length > 0 && (
            <div className="text-[10px] mt-1 opacity-90 inline-flex items-center gap-1.5 flex-wrap">
              <ShieldAlert className="w-3 h-3" />
              {overrideBadges.map((b) => (
                <span key={b!} className="px-1 py-0.5 rounded-lg bg-white/10 border border-current">
                  {b}
                </span>
              ))}
            </div>
          )}

          {isOwnPrescription && (
            <p className="text-[10px] mt-1 italic opacity-90">
              You prescribed this — separation of duties: a different clinician must administer.
            </p>
          )}
        </div>

        <button
          type="button"
          onClick={() => onOpenVisit(med.visitId)}
          className="text-[10px] inline-flex items-center gap-0.5 opacity-80 hover:opacity-100"
          title="Open visit chart"
        >
          <ExternalLink className="w-3 h-3" />
          Chart
        </button>
      </div>

      {/* Actions */}
      <div className="mt-2 flex items-center gap-1.5 flex-wrap">
        <button
          type="button"
          onClick={() => onAction(med.id, 'administer')}
          disabled={isOwnPrescription}
          title={isOwnPrescription ? 'Separation of duties — a different clinician must administer' : 'Record administration'}
          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-[11px] font-bold bg-cyan-600 hover:bg-cyan-700 text-white disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <CheckCircle2 className="w-3 h-3" /> Administer
        </button>
        <button
          type="button"
          onClick={() => onRequestReason(med.id, 'hold')}
          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-[11px] font-bold bg-amber-500/20 text-amber-300 border border-amber-500/30 hover:bg-amber-500/30"
        >
          <Pause className="w-3 h-3" /> Hold
        </button>
        <button
          type="button"
          onClick={() => onRequestReason(med.id, 'refuse')}
          className="inline-flex items-center gap-1 px-2.5 py-1.5 rounded-xl text-[11px] font-bold bg-red-500/20 text-red-300 border border-red-500/30 hover:bg-red-500/30"
        >
          <XCircle className="w-3 h-3" /> Refuse
        </button>
      </div>
    </li>
  );
}
