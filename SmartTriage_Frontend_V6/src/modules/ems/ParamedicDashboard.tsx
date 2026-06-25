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
import {
  Siren, Plus, RefreshCw, Loader2, CheckCircle2, AlertOctagon,
  Send, MapPin, Clock, Activity, ClipboardList, Wifi, WifiOff,
  ShieldAlert, HeartPulse, ChevronDown, ChevronUp, Download,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { emsApi } from '@/api/ems';
import { saveBlob } from '@/api/client';
import type { EmsRun, EmsRunStatus, FieldTriageCategory, PatientHistory } from '@/api/ems';
import { subscribeToEmsRuns, getStompClient } from '@/api/websocket';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
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
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [runs, setRuns] = useState<EmsRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<EmsRun | null>(null);
  const [toast, setToast] = useState<{ type: 'ok' | 'err'; text: string } | null>(null);
  const [online, setOnline] = useState<boolean>(false);
  const [lastSync, setLastSync] = useState<Date | null>(null);

  const flash = (type: 'ok' | 'err', t: string) => {
    setToast({ type, text: t });
    setTimeout(() => setToast(null), 3500);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await emsApi.myRuns();
      setRuns(data || []);
      setLastSync(new Date());
    } catch (err) {
      console.error('[ParamedicDashboard] load failed:', err);
      flash('err', 'Failed to load runs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // Live updates — receiving nurse acks handover etc.
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToEmsRuns(hospitalId, () => { load(); });
    return () => unsub();
  }, [hospitalId, load]);

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
                  <h1 className="text-xl font-bold text-white tracking-wide">Paramedic — Runs</h1>
                  <p className="text-white/70 text-sm">
                    {user?.fullName ? `Signed in as ${user.fullName}` : 'Pre-hospital workflow'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <ConnectivityPill online={online} lastSync={lastSync} />
                <button onClick={load} className="w-11 h-11 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25" title="Refresh">
                  <RefreshCw className={`w-5 h-5 text-white ${loading ? 'animate-spin' : ''}`} />
                </button>
                <button
                  onClick={() => { setEditing(null); setShowForm(true); }}
                  className="inline-flex items-center gap-2 px-5 py-3 bg-white text-rose-600 rounded-xl text-sm font-bold shadow-lg hover:-translate-y-0.5 transition-all">
                  <Plus className="w-4 h-4" /> New run
                </button>
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
            Active runs ({active.length})
          </h2>

          {loading && active.length === 0 ? (
            <div className="flex items-center justify-center py-8"><Loader2 className="w-7 h-7 animate-spin text-rose-500" /></div>
          ) : active.length === 0 ? (
            <div className="rounded-2xl p-6 text-center" style={glassCard}>
              <Siren className="w-9 h-9 mx-auto mb-2 text-slate-400" />
              <p className={`text-base font-bold ${text.heading}`}>No active runs</p>
              <p className={`text-sm ${text.muted}`}>Tap “New run” to start documenting a dispatch.</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {active.map((run) => (
                <RunCard
                  key={run.id} run={run} glassCard={glassCard} glassInner={glassInner} text={text} isDark={isDark}
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
      </div>

      {showForm && (
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

function ConnectivityPill({ online, lastSync }: { online: boolean; lastSync: Date | null }) {
  return (
    <div className={`inline-flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs font-bold ${
      online ? 'bg-emerald-400/20 text-white' : 'bg-amber-400/25 text-white'}`}
      title={lastSync ? `Last synced ${lastSync.toLocaleTimeString()}` : 'Not yet synced'}>
      {online ? <Wifi className="w-4 h-4" /> : <WifiOff className="w-4 h-4 animate-pulse" />}
      {online ? 'Live' : 'Reconnecting'}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Cards
// ─────────────────────────────────────────────────────────────────

function RunCard({ run, glassCard, glassInner, text, isDark, onOpen, onPreregister, onConfirmArrival, onToggleLights }: any) {
  const stat: EmsRunStatus = run.status;
  const [showHistory, setShowHistory] = useState(false);
  const [downloadingPcr, setDownloadingPcr] = useState(false);

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
        <button onClick={onOpen}
          className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
          <ClipboardList className="w-4 h-4" /> Open / edit
        </button>
        {(stat === 'DISPATCHED' || stat === 'EN_ROUTE') && (
          <button onClick={onToggleLights}
            className={`inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold ${run.lightsActive ? 'bg-rose-500 text-white hover:bg-rose-600' : isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
            <Siren className="w-4 h-4" /> {run.lightsActive ? 'Lights off' : 'Lights'}
          </button>
        )}
        {stat === 'DISPATCHED' && (
          <button onClick={onPreregister}
            className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold bg-amber-500 text-white hover:bg-amber-600">
            <Send className="w-4 h-4" /> Send to ED
          </button>
        )}
        {stat === 'EN_ROUTE' && (
          <button onClick={onConfirmArrival}
            className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-xl text-sm font-bold bg-cyan-600 text-white hover:bg-cyan-700">
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
