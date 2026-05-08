/* ═══════════════════════════════════════════════════════════════
   Paramedic Dashboard — Phase 1

   Two-column view for the paramedic:
     1. My active runs    (DISPATCHED / EN_ROUTE / ARRIVED)
     2. Handover history  (HANDED_OFF + CANCELLED, recent)

   Mobile-first: paramedic uses a phone in the ambulance. Card layout
   stacks at narrow widths.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import {
  Siren, Plus, RefreshCw, Loader2, CheckCircle2, AlertOctagon,
  Send, MapPin, Clock, X, Activity, ClipboardList,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { emsApi } from '@/api/ems';
import type { EmsRun, EmsRunStatus, FieldTriageCategory } from '@/api/ems';
import { subscribeToEmsRuns } from '@/api/websocket';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { EmsRunForm } from './EmsRunForm';

const STATUS_LABEL: Record<EmsRunStatus, string> = {
  DISPATCHED: 'Dispatched',
  EN_ROUTE: 'En route',
  ARRIVED: 'At ED',
  HANDED_OFF: 'Handed off',
  CANCELLED: 'Cancelled',
};

const STATUS_CHIP: Record<EmsRunStatus, string> = {
  DISPATCHED: 'bg-slate-500/15 text-slate-500',
  EN_ROUTE: 'bg-amber-500/15 text-amber-500',
  ARRIVED: 'bg-indigo-500/15 text-indigo-500',
  HANDED_OFF: 'bg-emerald-500/15 text-emerald-500',
  CANCELLED: 'bg-slate-500/15 text-slate-400',
};

function triageColor(c: FieldTriageCategory | null): string {
  switch (c) {
    case 'RED':    return 'bg-rose-500/15 text-rose-500 ring-1 ring-rose-500/30';
    case 'ORANGE': return 'bg-amber-500/15 text-amber-500 ring-1 ring-amber-500/30';
    case 'YELLOW': return 'bg-yellow-500/15 text-yellow-600 ring-1 ring-yellow-500/30';
    case 'GREEN':  return 'bg-emerald-500/15 text-emerald-500 ring-1 ring-emerald-500/30';
    case 'BLUE':   return 'bg-blue-500/15 text-blue-500 ring-1 ring-blue-500/30';
    default:       return 'bg-slate-500/15 text-slate-500';
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
  const flash = (type: 'ok' | 'err', text: string) => {
    setToast({ type, text });
    setTimeout(() => setToast(null), 3500);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await emsApi.myRuns();
      setRuns(data || []);
    } catch (err) {
      console.error('[ParamedicDashboard] load failed:', err);
      flash('err', 'Failed to load runs');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // Live updates — receiving nurse acks the handover etc.
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToEmsRuns(hospitalId, () => { load(); });
    return () => unsub();
  }, [hospitalId, load]);

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
                <div className="w-10 h-10 rounded-xl bg-white/20 flex items-center justify-center">
                  <Siren className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Paramedic — Runs</h1>
                  <p className="text-white/60 text-xs">
                    {user?.fullName ? `Signed in as ${user.fullName}` : 'Pre-hospital workflow'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button onClick={load} className="w-9 h-9 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25" title="Refresh">
                  <RefreshCw className={`w-4 h-4 text-white ${loading ? 'animate-spin' : ''}`} />
                </button>
                <button
                  onClick={() => { setEditing(null); setShowForm(true); }}
                  className="inline-flex items-center gap-2 px-4 py-2 bg-white text-rose-600 rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all"
                >
                  <Plus className="w-3.5 h-3.5" /> New run
                </button>
              </div>
            </div>
          </div>
        </div>

        {toast && (
          <div className={`flex items-center gap-3 px-4 py-3 rounded-2xl text-sm font-semibold animate-fade-up ${
            toast.type === 'ok'
              ? 'bg-emerald-500/15 text-emerald-500 border border-emerald-500/20'
              : 'bg-rose-500/15 text-rose-500 border border-rose-500/20'
          }`}>
            {toast.type === 'ok' ? <CheckCircle2 className="w-4 h-4" /> : <AlertOctagon className="w-4 h-4" />}
            {toast.text}
          </div>
        )}

        {/* Active runs */}
        <div className="space-y-3">
          <h2 className={`text-sm font-bold ${text.heading}`}>
            <Activity className="w-4 h-4 inline mr-1.5 text-rose-500" />
            Active runs ({active.length})
          </h2>

          {loading && active.length === 0 ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="w-6 h-6 animate-spin text-rose-500" />
            </div>
          ) : active.length === 0 ? (
            <div className="rounded-2xl p-6 text-center" style={glassCard}>
              <Siren className="w-8 h-8 mx-auto mb-2 text-slate-400" />
              <p className={`text-sm font-bold ${text.heading}`}>No active runs</p>
              <p className={`text-xs ${text.muted}`}>Tap “New run” to start documenting a dispatch.</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {active.map((run) => (
                <RunCard
                  key={run.id}
                  run={run}
                  glassCard={glassCard}
                  glassInner={glassInner}
                  text={text}
                  isDark={isDark}
                  onOpen={() => { setEditing(run); setShowForm(true); }}
                  onPreregister={async () => {
                    try {
                      await emsApi.preregister(run.id, {});
                      flash('ok', 'Pre-arrival sent to ED');
                      load();
                    } catch (e: any) { flash('err', e?.message || 'Failed'); }
                  }}
                  onConfirmArrival={async () => {
                    try {
                      await emsApi.confirmArrival(run.id);
                      flash('ok', 'Arrival confirmed');
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
            <h2 className={`text-sm font-bold ${text.heading}`}>
              <ClipboardList className="w-4 h-4 inline mr-1.5 text-slate-500" />
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
          run={editing}
          hospitalId={hospitalId}
          onClose={() => { setShowForm(false); setEditing(null); }}
          onSaved={() => { setShowForm(false); setEditing(null); load(); flash('ok', 'Saved'); }}
        />
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Cards
// ─────────────────────────────────────────────────────────────────

function RunCard({ run, glassCard, glassInner, text, isDark, onOpen, onPreregister, onConfirmArrival }: any) {
  const stat: EmsRunStatus = run.status;
  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-start justify-between mb-2 gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${STATUS_CHIP[stat]}`}>
            {STATUS_LABEL[stat]}
          </span>
          {run.fieldTriageCategory && (
            <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${triageColor(run.fieldTriageCategory)}`}>
              {run.fieldTriageCategory}
            </span>
          )}
        </div>
        <span className={`text-[10px] ${text.muted}`}>
          {formatDistanceToNow(new Date(run.dispatchedAt), { addSuffix: true })}
        </span>
      </div>

      <div className="mb-2">
        <div className={`text-sm font-bold ${text.heading}`}>{run.mechanism ?? 'Patient'}</div>
        {run.incidentLocation && (
          <div className={`text-[11px] ${text.muted} flex items-center gap-1`}>
            <MapPin className="w-3 h-3" /> {run.incidentLocation}
          </div>
        )}
      </div>

      {/* Field vitals strip */}
      {(run.fieldGcs || run.fieldHr || run.fieldSbp || run.fieldSpo2) && (
        <div className="rounded-xl px-3 py-2 mb-3 text-[11px] grid grid-cols-4 gap-1" style={glassInner}>
          <Stat label="GCS"   value={run.fieldGcs} text={text} />
          <Stat label="HR"    value={run.fieldHr} text={text} />
          <Stat label="BP"    value={run.fieldSbp != null ? `${run.fieldSbp}/${run.fieldDbp ?? '—'}` : null} text={text} />
          <Stat label="SpO₂"  value={run.fieldSpo2 != null ? `${run.fieldSpo2}%` : null} text={text} />
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <button
          onClick={onOpen}
          className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}
        >
          <ClipboardList className="w-3 h-3" /> Open / edit
        </button>
        {stat === 'DISPATCHED' && (
          <button
            onClick={onPreregister}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-amber-500 text-white hover:bg-amber-600"
          >
            <Send className="w-3 h-3" /> Send to ED
          </button>
        )}
        {stat === 'EN_ROUTE' && (
          <button
            onClick={onConfirmArrival}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-indigo-500 text-white hover:bg-indigo-600"
          >
            <CheckCircle2 className="w-3 h-3" /> At ED
          </button>
        )}
      </div>
    </div>
  );
}

function HistoryCard({ run, glassCard, text }: any) {
  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-start justify-between mb-1 gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${STATUS_CHIP[run.status as EmsRunStatus]}`}>
            {STATUS_LABEL[run.status as EmsRunStatus]}
          </span>
          {run.fieldTriageCategory && (
            <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${triageColor(run.fieldTriageCategory)}`}>
              {run.fieldTriageCategory}
            </span>
          )}
        </div>
        <span className={`text-[10px] ${text.muted}`}>
          {formatDistanceToNow(new Date(run.dispatchedAt), { addSuffix: true })}
        </span>
      </div>
      <div className={`text-sm ${text.heading}`}>{run.mechanism ?? 'Patient'}</div>
      {run.handedOffToName && (
        <div className={`text-[10px] mt-1 flex items-center gap-1 ${text.muted}`}>
          <Clock className="w-3 h-3" /> Handed off to <span className={text.body}>{run.handedOffToName}</span>
          {run.handedOffAt && (
            <> • {formatDistanceToNow(new Date(run.handedOffAt), { addSuffix: true })}</>
          )}
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, text }: { label: string; value: any; text: any }) {
  return (
    <div>
      <div className={`text-[9px] uppercase font-bold ${text.label}`}>{label}</div>
      <div className={`font-bold ${text.heading}`}>{value ?? '—'}</div>
    </div>
  );
}
