/* ════════════════════════════════════════════════════════════════════
   MedicationBoard (V67) — the nurse's dose-level medication surface.

   One screen to run a zone's medication workload:
     • OVERDUE / DUE NOW / UPCOMING — every open scheduled or one-time
       dose, sorted by due time, with administer / delay / refuse
       actions. Administer runs the full server-side gate stack
       (dose verification, witness, allergy recheck, safety block,
       separation of duties).
     • PRN — live PRN orders with usage-vs-cap and a quick-give flow
       that records the triggering indication; the backend enforces
       min-interval, 24h cap and the structured vitals gate
       (fail-closed; override-with-justification supported).
     • INFUSIONS — live continuous orders: confirm initiation, change
       rate, stop (each an audited event).
     • APPROVALS — high-alert orders awaiting the charge nurse.

   Zone targeting mirrors the clinical dashboards (useScopedView):
   charge / shift-lead / admin see the whole hospital with a zone
   filter; an on-shift zone nurse sees exactly their zone; off-shift
   shows the restriction card. Real-time: subscribes to the zone (or
   hospital) medication topic and refetches on any dose/order event.
   ════════════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Pill, Clock, AlertTriangle, Loader2, RefreshCw, ExternalLink,
  CheckCircle2, Droplet, ShieldAlert, ShieldCheck, Timer, XCircle,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { PatientContextLine } from '@/components/PatientContextLine';
import { medicationApi } from '@/api/medications';
import { subscribeToMedications, subscribeToZoneMedications } from '@/api/websocket';
import { useScopedView } from '@/hooks/useScopedView';
import { NurseMedicationQueue } from './NurseMedicationQueue';
import { useTheme } from '@/hooks/useTheme';
import type {
  EdZone, MedicationDoseResponse, MedicationOrderAudit,
  MedicationResponse, ZoneMedicationBoard,
} from '@/api/types';
import { formatDistanceToNow } from 'date-fns';

const ZONES: EdZone[] = [
  'RESUS', 'ACUTE', 'GENERAL', 'TRIAGE', 'OBSERVATION',
  'ISOLATION', 'PEDIATRIC', 'NEONATAL', 'AMBULATORY',
];

type ModalKind =
  | { kind: 'administer'; dose: MedicationDoseResponse }
  | { kind: 'delay'; dose: MedicationDoseResponse }
  | { kind: 'refuse'; dose: MedicationDoseResponse }
  | { kind: 'prn'; entry: MedicationOrderAudit }
  | { kind: 'inf-start'; entry: MedicationOrderAudit }
  | { kind: 'inf-rate'; entry: MedicationOrderAudit }
  | { kind: 'inf-stop'; entry: MedicationOrderAudit }
  | { kind: 'approve'; order: MedicationResponse };

function fmtDose(d: MedicationDoseResponse): string {
  if (d.doseValue != null) return `${d.doseValue} ${d.doseUnit ?? ''}`.trim();
  return d.orderDose ?? '';
}

function fmtOrderDose(o: MedicationResponse): string {
  if (o.doseValue != null) return `${o.doseValue} ${o.doseUnit ?? ''}`.trim();
  return o.dose ?? '';
}

/** Latest infusion event of an order's timeline (or null). */
function latestInfusionEvent(entry: MedicationOrderAudit): MedicationDoseResponse | null {
  const events = entry.doses.filter((d) =>
    d.kind === 'INFUSION_START' || d.kind === 'INFUSION_RATE_CHANGE' || d.kind === 'INFUSION_STOP');
  return events.length > 0 ? events[events.length - 1] : null;
}

function isInfusionRunning(entry: MedicationOrderAudit): boolean {
  const last = latestInfusionEvent(entry);
  return last != null && last.kind !== 'INFUSION_STOP';
}

