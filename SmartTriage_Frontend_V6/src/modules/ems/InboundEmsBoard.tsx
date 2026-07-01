/* ═══════════════════════════════════════════════════════════════
   Inbound EMS Board — the ambulance-CASE lifecycle widget.

   One card per inbound ambulance case, showing the WHOLE lifecycle as a
   live stepper so the dashboard always reflects exactly where the patient
   is, and offering ONE reliable action for the case's current stage:

     En route  → [Mark at door]        (confirm-arrival)
     At door   → [Acknowledge receipt]  (acknowledge-arrival)  ← clears the alert too
     Received  → [Complete handover]    (transfer-of-care, read-back)
     Handed off / Cancelled → the card RESOLVES (drops off the board).

   Acuity-split routing is shown on each card: RED/ORANGE → their treatment
   zone (Resus/Acute); YELLOW/GREEN/BLUE → the triage-desk queue.

   The stage is server-derived (EmsRun.lifecycleStage) so every surface — the
   card, the chart, the Alert Center — agrees on one source of truth.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Siren, ChevronUp, ChevronDown, MapPin, Clock, ClipboardCheck,
  CheckCircle2, Send, Loader2, AlertOctagon, ArrowRight,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { emsApi } from '@/api/ems';
import type { EmsRun, EmsLifecycleStage, FieldTriageCategory } from '@/api/ems';
import { subscribeToEmsRuns } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { PatientContextLine } from '@/components/PatientContextLine';
import { chartPath } from '@/lib/chartNav';
import { formatDistanceToNow } from 'date-fns';
import { TransferOfCareModal } from './TransferOfCareModal';

function triageColor(c: FieldTriageCategory | null | undefined): string {
  switch (c) {
    case 'RED':    return 'bg-rose-500';
    case 'ORANGE': return 'bg-amber-500';
    case 'YELLOW': return 'bg-yellow-500';
    case 'GREEN':  return 'bg-emerald-500';
    case 'BLUE':   return 'bg-blue-500';
    default:       return 'bg-slate-500';
  }
}

// The lifecycle steps shown on the board (DISPATCHED lands here as EN_ROUTE once the
// pre-arrival is sent; HANDED_OFF/CANCELLED resolve the card so they're the terminal rung).
const STEPS: { key: EmsLifecycleStage; label: string }[] = [
  { key: 'EN_ROUTE', label: 'En route' },
  { key: 'AT_DOOR', label: 'At door' },
  { key: 'RECEIVED', label: 'Received' },
  { key: 'HANDED_OFF', label: 'Handover' },
];
const STEP_INDEX: Record<string, number> = { EN_ROUTE: 0, AT_DOOR: 1, RECEIVED: 2, HANDED_OFF: 3 };

/** Friendly label for the acuity-split routing target badge. */
function routingLabel(target: string | null): string | null {
  if (!target) return null;
  if (target === 'TRIAGE_QUEUE') return 'Triage queue';
  return target.charAt(0) + target.slice(1).toLowerCase(); // RESUS → Resus
}

function ts(iso: string | null): string | null {
  if (!iso) return null;
  try { return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }
  catch { return null; }
}

