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
import { medicationApi } from '@/api/medications';
import { subscribeToMedications, subscribeToZoneMedications } from '@/api/websocket';
import { useScopedView } from '@/hooks/useScopedView';
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

  const [board, setBoard] = useState<ZoneMedicationBoard | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  /** Charge/admin-only manual zone filter (null = whole hospital). */
  const [zoneFilter, setZoneFilter] = useState<EdZone | null>(null);

  // The zone the BACKEND query uses: a zone nurse is pinned to their
  // shift zone; cross-zone roles use the manual filter (or all).
  const effectiveZone: EdZone | null =
    scope.mode === 'ZONE_SCOPED' ? scope.zone : zoneFilter;

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
    const unsub = scope.mode === 'ZONE_SCOPED' && scope.zone
      ? subscribeToZoneMedications(hospitalId, scope.zone, onEvent)
      : subscribeToMedications(hospitalId, onEvent);
    return () => unsub();
  }, [hospitalId, scope.mode, scope.zone, scope.isLoading, load]);

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
  if (scope.mode === 'RESTRICTED') {
    return (
      <div className="max-w-xl mx-auto mt-16 rounded-2xl p-8 text-center" style={glassCard}>
        <ShieldAlert className="w-10 h-10 mx-auto text-amber-500 mb-3" />
        <h2 className={`text-lg font-bold ${text.heading}`}>No active shift</h2>
        <p className={`text-sm mt-2 ${text.body}`}>
          The medication board is zone-scoped. You don't have an active shift
          assignment right now — ask the charge nurse if you believe this is wrong.
        </p>
      </div>
    );
  }

  const witnessNeeded =
    (modal?.kind === 'administer' && modal.dose.requiresWitness) ||
    (modal?.kind === 'prn' && !!modal.entry.order.requiresWitness) ||
    (modal?.kind === 'inf-start' && !!modal.entry.order.requiresWitness);

  return (
    <div className="space-y-5 animate-fade-up">
      {/* ── Header ── */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div>
          <h1 className={`text-xl font-extrabold tracking-tight ${text.heading} flex items-center gap-2`}>
            <Pill className="w-5 h-5 text-emerald-500" />
            Medication Board
            {scope.mode === 'ZONE_SCOPED' && scope.zone && (
              <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-emerald-500/15 text-emerald-600">
                {scope.zone} zone
              </span>
            )}
          </h1>
          <p className={`text-xs mt-0.5 ${text.muted}`}>
            Scheduled doses, PRN, infusions and high-alert approvals — live.
          </p>
        </div>
        <div className="flex items-center gap-2">
          {scope.mode === 'HOSPITAL_WIDE' && (
            <div className="flex items-center gap-1 flex-wrap">
              <button
                type="button"
                onClick={() => setZoneFilter(null)}
                className={`px-2 py-1 rounded-lg text-[10px] font-bold ${zoneFilter === null
                  ? 'bg-emerald-600 text-white'
                  : isDark ? 'bg-white/5 text-slate-300' : 'bg-slate-100 text-slate-600'}`}
              >
                All zones
              </button>
              {ZONES.map((z) => (
                <button
                  key={z}
                  type="button"
                  onClick={() => setZoneFilter(z)}
                  className={`px-2 py-1 rounded-lg text-[10px] font-bold ${zoneFilter === z
                    ? 'bg-emerald-600 text-white'
                    : isDark ? 'bg-white/5 text-slate-300' : 'bg-slate-100 text-slate-600'}`}
                >
                  {z}
                </button>
              ))}
            </div>
          )}
          <button
            type="button"
            onClick={() => load()}
            className={`p-2 rounded-xl ${text.muted} hover:bg-white/5`}
            title="Refresh"
          >
            <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      {err && (
        <div className="rounded-xl border border-red-300 bg-red-500/10 p-3 text-sm text-red-600 font-semibold">
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
                        <span className="ml-2 text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-red-500/15 text-red-600">High alert</span>
                      </div>
                      <div className={`text-[11px] ${text.muted}`}>
                        {o.prescriptionType?.replace('_', '-')} · prescribed by {o.prescribedByName}
                        {' '}{formatDistanceToNow(new Date(o.prescribedAt), { addSuffix: true })}
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => openModal({ kind: 'approve', order: o })}
                      className="px-3 py-1.5 rounded-lg text-xs font-bold bg-emerald-600 text-white hover:bg-emerald-700 inline-flex items-center gap-1.5"
                    >
                      <ShieldCheck className="w-3.5 h-3.5" /> Approve
                    </button>
                    <button
                      type="button"
                      onClick={() => navigate(`/patients/${o.visitId}`)}
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
                          <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-violet-500/15 text-violet-600">PRN {o.prnIndication}</span>
                          {o.gateParameter && (
                            <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-sky-500/15 text-sky-600">
                              Gate: {o.gateParameter} {o.gateComparator === 'GTE' ? '≥' : '≤'} {o.gateThreshold}
                            </span>
                          )}
                          {o.requiresWitness && (
                            <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-700">Witness</span>
                          )}
                        </div>
                        <div className={`text-[11px] mt-1 ${text.muted}`}>
                          Patient: {firstPatientName(entry)} · {given} given
                          {o.prnMaxDosesPerDay != null && ` (max ${o.prnMaxDosesPerDay}/24h)`}
                          {o.prnMinIntervalHours != null && ` · min ${o.prnMinIntervalHours}h apart`}
                        </div>
                        <div className="mt-2 flex items-center gap-2">
                          <button
                            type="button"
                            onClick={() => openModal({ kind: 'prn', entry })}
                            className="px-3 py-1.5 rounded-lg text-xs font-bold bg-violet-600 text-white hover:bg-violet-700"
                          >
                            Give PRN dose
                          </button>
                          <button
                            type="button"
                            onClick={() => navigate(`/patients/${o.visitId}`)}
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
                          <span className={`text-[9px] font-bold uppercase px-1.5 py-0.5 rounded ${running
                            ? 'bg-emerald-500/15 text-emerald-600'
                            : 'bg-slate-500/15 text-slate-500'}`}>
                            {running
                              ? `Running @ ${last?.rateValue ?? o.rateValue} ${last?.rateUnit ?? o.rateUnit}`
                              : last ? 'Stopped' : 'Not started'}
                          </span>
                          {o.requiresWitness && (
                            <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-700">Witness</span>
                          )}
                        </div>
                        <div className={`text-[11px] mt-1 ${text.muted}`}>
                          Patient: {firstPatientName(entry)} · ordered {o.rateValue} {o.rateUnit}
                          {' '}· by {o.prescribedByName}
                        </div>
                        <div className="mt-2 flex items-center gap-2 flex-wrap">
                          {!running && (
                            <button
                              type="button"
                              onClick={() => openModal({ kind: 'inf-start', entry })}
                              className="px-3 py-1.5 rounded-lg text-xs font-bold bg-cyan-600 text-white hover:bg-cyan-700"
                            >
                              {last ? 'Restart' : 'Start infusion'}
                            </button>
                          )}
                          {running && (
                            <>
                              <button
                                type="button"
                                onClick={() => openModal({ kind: 'inf-rate', entry })}
                                className="px-3 py-1.5 rounded-lg text-xs font-bold bg-sky-600 text-white hover:bg-sky-700"
                              >
                                Change rate
                              </button>
                              <button
                                type="button"
                                onClick={() => openModal({ kind: 'inf-stop', entry })}
                                className="px-3 py-1.5 rounded-lg text-xs font-bold bg-rose-600 text-white hover:bg-rose-700"
                              >
                                Stop
                              </button>
                            </>
                          )}
                          <button
                            type="button"
                            onClick={() => navigate(`/patients/${o.visitId}`)}
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
                    <span className={text.muted}>→ {d.patientName} ({d.zone ?? '—'})</span>
                    <span className={text.muted}>
                      by {d.givenByName}{d.witnessName ? ` + witness ${d.witnessName}` : ''}
                      {' '}{d.givenAt ? formatDistanceToNow(new Date(d.givenAt), { addSuffix: true }) : ''}
                    </span>
                    {d.isOverride && (
                      <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-red-500/15 text-red-600">Override</span>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>
        </>
      )}

      {/* ── Action modal ── */}
      {modal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" role="dialog" aria-modal="true">
          <div className="w-full max-w-md rounded-2xl bg-white shadow-2xl overflow-hidden max-h-[90vh] flex flex-col">
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
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-violet-500" />
                    </Field>
                  )}
                  <div className="grid grid-cols-2 gap-3">
                    <Field label="Dose given (verification) *">
                      <input type="number" value={fDoseValue} onChange={(e) => setFDoseValue(e.target.value)}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-emerald-500" />
                    </Field>
                    <Field label="Unit">
                      <input value={fDoseUnit} onChange={(e) => setFDoseUnit(e.target.value)}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-emerald-500" />
                    </Field>
                  </div>
                  {witnessNeeded && (
                    <Field label="Witness (second clinician) *">
                      <input value={fWitness} onChange={(e) => setFWitness(e.target.value)}
                        placeholder="Full name of the witnessing clinician"
                        className="w-full px-3 py-2 text-sm rounded-lg border border-amber-400 outline-none focus:border-amber-500" />
                    </Field>
                  )}
                  <label className="flex items-center gap-2 text-xs font-semibold text-slate-700">
                    <input type="checkbox" checked={fOverride} onChange={(e) => setFOverride(e.target.checked)} />
                    Override a failed safety gate (justification required)
                  </label>
                  {fOverride && (
                    <Field label="Override justification (min 10 chars) *">
                      <textarea rows={2} value={fJustification} onChange={(e) => setFJustification(e.target.value)}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-red-300 outline-none focus:border-red-500" />
                    </Field>
                  )}
                </>
              )}

              {modal.kind === 'delay' && (
                <>
                  <Field label="Delay by (minutes, 15–720) *">
                    <input type="number" min={15} max={720} value={fMinutes}
                      onChange={(e) => setFMinutes(e.target.value)}
                      className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-amber-500" />
                  </Field>
                  <Field label="Reason *">
                    <textarea rows={2} value={fReason} onChange={(e) => setFReason(e.target.value)}
                      placeholder="e.g. patient away for imaging"
                      className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-amber-500" />
                  </Field>
                </>
              )}

              {(modal.kind === 'refuse' || modal.kind === 'inf-stop') && (
                <Field label="Reason *">
                  <textarea rows={2} value={fReason} onChange={(e) => setFReason(e.target.value)}
                    className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-rose-500" />
                </Field>
              )}

              {(modal.kind === 'inf-start' || modal.kind === 'inf-rate') && (
                <>
                  <div className="grid grid-cols-2 gap-3">
                    <Field label={modal.kind === 'inf-start' ? 'Rate' : 'New rate *'}>
                      <input type="number" value={fRate} onChange={(e) => setFRate(e.target.value)}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-cyan-500" />
                    </Field>
                    <Field label="Unit">
                      <input value={fRateUnit} onChange={(e) => setFRateUnit(e.target.value)}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-cyan-500" />
                    </Field>
                  </div>
                  {modal.kind === 'inf-start' && witnessNeeded && (
                    <Field label="Witness (second clinician) *">
                      <input value={fWitness} onChange={(e) => setFWitness(e.target.value)}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-amber-400 outline-none focus:border-amber-500" />
                    </Field>
                  )}
                  {modal.kind === 'inf-rate' && (
                    <Field label="Reason (optional)">
                      <input value={fReason} onChange={(e) => setFReason(e.target.value)}
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-cyan-500" />
                    </Field>
                  )}
                </>
              )}

              {modal.kind === 'approve' && (
                <>
                  <p className="text-xs text-slate-600">
                    Confirm this high-alert order is clinically appropriate. Your name is
                    recorded as the approver; the prescriber cannot approve their own order.
                  </p>
                  <Field label="Note (optional)">
                    <input value={fReason} onChange={(e) => setFReason(e.target.value)}
                      className="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 outline-none focus:border-emerald-500" />
                  </Field>
                </>
              )}
            </div>

            <div className="px-5 py-4 bg-slate-50 border-t border-slate-200 flex items-center justify-end gap-2">
              <button type="button" onClick={() => setModal(null)} disabled={actionBusy}
                className="px-4 py-2 text-xs font-bold rounded-xl bg-white border border-slate-300 text-slate-800 hover:bg-slate-100">
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
                className="px-4 py-2 text-xs font-bold rounded-xl bg-gradient-to-r from-emerald-600 to-emerald-500 text-white shadow-md disabled:opacity-50 inline-flex items-center gap-2"
              >
                {actionBusy && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                Confirm
              </button>
            </div>
          </div>
        </div>
      )}
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
      <label className="block text-[10px] font-bold uppercase tracking-wider mb-1 text-slate-500">
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
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-red-500/15 text-red-600">{d.priority}</span>
                )}
                {d.requiresWitness && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-700">Witness</span>
                )}
                {d.productType && d.productType !== 'DRUG' && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-rose-500/15 text-rose-600">{d.productType.replace('_', ' ')}</span>
                )}
              </div>
              <div className={`text-[11px] mt-1 ${text.muted}`}>
                {d.patientName} · {d.zone ?? '—'} · visit {d.visitNumber}
                {d.dueAt && <> · due {formatDistanceToNow(new Date(d.dueAt), { addSuffix: true })}</>}
                {d.delayCount > 0 && <> · delayed ×{d.delayCount}</>}
              </div>
              <div className="mt-2 flex items-center gap-1.5 flex-wrap">
                <button type="button" onClick={() => onAdminister(d)}
                  className="px-2.5 py-1 rounded-lg text-[11px] font-bold bg-emerald-600 text-white hover:bg-emerald-700">
                  Administer
                </button>
                <button type="button" onClick={() => onDelay(d)}
                  className="px-2.5 py-1 rounded-lg text-[11px] font-bold bg-amber-500 text-white hover:bg-amber-600">
                  Delay
                </button>
                <button type="button" onClick={() => onRefuse(d)}
                  className="px-2.5 py-1 rounded-lg text-[11px] font-bold bg-rose-600 text-white hover:bg-rose-700">
                  Refused
                </button>
                <button type="button" onClick={() => navigate(`/patients/${d.visitId}`)}
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