export function MedicationBoard() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const userName = user?.fullName ?? '';
  const navigate = useNavigate();
  const scope = useScopedView();

  const [activeTab, setActiveTab] = useState<'board' | 'orders'>('board');
  const [board, setBoard] = useState<ZoneMedicationBoard | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  /** Charge/admin-only manual zone filter (null = whole hospital). */
  const [zoneFilter, setZoneFilter] = useState<EdZone | null>(null);
  /** Multi-zone nurse's selected covered zone (null = their primary zone). */
  const [zoneSel, setZoneSel] = useState<EdZone | null>(null);

  // The zone the BACKEND query uses:
  //  • ZONE_SCOPED nurse → a zone they COVER (primary ∪ additionalZones); the
  //    selector below lets a multi-zone nurse switch, defaulting to primary.
  //  • cross-zone roles → the manual filter (or all/hospital-wide when null).
  const effectiveZone: EdZone | null =
    scope.mode === 'ZONE_SCOPED'
      ? (zoneSel && scope.coveredZones.includes(zoneSel) ? zoneSel : scope.zone)
      : zoneFilter;

  // 30s tick so "due in X min" labels advance.
  const [, setNow] = useState(0);
  useEffect(() => {
    const t = window.setInterval(() => setNow((n) => n + 1), 30_000);
    return () => window.clearInterval(t);
  }, []);

  const load = useCallback(async () => {
    if (!hospitalId || scope.isLoading || scope.mode === 'RESTRICTED') return;
    setRefreshing(true);
    setErr(null);
    try {
      const data = await medicationApi.getBoard(hospitalId, effectiveZone);
      setBoard(data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load the medication board');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [hospitalId, effectiveZone, scope.isLoading, scope.mode]);

  useEffect(() => { void load(); }, [load]);

  // Real-time: refetch (debounced) on any medication event for our
  // scope. Zone nurses listen to their zone topic; cross-zone roles
  // listen hospital-wide.
  const reloadTimer = useRef<number | null>(null);
  useEffect(() => {
    if (!hospitalId || scope.isLoading || scope.mode === 'RESTRICTED') return;
    const onEvent = () => {
      if (reloadTimer.current) window.clearTimeout(reloadTimer.current);
      reloadTimer.current = window.setTimeout(() => load(), 300);
    };
    const unsub = scope.mode === 'ZONE_SCOPED' && effectiveZone
      ? subscribeToZoneMedications(hospitalId, effectiveZone, onEvent)
      : subscribeToMedications(hospitalId, onEvent);
    return () => unsub();
  }, [hospitalId, scope.mode, effectiveZone, scope.isLoading, load]);

  // ── Modal-driven actions ──────────────────────────────────────────
  const [modal, setModal] = useState<ModalKind | null>(null);
  const [actionBusy, setActionBusy] = useState(false);
  // Shared modal fields (reset on open).
  const [fReason, setFReason] = useState('');
  const [fMinutes, setFMinutes] = useState('60');
  const [fDoseValue, setFDoseValue] = useState('');
  const [fDoseUnit, setFDoseUnit] = useState('');
  const [fWitness, setFWitness] = useState('');
  const [fIndication, setFIndication] = useState('');
  const [fRate, setFRate] = useState('');
  const [fRateUnit, setFRateUnit] = useState('');
  const [fOverride, setFOverride] = useState(false);
  const [fJustification, setFJustification] = useState('');

  const openModal = useCallback((m: ModalKind) => {
    setFReason(''); setFMinutes('60'); setFWitness(''); setFIndication('');
    setFOverride(false); setFJustification('');
    if (m.kind === 'administer') {
      setFDoseValue(m.dose.doseValue != null ? String(m.dose.doseValue) : '');
      setFDoseUnit(m.dose.doseUnit ?? '');
    } else if (m.kind === 'prn') {
      setFDoseValue(m.entry.order.doseValue != null ? String(m.entry.order.doseValue) : '');
      setFDoseUnit(m.entry.order.doseUnit ?? '');
    } else {
      setFDoseValue(''); setFDoseUnit('');
    }
    if (m.kind === 'inf-start' || m.kind === 'inf-rate') {
      const o = m.entry.order;
      setFRate(o.rateValue != null ? String(o.rateValue) : '');
      setFRateUnit(o.rateUnit ?? 'mL/hr');
    } else {
      setFRate(''); setFRateUnit('');
    }
    setModal(m);
  }, []);

  const runModalAction = useCallback(async () => {
    if (!modal) return;
    setActionBusy(true);
    try {
      switch (modal.kind) {
        case 'administer':
          await medicationApi.administerDose(modal.dose.id, {
            administeredByName: userName || undefined,
            doseValue: fDoseValue ? Number(fDoseValue) : undefined,
            doseUnit: fDoseUnit || undefined,
            witnessName: fWitness || undefined,
            override: fOverride || undefined,
            overrideJustification: fOverride ? fJustification : undefined,
          });
          break;
        case 'delay':
          await medicationApi.delayDose(modal.dose.id, {
            delayMinutes: Number(fMinutes), reason: fReason,
          });
          break;
        case 'refuse':
          await medicationApi.refuseDose(modal.dose.id, {
            reason: fReason, recordedByName: userName || undefined,
          });
          break;
        case 'prn':
          await medicationApi.recordPrnDose(modal.entry.order.id, {
            prnReason: fIndication,
            administeredByName: userName || undefined,
            doseValue: fDoseValue ? Number(fDoseValue) : undefined,
            doseUnit: fDoseUnit || undefined,
            witnessName: fWitness || undefined,
            override: fOverride || undefined,
            overrideJustification: fOverride ? fJustification : undefined,
          });
          break;
        case 'inf-start':
          await medicationApi.startInfusion(modal.entry.order.id, {
            rateValue: fRate ? Number(fRate) : undefined,
            rateUnit: fRateUnit || undefined,
            recordedByName: userName || undefined,
            witnessName: fWitness || undefined,
          });
          break;
        case 'inf-rate':
          await medicationApi.changeInfusionRate(modal.entry.order.id, {
            rateValue: Number(fRate), rateUnit: fRateUnit || undefined,
            recordedByName: userName || undefined, reason: fReason || undefined,
          });
          break;
        case 'inf-stop':
          await medicationApi.stopInfusion(modal.entry.order.id, {
            reason: fReason, recordedByName: userName || undefined,
          });
          break;
        case 'approve':
          await medicationApi.approve(modal.order.id, {
            approvedByName: userName || undefined, note: fReason || undefined,
          });
          break;
      }
      setModal(null);
      await load();
    } catch (e) {
      // Surface the backend's gate message (vitals gate, verification,
      // witness, interval/cap, approval) — the nurse needs the reason.
      // eslint-disable-next-line no-alert
      window.alert(e instanceof Error ? e.message : 'Action failed');
    } finally {
      setActionBusy(false);
    }
  }, [modal, fDoseValue, fDoseUnit, fWitness, fMinutes, fReason, fIndication,
      fRate, fRateUnit, fOverride, fJustification, userName, load]);

  // ── Lanes ─────────────────────────────────────────────────────────
  const nowMs = Date.now();
  const lanes = useMemo(() => {
    const due = board?.dueDoses ?? [];
    const overdue = due.filter((d) => d.dueAt && new Date(d.dueAt).getTime() < nowMs - 60_000);
    const dueNow = due.filter((d) => {
      if (!d.dueAt) return true;
      const t = new Date(d.dueAt).getTime();
      return t >= nowMs - 60_000 && t <= nowMs + 30 * 60_000;
    });
    const upcoming = due.filter((d) => d.dueAt && new Date(d.dueAt).getTime() > nowMs + 30 * 60_000);
    return { overdue, dueNow, upcoming };
  }, [board, nowMs]);

  // ── Scope guards (mirrors clinical dashboards) ───────────────────
  if (scope.isLoading) {
    return (
      <div className="flex items-center justify-center min-h-[40vh]">
        <div className="w-8 h-8 rounded-full border-2 border-slate-400/40 border-t-slate-500 animate-spin" />
      </div>
    );
  }
  // NOTE: the RESTRICTED (off-shift) scope guard is applied inside the Dose
  // Schedule tab only — the New Orders tab stays hospital-wide as the standalone
  // Med Queue was, so a STAT order is never hidden from an off-shift nurse.

  const witnessNeeded =
    (modal?.kind === 'administer' && modal.dose.requiresWitness) ||
    (modal?.kind === 'prn' && !!modal.entry.order.requiresWitness) ||
    (modal?.kind === 'inf-start' && !!modal.entry.order.requiresWitness);

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        {/* ── Header banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center flex-shrink-0">
              <Pill className="w-5 h-5 text-cyan-300" />
            </div>
            <div className="flex-1 min-w-0">
              <h1 className="text-lg font-bold text-white flex items-center gap-2">
                Medication Board
                {scope.mode === 'ZONE_SCOPED' && scope.zone && (
                  <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">
                    {scope.zone} zone
                  </span>
                )}
              </h1>
              <p className="text-sm text-white/50">
                Scheduled doses, PRN, infusions and high-alert approvals — live.
              </p>
            </div>
            <div className="flex items-center gap-2 flex-wrap justify-end">
              {scope.mode === 'HOSPITAL_WIDE' && (
                <div className="flex items-center gap-1 flex-wrap">
                  <button
                    type="button"
                    onClick={() => setZoneFilter(null)}
                    className={`px-2 py-1 rounded-lg text-[10px] font-bold border ${zoneFilter === null
                      ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
                      : 'text-white/70 hover:bg-white/5 border-transparent'}`}
                  >
                    All zones
                  </button>
                  {ZONES.map((z) => (
                    <button
                      key={z}
                      type="button"
                      onClick={() => setZoneFilter(z)}
                      className={`px-2 py-1 rounded-lg text-[10px] font-bold border ${zoneFilter === z
                        ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
                        : 'text-white/70 hover:bg-white/5 border-transparent'}`}
                    >
                      {z}
                    </button>
                  ))}
                </div>
              )}
              {/* Multi-zone nurse: switch between the zones they cover this shift
                  (primary ∪ additionalZones). Single-zone nurses get no selector. */}
              {scope.mode === 'ZONE_SCOPED' && scope.coveredZones.length > 1 && (
                <div className="flex items-center gap-1 flex-wrap">
                  {scope.coveredZones.map((z) => (
                    <button
                      key={z}
                      type="button"
                      onClick={() => setZoneSel(z)}
                      className={`px-2 py-1 rounded-lg text-[10px] font-bold border ${effectiveZone === z
                        ? 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
                        : 'text-white/70 hover:bg-white/5 border-transparent'}`}
                    >
                      {z}
                    </button>
                  ))}
                </div>
              )}
              <button
                type="button"
                onClick={() => load()}
                className="p-2 rounded-xl text-white/70 hover:bg-white/5"
                title="Refresh"
              >
                <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>
        </div>

        {/* ── Tab bar: Dose Schedule | New Orders (the merged Med Queue) ── */}
        <div className="rounded-2xl p-1.5 flex items-center gap-1.5" style={glassInner}>
          {([['board', 'Dose Schedule'], ['orders', 'New Orders']] as const).map(([key, label]) => (
            <button
              key={key}
              type="button"
              onClick={() => setActiveTab(key)}
              className={`inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold rounded-xl transition-all ${
                activeTab === key
                  ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                  : `${text.body} hover:bg-white/5`
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        {/* New Orders tab = the order-level queue (PRESCRIBED, awaiting first administration) */}
        {activeTab === 'orders' && <NurseMedicationQueue embedded />}

      {activeTab === 'board' && (
        scope.mode === 'RESTRICTED' ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <ShieldAlert className="w-10 h-10 mx-auto text-amber-500 mb-3" />
            <h2 className={`text-lg font-bold ${text.heading}`}>No active shift</h2>
            <p className={`text-sm mt-2 ${text.body}`}>
              The dose schedule is zone-scoped — you don't have an active shift
              assignment right now (ask the charge nurse if that's wrong). New orders
              awaiting first administration are still listed under the New Orders tab.
            </p>
          </div>
        ) : (<>
      {err && (
        <div className="rounded-xl bg-red-500/10 border border-red-500/30 p-3 text-sm text-red-400 font-semibold">
          {err}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-7 h-7 animate-spin text-emerald-500" />
        </div>
      ) : (
        <>
          {/* ── Pending approvals (visible to all; actionable per backend rules) ── */}
          {(board?.pendingApproval?.length ?? 0) > 0 && (
            <section className="rounded-2xl p-4" style={glassCard}>
              <h2 className={`text-xs font-bold uppercase tracking-wider mb-3 flex items-center gap-2 ${text.label}`}>
                <ShieldAlert className="w-4 h-4 text-red-500" />
                High-alert — awaiting approval ({board!.pendingApproval.length})
              </h2>
              <div className="space-y-2">
                {board!.pendingApproval.map((o) => (
                  <div key={o.id} className="rounded-xl p-3 flex items-center gap-3 flex-wrap border border-red-400/30" style={glassInner}>
                    <div className="flex-1 min-w-[220px]">
                      <div className={`text-sm font-bold ${text.heading}`}>
                        {o.drugName} {fmtOrderDose(o)} {o.route}
                        <span className="ml-2 inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-red-600" style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}>High alert</span>
                      </div>
                      <PatientContextLine
                        patientName={o.patientName}
                        zone={o.zone}
                        bedLabel={o.bedLabel}
                        visitNumber={o.visitNumber}
                        className={`text-[11px] mt-0.5 ${text.heading}`}
                      />
                      <div className={`text-[11px] ${text.muted}`}>
                        {o.prescriptionType?.replace('_', '-')} · prescribed by {o.prescribedByName}
                        {' '}{formatDistanceToNow(new Date(o.prescribedAt), { addSuffix: true })}
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => openModal({ kind: 'approve', order: o })}
                      className="px-3 py-1.5 rounded-xl text-xs font-bold bg-cyan-600 text-white hover:bg-cyan-700 inline-flex items-center gap-1.5"
                    >
                      <ShieldCheck className="w-3.5 h-3.5" /> Approve
                    </button>
                    <button
                      type="button"
                      onClick={() => navigate(`/visit/${o.visitId}`)}
                      className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`}
                      title="Open visit"
                    >
                      <ExternalLink className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            </section>
          )}

          {/* ── Dose lanes ── */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <DoseLane
              title="Overdue"
              icon={<AlertTriangle className="w-4 h-4 text-red-500" />}
              doses={lanes.overdue}
              tone="red"
              onAdminister={(d) => openModal({ kind: 'administer', dose: d })}
              onDelay={(d) => openModal({ kind: 'delay', dose: d })}
              onRefuse={(d) => openModal({ kind: 'refuse', dose: d })}
              navigate={navigate}
              glassCard={glassCard} glassInner={glassInner} text={text}
            />
            <DoseLane
              title="Due now (next 30 min)"
              icon={<Clock className="w-4 h-4 text-amber-500" />}
              doses={lanes.dueNow}
              tone="amber"
              onAdminister={(d) => openModal({ kind: 'administer', dose: d })}
              onDelay={(d) => openModal({ kind: 'delay', dose: d })}
              onRefuse={(d) => openModal({ kind: 'refuse', dose: d })}
              navigate={navigate}
              glassCard={glassCard} glassInner={glassInner} text={text}
            />
            <DoseLane
              title="Upcoming"
              icon={<Timer className="w-4 h-4 text-sky-500" />}
              doses={lanes.upcoming}
              tone="sky"
              onAdminister={(d) => openModal({ kind: 'administer', dose: d })}
              onDelay={(d) => openModal({ kind: 'delay', dose: d })}
              onRefuse={(d) => openModal({ kind: 'refuse', dose: d })}
              navigate={navigate}
              glassCard={glassCard} glassInner={glassInner} text={text}
            />
          </div>

          {/* ── PRN + infusions ── */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <section className="rounded-2xl p-4" style={glassCard}>
              <h2 className={`text-xs font-bold uppercase tracking-wider mb-3 flex items-center gap-2 ${text.label}`}>
                <Pill className="w-4 h-4 text-violet-500" />
                PRN — as needed ({board?.prnOrders.length ?? 0})
              </h2>
              {(board?.prnOrders.length ?? 0) === 0 ? (
                <p className={`text-xs ${text.muted}`}>No live PRN orders.</p>
              ) : (
                <div className="space-y-2">
                  {board!.prnOrders.map((entry) => {
                    const o = entry.order;
                    const given = entry.doses.filter((d) => d.status === 'GIVEN').length;
                    return (
                      <div key={o.id} className="rounded-xl p-3" style={glassInner}>
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className={`text-sm font-bold ${text.heading}`}>
                            {o.drugName} {fmtOrderDose(o)} {o.route}
                          </span>
                          <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-violet-600" style={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}>PRN {o.prnIndication}</span>
                          {o.gateParameter && (
                            <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-blue-600" style={{ background: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)' }}>
                              Gate: {o.gateParameter} {o.gateComparator === 'GTE' ? '≥' : '≤'} {o.gateThreshold}
                            </span>
                          )}
                          {o.requiresWitness && (
                            <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-amber-600" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>Witness</span>
                          )}
                        </div>
                        <PatientContextLine
                          patientName={o.patientName ?? firstPatientName(entry)}
                          zone={o.zone}
                          bedLabel={o.bedLabel}
                          visitNumber={o.visitNumber}
                          className={`text-[11px] mt-1 ${text.heading}`}
                        />
                        <div className={`text-[11px] mt-0.5 ${text.muted}`}>
                          {given} given
                          {o.prnMaxDosesPerDay != null && ` (max ${o.prnMaxDosesPerDay}/24h)`}
                          {o.prnMinIntervalHours != null && ` · min ${o.prnMinIntervalHours}h apart`}
                        </div>
                        <div className="mt-2 flex items-center gap-2">
                          <button
                            type="button"
                            onClick={() => openModal({ kind: 'prn', entry })}
                            className="px-3 py-1.5 rounded-xl text-xs font-bold bg-cyan-600 text-white hover:bg-cyan-700"
                          >
                            Give PRN dose
                          </button>
                          <button
                            type="button"
                            onClick={() => navigate(`/visit/${o.visitId}`)}
                            className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`}
                            title="Open visit"
                          >
                            <ExternalLink className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </section>

            <section className="rounded-2xl p-4" style={glassCard}>
              <h2 className={`text-xs font-bold uppercase tracking-wider mb-3 flex items-center gap-2 ${text.label}`}>
                <Droplet className="w-4 h-4 text-cyan-500" />
                Continuous infusions ({board?.activeInfusions.length ?? 0})
              </h2>
              {(board?.activeInfusions.length ?? 0) === 0 ? (
                <p className={`text-xs ${text.muted}`}>No live continuous orders.</p>
              ) : (
                <div className="space-y-2">
                  {board!.activeInfusions.map((entry) => {
                    const o = entry.order;
                    const running = isInfusionRunning(entry);
                    const last = latestInfusionEvent(entry);
                    return (
                      <div key={o.id} className="rounded-xl p-3" style={glassInner}>
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className={`text-sm font-bold ${text.heading}`}>
                            {o.drugName}{o.productDetail ? ` (${o.productDetail})` : ''}
                          </span>
                          <span
                            className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${running ? 'text-emerald-600' : 'text-slate-600'}`}
                            style={running
                              ? { background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }
                              : { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                            {running
                              ? `Running @ ${last?.rateValue ?? o.rateValue} ${last?.rateUnit ?? o.rateUnit}`
                              : last ? 'Stopped' : 'Not started'}
                          </span>
                          {o.requiresWitness && (
                            <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-amber-600" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>Witness</span>
                          )}
                        </div>
                        <PatientContextLine
                          patientName={o.patientName ?? firstPatientName(entry)}
                          zone={o.zone}
                          bedLabel={o.bedLabel}
                          visitNumber={o.visitNumber}
                          className={`text-[11px] mt-1 ${text.heading}`}
                        />
                        <div className={`text-[11px] mt-0.5 ${text.muted}`}>
                          Ordered {o.rateValue} {o.rateUnit} · by {o.prescribedByName}
                        </div>
                        <div className="mt-2 flex items-center gap-2 flex-wrap">
                          {!running && (
                            <button
                              type="button"
                              onClick={() => openModal({ kind: 'inf-start', entry })}
                              className="px-3 py-1.5 rounded-xl text-xs font-bold bg-cyan-600 text-white hover:bg-cyan-700"
                            >
                              {last ? 'Restart' : 'Start infusion'}
                            </button>
                          )}
                          {running && (
                            <>
                              <button
                                type="button"
                                onClick={() => openModal({ kind: 'inf-rate', entry })}
                                className="px-3 py-1.5 rounded-xl text-xs font-bold bg-cyan-600 text-white hover:bg-cyan-700"
                              >
                                Change rate
                              </button>
                              <button
                                type="button"
                                onClick={() => openModal({ kind: 'inf-stop', entry })}
                                className="px-3 py-1.5 rounded-xl text-xs font-bold bg-rose-600 text-white hover:bg-rose-700"
                              >
                                Stop
                              </button>
                            </>
                          )}
                          <button
                            type="button"
                            onClick={() => navigate(`/visit/${o.visitId}`)}
                            className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`}
                            title="Open visit"
                          >
                            <ExternalLink className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </section>
          </div>

          {/* ── Recently given ── */}
          <section className="rounded-2xl p-4" style={glassCard}>
            <h2 className={`text-xs font-bold uppercase tracking-wider mb-3 flex items-center gap-2 ${text.label}`}>
              <CheckCircle2 className="w-4 h-4 text-emerald-500" />
              Administered — last 8 hours ({board?.recentlyGiven.length ?? 0})
            </h2>
            {(board?.recentlyGiven.length ?? 0) === 0 ? (
              <p className={`text-xs ${text.muted}`}>Nothing administered in the window.</p>
            ) : (
              <div className="space-y-1.5">
                {board!.recentlyGiven.slice(0, 30).map((d) => (
                  <div key={d.id} className={`text-xs flex items-center gap-2 flex-wrap ${text.body}`}>
                    <CheckCircle2 className="w-3 h-3 text-emerald-500 flex-shrink-0" />
                    <span className="font-semibold">{d.drugName}</span>
                    <span>{fmtDose(d)}</span>
                    <span className={text.muted}>→ {d.patientName} ({d.zone ?? '—'}{d.bedLabel ? ` · Bed ${d.bedLabel}` : ''})</span>
                    <span className={text.muted}>
                      by {d.givenByName}{d.witnessName ? ` + witness ${d.witnessName}` : ''}
                      {' '}{d.givenAt ? formatDistanceToNow(new Date(d.givenAt), { addSuffix: true }) : ''}
                    </span>
                    {d.isOverride && (
                      <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-red-600" style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}>Override</span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>
        </>
      )}
      </>)
      )}

      {/* ── Action modal ── */}
      {modal && (
        <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }} role="dialog" aria-modal="true">
          <div className="w-full max-w-md rounded-2xl overflow-hidden shadow-2xl animate-scale-in max-h-[90vh] flex flex-col" style={glassCard}>
            <div className="px-5 py-4 bg-gradient-to-r from-slate-800 to-slate-700 flex items-center justify-between">
              <h3 className="text-sm font-extrabold text-white">
                {modal.kind === 'administer' && `Administer — ${modal.dose.drugName}`}
                {modal.kind === 'delay' && `Delay dose — ${modal.dose.drugName}`}
                {modal.kind === 'refuse' && `Patient refused — ${modal.dose.drugName}`}
                {modal.kind === 'prn' && `PRN dose — ${modal.entry.order.drugName}`}
                {modal.kind === 'inf-start' && `Start infusion — ${modal.entry.order.drugName}`}
                {modal.kind === 'inf-rate' && `Change rate — ${modal.entry.order.drugName}`}
                {modal.kind === 'inf-stop' && `Stop infusion — ${modal.entry.order.drugName}`}
                {modal.kind === 'approve' && `Approve high-alert — ${modal.order.drugName}`}
              </h3>
              <button type="button" onClick={() => setModal(null)} disabled={actionBusy} aria-label="Close"
                className="w-7 h-7 rounded-lg bg-white/10 hover:bg-white/20 flex items-center justify-center">
                <XCircle className="w-4 h-4 text-white" />
              </button>
            </div>

            <div className="p-5 space-y-3 overflow-y-auto">
              {(modal.kind === 'administer' || modal.kind === 'prn') && (
                <>
                  {modal.kind === 'prn' && (
                    <Field label="Indication (what triggered this dose) *">
                      <input value={fIndication} onChange={(e) => setFIndication(e.target.value)}
                        placeholder={`e.g. ${modal.entry.order.prnIndication ?? 'pain 6/10'}`}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                  )}
                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Dose given (verification) *">
                      <input type="number" value={fDoseValue} onChange={(e) => setFDoseValue(e.target.value)}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                    <Field label="Unit">
                      <input value={fDoseUnit} onChange={(e) => setFDoseUnit(e.target.value)}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                  </div>
                  {witnessNeeded && (
                    <Field label="Witness (second clinician) *">
                      <input value={fWitness} onChange={(e) => setFWitness(e.target.value)}
                        placeholder="Full name of the witnessing clinician"
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg border border-amber-500/30 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                  )}
                  <label className={`flex items-center gap-2 text-xs font-semibold ${text.body}`}>
                    <input type="checkbox" checked={fOverride} onChange={(e) => setFOverride(e.target.checked)} />
                    Override a failed safety gate (justification required)
                  </label>
                  {fOverride && (
                    <Field label="Override justification (min 10 chars) *">
                      <textarea rows={2} value={fJustification} onChange={(e) => setFJustification(e.target.value)}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg border border-red-500/30 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                  )}
                </>
              )}

              {modal.kind === 'delay' && (
                <>
                  <Field label="Delay by (minutes, 15–720) *">
                    <input type="number" min={15} max={720} value={fMinutes}
                      onChange={(e) => setFMinutes(e.target.value)}
                      style={glassInner}
                      className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                  </Field>
                  <Field label="Reason *">
                    <textarea rows={2} value={fReason} onChange={(e) => setFReason(e.target.value)}
                      placeholder="e.g. patient away for imaging"
                      style={glassInner}
                      className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                  </Field>
                </>
              )}

              {(modal.kind === 'refuse' || modal.kind === 'inf-stop') && (
                <Field label="Reason *">
                  <textarea rows={2} value={fReason} onChange={(e) => setFReason(e.target.value)}
                    style={glassInner}
                    className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                </Field>
              )}

              {(modal.kind === 'inf-start' || modal.kind === 'inf-rate') && (
                <>
                  <div className="grid grid-cols-2 gap-3">
                    <Field label={modal.kind === 'inf-start' ? 'Rate' : 'New rate *'}>
                      <input type="number" value={fRate} onChange={(e) => setFRate(e.target.value)}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                    <Field label="Unit">
                      <input value={fRateUnit} onChange={(e) => setFRateUnit(e.target.value)}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                  </div>
                  {modal.kind === 'inf-start' && witnessNeeded && (
                    <Field label="Witness (second clinician) *">
                      <input value={fWitness} onChange={(e) => setFWitness(e.target.value)}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg border border-amber-500/30 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                  )}
                  {modal.kind === 'inf-rate' && (
                    <Field label="Reason (optional)">
                      <input value={fReason} onChange={(e) => setFReason(e.target.value)}
                        style={glassInner}
                        className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                    </Field>
                  )}
                </>
              )}

              {modal.kind === 'approve' && (
                <>
                  <p className={`text-xs ${text.muted}`}>
                    Confirm this high-alert order is clinically appropriate. Your name is
                    recorded as the approver; the prescriber cannot approve their own order.
                  </p>
                  <Field label="Note (optional)">
                    <input value={fReason} onChange={(e) => setFReason(e.target.value)}
                      style={glassInner}
                      className={`w-full px-3 py-2 text-sm rounded-lg focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`} />
                  </Field>
                </>
              )}
            </div>

            <div className="px-5 py-4 flex items-center justify-end gap-2" style={{ borderTop: borderStyle }}>
              <button type="button" onClick={() => setModal(null)} disabled={actionBusy}
                style={glassInner}
                className={`px-4 py-2 text-xs font-bold rounded-xl hover:bg-white/5 ${text.body}`}>
                Cancel
              </button>
              <button
                type="button"
                onClick={runModalAction}
                disabled={actionBusy
                  || (modal.kind === 'prn' && !fIndication.trim())
                  || ((modal.kind === 'delay' || modal.kind === 'refuse' || modal.kind === 'inf-stop') && !fReason.trim())
                  || (fOverride && fJustification.trim().length < 10)
                  || (witnessNeeded && !fWitness.trim())}
                className="px-4 py-2 text-xs font-bold rounded-xl bg-cyan-600 hover:bg-cyan-700 text-white shadow-md disabled:opacity-50 inline-flex items-center gap-2"
              >
                {actionBusy && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                Confirm
              </button>
            </div>
          </div>
        </div>
      )}
      </div>
    </div>
  );
}

function firstPatientName(entry: MedicationOrderAudit): string {
  const withName = entry.doses.find((d) => d.patientName);
  return withName?.patientName ?? '—';
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-[10px] font-bold uppercase tracking-wider mb-1 text-slate-400">
        {label}
      </label>
      {children}
    </div>
  );
}

function DoseLane({ title, icon, doses, tone, onAdminister, onDelay, onRefuse, navigate, glassCard, glassInner, text }: {
  title: string;
  icon: React.ReactNode;
  doses: MedicationDoseResponse[];
  tone: 'red' | 'amber' | 'sky';
  onAdminister: (d: MedicationDoseResponse) => void;
  onDelay: (d: MedicationDoseResponse) => void;
  onRefuse: (d: MedicationDoseResponse) => void;
  navigate: (path: string) => void;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  text: any;
}) {
  const border = tone === 'red' ? 'border-red-400/40' : tone === 'amber' ? 'border-amber-400/40' : 'border-sky-400/30';
  return (
    <section className={`rounded-2xl p-4 border ${tone === 'red' && doses.length > 0 ? 'border-red-400/50' : 'border-transparent'}`} style={glassCard}>
      <h2 className={`text-xs font-bold uppercase tracking-wider mb-3 flex items-center gap-2 ${text.label}`}>
        {icon} {title} ({doses.length})
      </h2>
      {doses.length === 0 ? (
        <p className={`text-xs ${text.muted}`}>None.</p>
      ) : (
        <div className="space-y-2">
          {doses.map((d) => (
            <div key={d.id} className={`rounded-xl p-3 border ${border}`} style={glassInner}>
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-sm font-bold ${text.heading}`}>{d.drugName}</span>
                <span className={`text-xs ${text.body}`}>{fmtDose(d)} {d.route ?? ''}</span>
                {d.sequenceNumber != null && (
                  <span className={`text-[10px] ${text.muted}`}>dose #{d.sequenceNumber}</span>
                )}
                {d.priority && d.priority !== 'ROUTINE' && (
                  <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-red-600" style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}>{d.priority}</span>
                )}
                {d.requiresWitness && (
                  <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-amber-600" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>Witness</span>
                )}
                {d.productType && d.productType !== 'DRUG' && (
                  <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-rose-600" style={{ background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' }}>{d.productType.replace('_', ' ')}</span>
                )}
              </div>
              <PatientContextLine
                patientName={d.patientName}
                zone={d.zone}
                bedLabel={d.bedLabel}
                visitNumber={d.visitNumber}
                className={`text-[11px] mt-1 ${text.heading}`}
              />
              <div className={`text-[11px] mt-0.5 ${text.muted}`}>
                {d.dueAt && <>due {formatDistanceToNow(new Date(d.dueAt), { addSuffix: true })}</>}
                {d.delayCount > 0 && <> · delayed ×{d.delayCount}</>}
              </div>
              <div className="mt-2 flex items-center gap-1.5 flex-wrap">
                <button type="button" onClick={() => onAdminister(d)}
                  className="px-2.5 py-1 rounded-xl text-[11px] font-bold bg-cyan-600 text-white hover:bg-cyan-700">
                  Administer
                </button>
                <button type="button" onClick={() => onDelay(d)}
                  className="px-2.5 py-1 rounded-xl text-[11px] font-bold bg-amber-500 text-white hover:bg-amber-600">
                  Delay
                </button>
                <button type="button" onClick={() => onRefuse(d)}
                  className="px-2.5 py-1 rounded-xl text-[11px] font-bold bg-rose-600 text-white hover:bg-rose-700">
                  Refused
                </button>
                <button type="button" onClick={() => navigate(`/visit/${d.visitId}`)}
                  className={`p-1 rounded-lg ${text.muted} hover:bg-white/5`} title="Open visit">
                  <ExternalLink className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

export default MedicationBoard;