export function InboundEmsBoard() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const nurseName = user?.fullName ?? '';
  // The board is mounted for ED staff, but only NURSE/DOCTOR/SUPER_ADMIN may drive the
  // case forward (a hospital admin views but does not action) — mirrors the backend authz.
  const canAct = ['NURSE', 'DOCTOR', 'SUPER_ADMIN'].includes(user?.role ?? '');

  const [runs, setRuns] = useState<EmsRun[]>([]);
  const [expanded, setExpanded] = useState(true);
  const [transferTarget, setTransferTarget] = useState<EmsRun | null>(null);
  // Per-card in-flight guards + last action error, so a click always produces a visible
  // effect (spinner → resolve, or an inline error) — never a silent no-op. A SET (not a
  // single id) so actioning one ambulance never locks the buttons on another — a
  // receiving clinician must be able to work two arrivals at once (mass-casualty).
  const [busyIds, setBusyIds] = useState<Set<string>>(new Set());
  const [actionError, setActionError] = useState<string | null>(null);
  // Distinguish a FAILED fetch from a genuinely empty board — otherwise a
  // 403/network error self-hides exactly like "no ambulances coming", which on
  // a life-critical board is a dangerous false reassurance.
  const [error, setError] = useState<string | null>(null);
  const wsGen = useWebSocketGeneration();

  const load = useCallback(async () => {
    if (!hospitalId) return;
    try {
      const data = await emsApi.getInbound(hospitalId);
      setRuns(data || []);
      setError(null);
    } catch (err) {
      console.error('[InboundEmsBoard] load failed:', err);
      setError('Inbound ambulance board unavailable — could not reach the server.');
    }
  }, [hospitalId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToEmsRuns(hospitalId, () => load());
    return () => unsub();
  }, [hospitalId, load, wsGen]);

  // One guarded action runner: spinner while in flight, re-load on success, inline error
  // on failure. This is what makes the card's buttons reliable (the reported "click does
  // nothing"): the outcome is always visible.
  const runAction = useCallback(async (run: EmsRun, fn: () => Promise<unknown>) => {
    if (busyIds.has(run.id)) return; // single-flight PER CARD only
    setBusyIds((s) => { const n = new Set(s); n.add(run.id); return n; });
    setActionError(null);
    try {
      await fn();
      await load();
    } catch (e: any) {
      setActionError(e?.message || 'That action could not be completed — please retry.');
    } finally {
      setBusyIds((s) => { const n = new Set(s); n.delete(run.id); return n; });
    }
  }, [busyIds, load]);

  if (runs.length === 0) {
    // Genuinely no inbound ambulances → stay hidden (unchanged behavior).
    if (!error) return null;
    // Fetch failed → surface it instead of masquerading as "all clear".
    return (
      <div className="rounded-2xl bg-rose-500/10 border border-rose-400/40 text-rose-700 px-4 py-3 flex items-center gap-3">
        <Siren className="w-5 h-5 flex-shrink-0 text-rose-500" />
        <span className="text-sm font-medium flex-1">{error}</span>
        <button
          onClick={() => load()}
          className="px-3 py-1.5 rounded-xl bg-rose-500 text-white hover:bg-rose-600 text-xs font-bold"
        >
          Retry
        </button>
      </div>
    );
  }

  // Arrived cases (at door / received) need action soonest → list them first.
  const arrived = runs.filter((r) => r.status === 'ARRIVED');
  const enRoute = runs.filter((r) => r.status === 'EN_ROUTE');
  const ordered = [...arrived, ...enRoute];

  return (
    <>
      <div className="rounded-2xl bg-gradient-to-r from-amber-600 to-amber-500 text-white shadow-lg ring-2 ring-amber-300/40 overflow-hidden">
        <div className="px-5 py-4">
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-white/20 flex items-center justify-center">
                <Siren className="w-5 h-5" />
              </div>
              <div>
                <div className="text-sm font-bold">
                  {arrived.length > 0 && `${arrived.length} at door`}
                  {arrived.length > 0 && enRoute.length > 0 && ' • '}
                  {enRoute.length > 0 && `${enRoute.length} en route`}
                </div>
                <div className="text-[11px] text-white/80">
                  Ambulance cases — advance each through its lifecycle to handover.
                </div>
              </div>
            </div>
            <button
              onClick={() => setExpanded((e) => !e)}
              className="px-3 py-1.5 rounded-xl bg-white/15 hover:bg-white/25 text-xs font-bold inline-flex items-center gap-1"
            >
              {expanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
              {expanded ? 'Collapse' : 'Show'}
            </button>
          </div>

          {actionError && (
            <div className="mt-3 flex items-center gap-2 rounded-xl bg-white text-rose-700 px-3 py-2 text-xs font-semibold">
              <AlertOctagon className="w-4 h-4 flex-shrink-0" />
              <span className="flex-1">{actionError}</span>
              <button onClick={() => setActionError(null)} className="text-rose-400 hover:text-rose-600 font-bold">✕</button>
            </div>
          )}

          {expanded && (
            <div className="mt-3 space-y-2">
              {ordered.map((run) => (
                <CaseCard
                  key={run.id}
                  run={run}
                  canAct={canAct}
                  busy={busyIds.has(run.id)}
                  onOpenChart={() => { if (run.visitId) navigate(chartPath(run.visitId)); }}
                  onMarkAtDoor={() => runAction(run, () => emsApi.confirmArrival(run.id))}
                  onAcknowledgeReceipt={() => runAction(run, () => emsApi.acknowledgeArrival(run.id))}
                  onCompleteHandover={() => setTransferTarget(run)}
                />
              ))}
            </div>
          )}
        </div>
      </div>

      {transferTarget && (
        <TransferOfCareModal
          run={transferTarget}
          receivedByName={nurseName}
          onClose={() => setTransferTarget(null)}
          onSaved={() => { setTransferTarget(null); load(); }}
        />
      )}
    </>
  );
}

