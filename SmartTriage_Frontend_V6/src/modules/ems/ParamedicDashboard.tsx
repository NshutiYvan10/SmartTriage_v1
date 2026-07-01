/* ═══════════════════════════════════════════════════════════════
   Paramedic Dashboard

   Two-column view for the paramedic:
     1. My active runs    (DISPATCHED / EN_ROUTE / ARRIVED)
     2. Handover history  (HANDED_OFF + CANCELLED, recent)

   Glove-friendly, moving-vehicle UI: large text + tap targets, a live
   connectivity indicator so the crew knows data is reaching the
   hospital, computed field-triage + TEWS, a one-tap lights toggle, and
   quick patient history for known patients.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Siren, Plus, RefreshCw, Loader2, CheckCircle2, AlertOctagon,
  Send, MapPin, Clock, Activity, ClipboardList, Wifi, WifiOff,
  ShieldAlert, HeartPulse, ChevronDown, ChevronUp, Download, ExternalLink,
  Radio, Copy, Check, KeyRound,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { emsApi } from '@/api/ems';
import { iotApi } from '@/api/iot';
import { saveBlob } from '@/api/client';
import { chartPath } from '@/lib/chartNav';
import type { EmsRun, EmsRunStatus, FieldTriageCategory, PatientHistory } from '@/api/ems';
import type { DeviceResponse } from '@/api/types';
import { subscribeToEmsRuns, getStompClient } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';
import { EmsRunForm } from './EmsRunForm';

const STATUS_LABEL: Record<EmsRunStatus, string> = {
  DISPATCHED: 'Dispatched', EN_ROUTE: 'En route', ARRIVED: 'At ED',
  HANDED_OFF: 'Handed off', CANCELLED: 'Cancelled',
};
const STATUS_CHIP: Record<EmsRunStatus, string> = {
  DISPATCHED: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]',
  EN_ROUTE: 'bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]',
  ARRIVED: 'bg-[rgba(99,102,241,0.08)] text-indigo-600 border border-[rgba(99,102,241,0.2)]',
  HANDED_OFF: 'bg-[rgba(16,185,129,0.08)] text-emerald-600 border border-[rgba(16,185,129,0.2)]',
  CANCELLED: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]',
};
function triageColor(c: FieldTriageCategory | null): string {
  switch (c) {
    case 'RED':    return 'bg-rose-500/20 text-rose-300 border border-rose-500/30';
    case 'ORANGE': return 'bg-amber-500/20 text-amber-300 border border-amber-500/30';
    case 'YELLOW': return 'bg-yellow-500/20 text-yellow-300 border border-yellow-500/30';
    case 'GREEN':  return 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30';
    case 'BLUE':   return 'bg-blue-500/20 text-blue-300 border border-blue-500/30';
    default:       return 'bg-slate-500/20 text-slate-300 border border-slate-500/30';
  }
}

export function ParamedicDashboard() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  // The /ems "Sirens" page is visible to nurses + doctors too (so the ED can
  // see inbound ambulances), but ONLY a paramedic can create/manage runs.
  // Paramedics load their OWN runs (/runs/mine, paramedic-gated); everyone
  // else loads the hospital INBOUND board (/hospital/{id}/inbound, the same
  // source the dashboard board uses) read-only. Mirrors the backend
  // @PreAuthorize on the create-run endpoint.
  const canCreateRun = user?.role === 'PARAMEDIC' || user?.role === 'SUPER_ADMIN';

  const [runs, setRuns] = useState<EmsRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<EmsRun | null>(null);
  const [toast, setToast] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const [online, setOnline] = useState<boolean>(false);
  const [lastSync, setLastSync] = useState<Date | null>(null);
  // True when the most recent load() rejected — so the connectivity pill
  // degrades on a failed sync, not only on a dropped socket.
  const [syncFailed, setSyncFailed] = useState<boolean>(false);
  // Increments whenever the shared STOMP client reconnects — driving the
  // /topic/ems subscription effect below to re-subscribe, so the board does
  // not go deaf after a reconnect / covered-zone change.
  const wsGen = useWebSocketGeneration();

  const flash = (type: 'ok' | 'err', t: string) => {
    setToast({ type, text: t });
    setTimeout(() => setToast(null), 3500);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // Paramedic: own runs. Nurse/doctor: hospital inbound ambulances.
      const data = canCreateRun
        ? await emsApi.myRuns()
        : hospitalId ? await emsApi.getInbound(hospitalId) : [];
      setRuns(data || []);
      setLastSync(new Date());
      setSyncFailed(false);
    } catch (err) {
      console.error('[ParamedicDashboard] load failed:', err);
      setSyncFailed(true);
      flash('err', canCreateRun ? 'Failed to load runs' : 'Failed to load inbound ambulances');
    } finally {
      setLoading(false);
    }
  }, [canCreateRun, hospitalId]);

  useEffect(() => { load(); }, [load]);

  // Live updates — receiving nurse acks handover etc. wsGen re-runs this on
  // every reconnect so the subscription is re-established (not left dead).
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToEmsRuns(hospitalId, () => { load(); });
    return () => unsub();
  }, [hospitalId, load, wsGen]);

  // Cross-facility backstop (paramedic own-runs view only). The WS subscription
  // above is the HOME-hospital topic, but a crew transporting to a DIFFERENT
  // hospital owns a run whose ED-ack + status updates are broadcast on the
  // DESTINATION hospital's topic — which this dashboard does not (and may not,
  // per SUBSCRIBE authz) listen to. getMyRuns() returns the crew's runs across
  // all hospitals, so this 30s poll surfaces those updates. Same-hospital runs
  // still update instantly over WS; this is purely a safety net for reroutes.
  useEffect(() => {
    if (!canCreateRun || !hospitalId) return;
    const id = setInterval(() => { load(); }, 30_000);
    return () => clearInterval(id);
  }, [canCreateRun, hospitalId, load]);

  // Connectivity poll — tells the crew whether data is reaching the hospital.
  useEffect(() => {
    const tick = () => setOnline(!!getStompClient()?.connected);
    tick();
    const id = setInterval(tick, 3000);
    return () => clearInterval(id);
  }, []);

  const active = runs.filter((r) => r.status !== 'HANDED_OFF' && r.status !== 'CANCELLED');
  const history = runs.filter((r) => r.status === 'HANDED_OFF' || r.status === 'CANCELLED');

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-rose-700 to-rose-600 px-6 py-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
                  <Siren className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-xl font-bold text-white tracking-wide">
                    {canCreateRun ? 'Paramedic — Runs' : 'Inbound Ambulances'}
                  </h1>
                  <p className="text-white/70 text-sm">
                    {canCreateRun
                      ? (user?.fullName ? `Signed in as ${user.fullName}` : 'Pre-hospital workflow')
                      : 'Ambulances en route to or arrived at your ED'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <ConnectivityPill online={online} lastSync={lastSync} syncFailed={syncFailed} />
                <button onClick={load} className="w-11 h-11 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25" title="Refresh">
                  <RefreshCw className={`w-5 h-5 text-white ${loading ? 'animate-spin' : ''}`} />
                </button>
                {/* Create-run is PARAMEDIC-only — nurses/doctors view inbound runs but cannot author them. */}
                {canCreateRun && (
                  <button
                    onClick={() => { setEditing(null); setShowForm(true); }}
                    className="inline-flex items-center gap-2 px-5 py-3 bg-white text-rose-600 rounded-xl text-sm font-bold shadow-lg hover:-translate-y-0.5 transition-all">
                    <Plus className="w-4 h-4" /> New run
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>

        {toast && (
          <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl text-base font-semibold animate-fade-up ${
            toast.type === 'ok' ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
              : 'bg-rose-500/20 text-rose-300 border border-rose-500/30'}`}>
            {toast.type === 'ok' ? <CheckCircle2 className="w-5 h-5" /> : <AlertOctagon className="w-5 h-5" />}
            {toast.text}
          </div>
        )}

        {/* Active runs */}
        <div className="space-y-3">
          <h2 className={`text-base font-bold ${text.heading}`}>
            <Activity className="w-5 h-5 inline mr-1.5 text-rose-500" />
            {canCreateRun ? `Active runs (${active.length})` : `Inbound ambulances (${active.length})`}
          </h2>

          {loading && active.length === 0 ? (
            <div className="flex items-center justify-center py-8"><Loader2 className="w-7 h-7 animate-spin text-rose-500" /></div>
          ) : active.length === 0 ? (
            <div className="rounded-2xl p-6 text-center" style={glassCard}>
              <Siren className="w-9 h-9 mx-auto mb-2 text-slate-400" />
              <p className={`text-base font-bold ${text.heading}`}>
                {canCreateRun ? 'No active runs' : 'No inbound ambulances'}
              </p>
              <p className={`text-sm ${text.muted}`}>
                {canCreateRun
                  ? 'Tap “New run” to start documenting a dispatch.'
                  : 'Ambulances appear here the moment a paramedic sends a pre-arrival to your ED.'}
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {active.map((run) => (
                <RunCard
                  key={run.id} run={run} glassCard={glassCard} glassInner={glassInner} text={text} isDark={isDark}
                  canAct={canCreateRun}
                  onOpenChart={() => { if (run.visitId) navigate(chartPath(run.visitId)); }}
                  onOpen={() => { setEditing(run); setShowForm(true); }}
                  onPreregister={async () => {
                    try { await emsApi.preregister(run.id, {}); flash('ok', 'Pre-arrival sent to ED'); load(); }
                    catch (e: any) { flash('err', e?.message || 'Failed'); }
                  }}
                  onConfirmArrival={async () => {
                    try { await emsApi.confirmArrival(run.id); flash('ok', 'Arrival confirmed'); load(); }
                    catch (e: any) { flash('err', e?.message || 'Failed'); }
                  }}
                  onToggleLights={async () => {
                    try {
                      const updated = await emsApi.setLights(run.id, !run.lightsActive);
                      flash('ok', updated.lightsActive ? 'Lights activated — priority transport' : 'Lights cleared');
                      load();
                    } catch (e: any) { flash('err', e?.message || 'Failed'); }
                  }}
                />
              ))}
            </div>
          )}
        </div>

        {/* History */}
        {history.length > 0 && (
          <div className="space-y-3">
            <h2 className={`text-base font-bold ${text.heading}`}>
              <ClipboardList className="w-5 h-5 inline mr-1.5 text-slate-500" />
              Recent ({history.length})
            </h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {history.slice(0, 10).map((run) => (
                <HistoryCard key={run.id} run={run} glassCard={glassCard} text={text} />
              ))}
            </div>
          </div>
        )}

        {/* My Field Monitor — self-register a vitals monitor + pairing key.
            Paramedic-only (mirrors the create-run gate). */}
        {canCreateRun && (
          <MyFieldMonitor glassCard={glassCard} glassInner={glassInner} text={text} isDark={isDark} />
        )}
      </div>

      {showForm && canCreateRun && (
        <EmsRunForm
          run={editing} hospitalId={hospitalId}
          onClose={() => { setShowForm(false); setEditing(null); }}
          onSaved={() => { setShowForm(false); setEditing(null); load(); flash('ok', 'Saved'); }}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Connectivity pill
// ─────────────────────────────────────────────────────────────────

function ConnectivityPill({ online, lastSync, syncFailed }: { online: boolean; lastSync: Date | null; syncFailed?: boolean }) {
  // "Live" only when the socket is up AND the last data sync succeeded — a
  // rejected sync (e.g. 403 on a covered-zone change) degrades the pill instead
  // of falsely reassuring the crew. Show the last successful sync time on the
  // face, not just the tooltip, so a stale feed is visible at a glance.
  const healthy = online && !syncFailed;
  const syncedLabel = lastSync ? lastSync.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : null;
  return (
    <div className={`inline-flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs font-bold ${
      healthy ? 'bg-emerald-400/20 text-white' : 'bg-amber-400/25 text-white'}`}
      title={lastSync ? `Last synced ${lastSync.toLocaleTimeString()}` : 'Not yet synced'}>
      {healthy ? <Wifi className="w-4 h-4" /> : <WifiOff className="w-4 h-4 animate-pulse" />}
      {healthy ? (syncedLabel ? `Live · ${syncedLabel}` : 'Live') : (syncFailed ? 'Sync failed' : 'Reconnecting')}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Cards
// ─────────────────────────────────────────────────────────────────

function RunCard({ run, glassCard, glassInner, text, isDark, canAct = true, onOpen, onOpenChart, onPreregister, onConfirmArrival, onToggleLights }: any) {
  const stat: EmsRunStatus = run.status;
  const [showHistory, setShowHistory] = useState(false);
  const [downloadingPcr, setDownloadingPcr] = useState(false);
  // Per-card in-flight guard — an ambulance double-tap on Send-to-ED /
  // Confirm-arrival / Lights must not fire the mutation twice.
  const [actionBusy, setActionBusy] = useState<string | null>(null);
  const guard = (key: string, fn?: () => any) => async () => {
    if (actionBusy || !fn) return;
    setActionBusy(key);
    try { await fn(); } finally { setActionBusy(null); }
  };

  const downloadPcr = async () => {
    setDownloadingPcr(true);
    try {
      const { blob, filename } = await emsApi.downloadPcr(run.id);
      saveBlob(blob, filename);
    } catch (e) {
      console.error('[EMS] PCR download failed', e);
    } finally {
      setDownloadingPcr(false);
    }
  };
  return (
    <div className={`rounded-2xl p-4 ${run.lightsActive ? 'ring-2 ring-rose-500/50' : ''}`} style={glassCard}>
      <div className="flex items-start justify-between mb-2 gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${STATUS_CHIP[stat]}`}>
            {STATUS_LABEL[stat]}
          </span>
          {run.fieldTriageCategory && (
            <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${triageColor(run.fieldTriageCategory)}`}>
              {run.fieldTriageCategory}{run.fieldTewsScore != null ? ` · TEWS ${run.fieldTewsScore}` : ''}
            </span>
          )}
          {run.lightsActive && (
            <span className="text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg bg-rose-500 text-white inline-flex items-center gap-1">
              <Siren className="w-3.5 h-3.5 animate-pulse" /> Lights
            </span>
          )}
          {run.preArrivalAckedAt && (
            <span className="text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg text-emerald-600 inline-flex items-center gap-1"
              style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}>
              <CheckCircle2 className="w-3.5 h-3.5" /> ED acknowledged
            </span>
          )}
        </div>
        <span className={`text-xs ${text.muted}`}>{formatDistanceToNow(new Date(run.dispatchedAt), { addSuffix: true })}</span>
      </div>
      {run.preArrivalAckedAt && (
        <div className="text-sm text-emerald-400 flex items-center gap-1 mb-2">
          <CheckCircle2 className="w-4 h-4" /> Received by {run.preArrivalAckedByName ?? 'ED'} · {formatDistanceToNow(new Date(run.preArrivalAckedAt), { addSuffix: true })}
        </div>
      )}

      <div className="mb-2">
        {/* Who first — identity (or the pre-arrival placeholder) before the clinical payload. */}
        <PatientContextLine
          patientName={run.patientName}
          visitNumber={run.visitNumber}
          className={`text-sm mb-0.5 ${text.body}`}
        />
        <div className={`text-base font-bold ${text.heading}`}>{run.mechanism ?? 'Patient'}</div>
        {run.incidentLocation && (
          <div className={`text-sm ${text.muted} flex items-center gap-1`}><MapPin className="w-4 h-4" /> {run.incidentLocation}</div>
        )}
      </div>

      {(run.fieldGcs || run.fieldHr || run.fieldSbp || run.fieldSpo2) && (
        <div className="rounded-xl px-3 py-2 mb-3 text-sm grid grid-cols-4 gap-1" style={glassInner}>
          <Stat label="GCS" value={run.fieldGcs} text={text} />
          <Stat label="HR" value={run.fieldHr} text={text} />
          <Stat label="BP" value={run.fieldSbp != null ? `${run.fieldSbp}/${run.fieldDbp ?? '—'}` : null} text={text} />
          <Stat label="SpO₂" value={run.fieldSpo2 != null ? `${run.fieldSpo2}%` : null} text={text} />
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        {canAct ? (
          <>
            <button onClick={onOpen}
              className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
              <ClipboardList className="w-4 h-4" /> Open / edit
            </button>
            {(stat === 'DISPATCHED' || stat === 'EN_ROUTE') && (
              <button onClick={guard('lights', onToggleLights)} disabled={!!actionBusy}
                className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold disabled:opacity-50 ${run.lightsActive ? 'bg-rose-500 text-white hover:bg-rose-600' : isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
                <Siren className="w-4 h-4" /> {run.lightsActive ? 'Lights off' : 'Lights'}
              </button>
            )}
            {stat === 'DISPATCHED' && (
              <button onClick={guard('prereg', onPreregister)} disabled={!!actionBusy}
                className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold bg-amber-500 text-white hover:bg-amber-600 disabled:opacity-50">
                <Send className="w-4 h-4" /> Send to ED
              </button>
            )}
            {stat === 'EN_ROUTE' && (
              <button onClick={guard('arrive', onConfirmArrival)} disabled={!!actionBusy}
                className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold bg-cyan-600 text-white hover:bg-cyan-700 disabled:opacity-50">
                <CheckCircle2 className="w-4 h-4" /> At ED
              </button>
            )}
            {run.visitId && (
              <button onClick={() => setShowHistory((v: boolean) => !v)}
                className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
                <HeartPulse className="w-4 h-4" /> History {showHistory ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
              </button>
            )}
            {/* PCR PDF — a permanent run artifact, available for any status. */}
            <button onClick={downloadPcr} disabled={downloadingPcr}
              className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold disabled:opacity-50 ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
              {downloadingPcr ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />} PCR
            </button>
          </>
        ) : (
          /* Nurse/doctor read-only view: this board is for VISIBILITY of inbound
             ambulances. The acknowledge / transfer-of-care action lives on the
             dashboard Inbound board; here we offer the one clearly-allowed,
             useful action — open the linked patient chart. */
          run.visitId ? (
            <button onClick={onOpenChart}
              className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
              <ExternalLink className="w-4 h-4" /> Open chart
            </button>
          ) : (
            <span className={`text-sm ${text.muted}`}>Pre-arrival — chart opens once registered.</span>
          )
        )}
      </div>

      {showHistory && <PatientHistoryPanel runId={run.id} text={text} glassInner={glassInner} />}
    </div>
  );
}

function PatientHistoryPanel({ runId, text, glassInner }: any) {
  const [data, setData] = useState<PatientHistory | null>(null);
  const [loading, setLoading] = useState(true);
  const loadedRef = useRef(false);

  useEffect(() => {
    if (loadedRef.current) return;
    loadedRef.current = true;
    emsApi.patientHistory(runId)
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [runId]);

  return (
    <div className="rounded-xl px-3 py-2.5 mt-3 text-sm" style={glassInner}>
      {loading ? (
        <div className={`flex items-center gap-2 ${text.muted}`}><Loader2 className="w-4 h-4 animate-spin" /> Loading history…</div>
      ) : !data || !data.known ? (
        <div className={text.muted}>{data?.unidentified ? `${data.displayName ?? 'Unidentified'} — no chart history yet.` : 'No linked patient history.'}</div>
      ) : (
        <div className="space-y-1">
          <div className={`font-bold ${text.heading}`}>{data.displayName}</div>
          <div className="flex items-start gap-2">
            <ShieldAlert className="w-4 h-4 text-rose-500 mt-0.5 shrink-0" />
            <span className={text.body}><b>Allergies:</b> {data.knownAllergies?.trim() ? data.knownAllergies : 'None recorded'}</span>
          </div>
          {data.chronicConditions?.trim() && <div className={text.body}><b>Chronic:</b> {data.chronicConditions}</div>}
          {data.bloodType?.trim() && <div className={text.body}><b>Blood type:</b> {data.bloodType}</div>}
          <div className={text.muted}>{data.priorVisitCount} prior visit{data.priorVisitCount === 1 ? '' : 's'}{data.lastVisitAt ? ` · last ${new Date(data.lastVisitAt).toLocaleDateString()}` : ''}</div>
        </div>
      )}
    </div>
  );
}

function HistoryCard({ run, glassCard, text }: any) {
  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-start justify-between mb-1 gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${STATUS_CHIP[run.status as EmsRunStatus]}`}>
            {STATUS_LABEL[run.status as EmsRunStatus]}
          </span>
          {run.fieldTriageCategory && (
            <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${triageColor(run.fieldTriageCategory)}`}>
              {run.fieldTriageCategory}
            </span>
          )}
        </div>
        <span className={`text-xs ${text.muted}`}>{formatDistanceToNow(new Date(run.dispatchedAt), { addSuffix: true })}</span>
      </div>
      <PatientContextLine
        patientName={run.patientName}
        visitNumber={run.visitNumber}
        className={`text-sm mb-0.5 ${text.body}`}
      />
      <div className={`text-base ${text.heading}`}>{run.mechanism ?? 'Patient'}</div>
      {run.handedOffToName && (
        <div className={`text-sm mt-1 flex items-center gap-1 ${text.muted}`}>
          <Clock className="w-4 h-4" /> Handed off to <span className={text.body}>{run.handedOffToName}</span>
          {run.handedOffAt && <> • {formatDistanceToNow(new Date(run.handedOffAt), { addSuffix: true })}</>}
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, text }: { label: string; value: any; text: any }) {
  return (
    <div>
      <div className={`text-[10px] uppercase font-bold ${text.label}`}>{label}</div>
      <div className={`font-bold ${text.heading}`}>{value ?? '—'}</div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// My Field Monitor — paramedic self-registers a vitals monitor, then
// pulls its snapshot into a run (the pull lives in EmsRunForm step 2).
// The ESP32 pairing key is returned ONCE by self-register; we surface it
// in a copyable box that clears on the next load/register.
// ─────────────────────────────────────────────────────────────────

function MyFieldMonitor({ glassCard, glassInner, text, isDark }: any) {
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [serialNumber, setSerialNumber] = useState('');
  const [deviceName, setDeviceName] = useState('');
  const [registering, setRegistering] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // The pairing key is shown ONCE, right after a successful register. It is
  // never returned again by myDevices(), so we hold it in local state only.
  const [pairingKey, setPairingKey] = useState<{ deviceName: string; apiKey: string } | null>(null);
  const [copied, setCopied] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await iotApi.myDevices();
      setDevices(data || []);
    } catch (e) {
      console.error('[MyFieldMonitor] load failed:', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const register = async () => {
    const sn = serialNumber.trim();
    const name = deviceName.trim();
    if (!sn || !name) { setError('Serial number and device name are both required.'); return; }
    setRegistering(true);
    setError(null);
    try {
      const created = await iotApi.selfRegisterDevice({ serialNumber: sn, deviceName: name });
      setSerialNumber('');
      setDeviceName('');
      // apiKey is present exactly once — right here. Surface it, then refresh.
      if (created.apiKey) setPairingKey({ deviceName: created.deviceName, apiKey: created.apiKey });
      await load();
    } catch (e: any) {
      const msg = String(e?.message ?? '');
      setError(
        /exist|duplicate|already|conflict|409/i.test(msg)
          ? 'A monitor with that serial number is already registered.'
          : (msg || 'Could not register the monitor.'),
      );
    } finally {
      setRegistering(false);
    }
  };

  const copyKey = async () => {
    if (!pairingKey) return;
    try {
      await navigator.clipboard.writeText(pairingKey.apiKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard blocked — the key is still visible to copy manually */ }
  };

  const inputClass = `w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`;

  return (
    <div className="space-y-3">
      <h2 className={`text-base font-bold ${text.heading}`}>
        <Radio className="w-5 h-5 inline mr-1.5 text-cyan-500" />
        My Field Monitor
      </h2>
      <div className="rounded-2xl p-4 space-y-4" style={glassCard}>
        <p className={`text-sm ${text.muted}`}>
          Register your vitals monitor once, pair it with the key below, then pull its readings
          straight into a run's field vitals.
        </p>

        {/* Registered monitors */}
        {loading ? (
          <div className={`flex items-center gap-2 text-sm ${text.muted}`}><Loader2 className="w-4 h-4 animate-spin" /> Loading your monitors…</div>
        ) : devices.length === 0 ? (
          <div className={`text-sm ${text.muted}`}>No monitors registered yet — add one below.</div>
        ) : (
          <div className="space-y-2">
            {devices.map((d) => (
              <div key={d.id} className="rounded-xl px-3 py-2.5 flex items-center justify-between gap-2" style={glassInner}>
                <div className="min-w-0">
                  <div className={`text-sm font-bold truncate ${text.heading}`}>{d.deviceName}</div>
                  <div className={`text-xs font-mono truncate ${text.muted}`}>{d.serialNumber}</div>
                </div>
                <span className="text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg shrink-0 text-slate-600"
                  style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                  {d.status}
                </span>
              </div>
            ))}
          </div>
        )}

        {/* Pairing key — shown ONCE right after register */}
        {pairingKey && (
          <div className="rounded-xl p-3.5 ring-2 ring-cyan-500/40 bg-cyan-500/10 space-y-2">
            <div className="flex items-center gap-2">
              <KeyRound className="w-4 h-4 text-cyan-500 shrink-0" />
              <span className={`text-sm font-bold ${text.heading}`}>
                Pairing key for {pairingKey.deviceName}
              </span>
            </div>
            <p className={`text-xs ${text.muted}`}>
              Enter this in your monitor — <b>shown only once</b>. Copy it now; you can't retrieve it later.
            </p>
            <div className="flex items-center gap-2">
              <code className={`flex-1 min-w-0 px-3 py-2 rounded-lg text-sm font-mono break-all ${isDark ? 'bg-black/30 text-cyan-200' : 'bg-white text-cyan-700'}`} style={glassInner}>
                {pairingKey.apiKey}
              </code>
              <button onClick={copyKey}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-bold bg-cyan-600 text-white hover:bg-cyan-700 shrink-0">
                {copied ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />} {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
          </div>
        )}

        {/* Register form */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <input value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)} placeholder="Serial number"
            className={inputClass} style={glassInner} />
          <input value={deviceName} onChange={(e) => setDeviceName(e.target.value)} placeholder="Device name (e.g. SAMU-K7 monitor)"
            className={inputClass} style={glassInner} />
        </div>
        {error && (
          <div className="rounded-xl px-3 py-2 text-sm font-semibold bg-rose-500/10 text-rose-500">{error}</div>
        )}
        <button onClick={register} disabled={registering || !serialNumber.trim() || !deviceName.trim()}
          className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold bg-cyan-600 text-white hover:bg-cyan-700 disabled:opacity-50">
          {registering ? <Loader2 className="w-4 h-4 animate-spin" /> : <Plus className="w-4 h-4" />} Register monitor
        </button>
      </div>
    </div>
  );
}