// ─────────────────────────────────────────────────────────────────
// One ambulance case card — lifecycle stepper + stage-appropriate action
// ─────────────────────────────────────────────────────────────────

interface CaseCardProps {
  run: EmsRun;
  canAct: boolean;
  busy: boolean;
  onOpenChart: () => void;
  onMarkAtDoor: () => void;
  onAcknowledgeReceipt: () => void;
  onCompleteHandover: () => void;
}

function CaseCard({
  run, canAct, busy, onOpenChart, onMarkAtDoor, onAcknowledgeReceipt, onCompleteHandover,
}: CaseCardProps) {
  const stage = run.lifecycleStage;
  const currentIndex = STEP_INDEX[stage] ?? 0;
  const routing = routingLabel(run.routingTarget);
  // Badge prominence tracks ACUITY (RED/ORANGE), not the routing string — so a YELLOW/GREEN
  // patient placed into GENERAL/OBSERVATION by formal triage keeps its low-acuity muted badge
  // instead of flipping to the amber treatment-zone style used for Resus/Acute arrivals.
  const routingIsHighAcuity = run.fieldTriageCategory === 'RED' || run.fieldTriageCategory === 'ORANGE';

  // Timestamp shown under each completed/active step (best-effort).
  const stepTime = (key: EmsLifecycleStage): string | null => {
    switch (key) {
      case 'EN_ROUTE': return null;
      case 'AT_DOOR': return ts(run.edArrivedAt);
      case 'RECEIVED': return ts(run.arrivalAckedAt);
      case 'HANDED_OFF': return ts(run.handedOffAt);
      default: return null;
    }
  };

  return (
    <div
      onClick={run.visitId ? onOpenChart : undefined}
      className={`rounded-xl bg-white/10 px-3 py-2.5 ${run.visitId ? 'cursor-pointer hover:bg-white/15' : ''}`}
    >
      {/* Who + acuity + lights + stage */}
      <div className="flex items-start gap-3">
        <div className={`w-2 h-2 rounded-full mt-1.5 shrink-0 ${triageColor(run.fieldTriageCategory)}`} />
        <div className="flex-1 min-w-0">
          <PatientContextLine
            patientName={run.patientName}
            visitNumber={run.visitNumber}
            className="text-[11px] text-white"
          />
          <div className="flex items-center gap-2 flex-wrap mt-0.5">
            <span className="text-sm font-bold truncate">{run.mechanism ?? 'Patient'}</span>
            {run.fieldTriageCategory && (
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-white/20">
                {run.fieldTriageCategory}{run.fieldTewsScore != null ? ` · TEWS ${run.fieldTewsScore}` : ''}
              </span>
            )}
            {run.lightsActive && (
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-white text-rose-600 inline-flex items-center gap-1">
                <Siren className="w-3 h-3 animate-pulse" /> LIGHTS
              </span>
            )}
            {/* Acuity-split routing badge */}
            {routing && (
              <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded inline-flex items-center gap-1 ${routingIsHighAcuity ? 'bg-white text-amber-700' : 'bg-white/20'}`}>
                <ArrowRight className="w-3 h-3" /> {routing}
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Lifecycle stepper — the dashboard always shows exactly where the case is. */}
      <div className="mt-2.5 flex items-center">
        {STEPS.map((step, i) => {
          const done = i < currentIndex;
          const active = i === currentIndex;
          const time = (done || active) ? stepTime(step.key) : null;
          return (
            <div key={step.key} className="flex items-center flex-1 last:flex-none">
              <div className="flex flex-col items-center">
                <div className={`w-5 h-5 rounded-full flex items-center justify-center text-[9px] font-bold ${
                  done ? 'bg-white text-amber-600'
                    : active ? 'bg-white text-amber-700 ring-2 ring-white/70'
                    : 'bg-white/25 text-white/70'}`}>
                  {done ? <CheckCircle2 className="w-3.5 h-3.5" /> : i + 1}
                </div>
                <span className={`mt-1 text-[9px] font-semibold leading-none whitespace-nowrap ${active ? 'text-white' : 'text-white/70'}`}>
                  {step.label}
                </span>
                <span className="mt-0.5 text-[8px] text-white/60 leading-none h-2">{time ?? ''}</span>
              </div>
              {i < STEPS.length - 1 && (
                <div className={`h-0.5 flex-1 mx-1 -mt-4 ${i < currentIndex ? 'bg-white' : 'bg-white/25'}`} />
              )}
            </div>
          );
        })}
      </div>

      {/* ED-triage countdown — proactive: the nurse sees the clock, not just an after-the-fact
          alert. Shows only while the patient is arrived + still awaiting ED triage
          (edRetriageDueAt is cleared by the re-triage monitor once a triage is filed). */}
      {run.status === 'ARRIVED' && run.edRetriageDueAt && (
        <div className="mt-2">
          <RetriageCountdown dueAt={run.edRetriageDueAt} />
        </div>
      )}

      {/* Vitals + context */}
      <div className="text-[11px] text-white/85 mt-2.5 flex flex-wrap gap-x-3 gap-y-0.5">
        {run.incidentLocation && <span className="flex items-center gap-1"><MapPin className="w-3 h-3" /> {run.incidentLocation}</span>}
        {run.etaMinutes != null && run.status === 'EN_ROUTE' && <span className="flex items-center gap-1"><Clock className="w-3 h-3" /> ETA {run.etaMinutes} min</span>}
        {run.fieldSbp != null && <span>BP {run.fieldSbp}/{run.fieldDbp ?? '—'}</span>}
        {run.fieldSpo2 != null && <span>SpO₂ {run.fieldSpo2}%</span>}
        {run.fieldGcs != null && <span>GCS {run.fieldGcs}</span>}
      </div>
      <div className="text-[10px] text-white/70 mt-0.5">
        {run.paramedicName ?? 'Paramedic'} • {(run.interventions?.length ?? 0)} interventions logged
        {run.arrivalAckedAt && run.arrivalAckedByName && (
          <> • received by {run.arrivalAckedByName}{run.arrivalAckedAt ? ` ${formatDistanceToNow(new Date(run.arrivalAckedAt), { addSuffix: true })}` : ''}</>
        )}
      </div>
      {run.notes && <div className="text-[11px] text-white/90 mt-0.5 italic">“{run.notes}”</div>}

      {/* Stage-appropriate action — exactly ONE clear next step. */}
      {canAct && (
        <div className="mt-2.5 flex justify-end">
          {run.status === 'EN_ROUTE' && (
            <ActionButton busy={busy} onClick={onMarkAtDoor} icon={<CheckCircle2 className="w-3.5 h-3.5" />}>
              Mark at door
            </ActionButton>
          )}
          {stage === 'AT_DOOR' && (
            <ActionButton busy={busy} onClick={onAcknowledgeReceipt} icon={<ClipboardCheck className="w-3.5 h-3.5" />}>
              Acknowledge receipt
            </ActionButton>
          )}
          {stage === 'RECEIVED' && (
            <ActionButton busy={busy} onClick={onCompleteHandover} icon={<Send className="w-3.5 h-3.5" />}>
              Complete handover
            </ActionButton>
          )}
        </div>
      )}
    </div>
  );
}

/** Live ED-triage countdown: "ED triage due in M:SS" → "ED TRIAGE OVERDUE M:SS" (pulsing red). */
function RetriageCountdown({ dueAt }: { dueAt: string }) {
  const [now, setNow] = useState<number>(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const due = new Date(dueAt).getTime();
  if (Number.isNaN(due)) return null;
  const diffMs = due - now;
  const overdue = diffMs <= 0;
  const secs = Math.floor(Math.abs(diffMs) / 1000);
  const label = `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`;
  return (
    <span className={`inline-flex items-center gap-1 text-[10px] font-bold px-1.5 py-0.5 rounded ${
      overdue ? 'bg-rose-600 text-white animate-pulse' : 'bg-white/20 text-white'}`}>
      <Clock className="w-3 h-3" />
      {overdue ? `ED TRIAGE OVERDUE ${label}` : `ED triage due in ${label}`}
    </span>
  );
}

function ActionButton({
  children, icon, busy, onClick,
}: { children: ReactNode; icon: ReactNode; busy: boolean; onClick: () => void }) {
  return (
    <button
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      disabled={busy}
      className="shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-white text-amber-700 hover:bg-white/90 disabled:opacity-60 text-[11px] font-bold"
    >
      {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : icon}
      {children}
    </button>
  );
}
