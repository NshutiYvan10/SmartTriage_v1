/* ═══════════════════════════════════════════════════════════════
   Paramedic Home — the paramedic's landing dashboard.

   Rendered by the '/dashboard' route for the PARAMEDIC role INSTEAD of
   the hospital dashboard. A paramedic has no business seeing hospital
   census, ED-zone activity or internal metrics — their picture is:

     • My active runs / handoffs (own-run scoped, emsApi.myRuns)
     • What stage each case is at (lifecycle) + where it's routed
     • My field monitor status (online / recording)
     • Quick paths into the Siren (runs) and Monitor pages

   Everything here is scoped to the signed-in crew member — no
   hospital-wide data is fetched at all.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Siren, Radio, RefreshCw, Loader2, Activity, CheckCircle2,
  ClipboardList, ChevronRight, Plus, MapPin,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { emsApi } from '@/api/ems';
import { iotApi } from '@/api/iot';
import type { EmsRun, EmsRunStatus, FieldTriageCategory } from '@/api/ems';
import type { DeviceResponse } from '@/api/types';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';

const STAGE_LABEL: Record<string, string> = {
  DISPATCHED: 'Dispatched', EN_ROUTE: 'En route', AT_DOOR: 'At door',
  RECEIVED: 'Received', HANDED_OFF: 'Handed off', CANCELLED: 'Cancelled',
};
const STATUS_CHIP: Record<EmsRunStatus, string> = {
  DISPATCHED: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]',
  EN_ROUTE: 'bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]',
  ARRIVED: 'bg-[rgba(99,102,241,0.08)] text-indigo-600 border border-[rgba(99,102,241,0.2)]',
  HANDED_OFF: 'bg-[rgba(16,185,129,0.08)] text-emerald-600 border border-[rgba(16,185,129,0.2)]',
  CANCELLED: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]',
};
function triageChip(c: FieldTriageCategory | null): string {
  switch (c) {
    case 'RED':    return 'bg-rose-500/20 text-rose-500 border border-rose-500/30';
    case 'ORANGE': return 'bg-amber-500/20 text-amber-600 border border-amber-500/30';
    case 'YELLOW': return 'bg-yellow-500/20 text-yellow-600 border border-yellow-500/30';
    case 'GREEN':  return 'bg-emerald-500/20 text-emerald-600 border border-emerald-500/30';
    case 'BLUE':   return 'bg-blue-500/20 text-blue-500 border border-blue-500/30';
    default:       return 'bg-slate-500/20 text-slate-500 border border-slate-500/30';
  }
}

export function ParamedicHome() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [runs, setRuns] = useState<EmsRun[]>([]);
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    // Own runs + own monitors — independently; one failing must not blank
    // the other pane.
    const [runsR, devicesR] = await Promise.allSettled([
      emsApi.myRuns(),
      iotApi.myDevices(),
    ]);
    if (runsR.status === 'fulfilled') setRuns(runsR.value || []);
    if (devicesR.status === 'fulfilled') setDevices(devicesR.value || []);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  // Light refresh — handoff acks and lifecycle changes land within 30s even
  // without a WS subscription (cross-facility runs update via poll anyway).
  useEffect(() => {
    const id = setInterval(() => { load(); }, 30_000);
    return () => clearInterval(id);
  }, [load]);

  const active = runs.filter((r) => r.status !== 'HANDED_OFF' && r.status !== 'CANCELLED');
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const handedOffToday = runs.filter(
    (r) => r.status === 'HANDED_OFF' && r.handedOffAt && new Date(r.handedOffAt) >= today,
  ).length;
  const monitorsOnline = devices.filter((d) => d.status === 'ONLINE' || d.status === 'MONITORING').length;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-5xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-rose-700 to-rose-600 px-6 py-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
                  <Siren className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-xl font-bold text-white tracking-wide">Field Operations</h1>
                  <p className="text-white/70 text-sm">
                    {user?.fullName ? `Signed in as ${user.fullName}` : 'Pre-hospital crew dashboard'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button onClick={load} className="w-11 h-11 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25" title="Refresh">
                  <RefreshCw className={`w-5 h-5 text-white ${loading ? 'animate-spin' : ''}`} />
                </button>
                <button
                  onClick={() => navigate('/ems')}
                  className="inline-flex items-center gap-2 px-5 py-3 bg-white text-rose-600 rounded-xl text-sm font-bold shadow-lg hover:-translate-y-0.5 transition-all">
                  <Plus className="w-4 h-4" /> New run
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* KPI strip — MY work, not the hospital's */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <Kpi icon={Activity} tone="text-rose-500" label="Active runs" value={active.length} glassCard={glassCard} text={text} />
          <Kpi icon={CheckCircle2} tone="text-emerald-600" label="Handed off today" value={handedOffToday} glassCard={glassCard} text={text} />
          <Kpi icon={Radio} tone={monitorsOnline > 0 ? 'text-cyan-600' : 'text-slate-500'} label="Monitors online" value={`${monitorsOnline}/${devices.length}`} glassCard={glassCard} text={text} />
        </div>

        {/* Active handoffs */}
        <div className="space-y-3">
          <h2 className={`text-base font-bold ${text.heading}`}>
            <Activity className="w-5 h-5 inline mr-1.5 text-rose-500" />
            Active handoffs ({active.length})
          </h2>
          {loading && active.length === 0 ? (
            <div className="flex items-center justify-center py-8"><Loader2 className="w-7 h-7 animate-spin text-rose-500" /></div>
          ) : active.length === 0 ? (
            <div className="rounded-2xl p-6 text-center" style={glassCard}>
              <Siren className="w-9 h-9 mx-auto mb-2 text-slate-400" />
              <p className={`text-base font-bold ${text.heading}`}>No active runs</p>
              <p className={`text-sm ${text.muted}`}>Start a new run from the Siren page when you're dispatched.</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {active.map((run) => (
                <button
                  key={run.id}
                  onClick={() => navigate('/ems')}
                  className={`rounded-2xl p-4 text-left transition-all hover:-translate-y-0.5 ${run.lightsActive ? 'ring-2 ring-rose-500/50' : ''}`}
                  style={glassCard}
                >
                  <div className="flex items-start justify-between gap-2 mb-2">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${STATUS_CHIP[run.status]}`}>
                        {STAGE_LABEL[run.lifecycleStage] ?? run.lifecycleStage}
                      </span>
                      {run.fieldTriageCategory && (
                        <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${triageChip(run.fieldTriageCategory)}`}>
                          {run.fieldTriageCategory}
                        </span>
                      )}
                      {run.preArrivalAckedAt && (
                        <span className="text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg text-emerald-600 inline-flex items-center gap-1"
                          style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}>
                          <CheckCircle2 className="w-3.5 h-3.5" /> ED ack
                        </span>
                      )}
                    </div>
                    <span className={`text-xs shrink-0 ${text.muted}`}>{formatDistanceToNow(new Date(run.dispatchedAt), { addSuffix: true })}</span>
                  </div>
                  <PatientContextLine
                    patientName={run.patientName}
                    visitNumber={run.visitNumber}
                    className={`text-sm mb-0.5 ${text.body}`}
                  />
                  <div className={`text-base font-bold ${text.heading}`}>{run.mechanism ?? 'Patient'}</div>
                  {run.routingTarget && (
                    <div className={`text-sm mt-1 flex items-center gap-1 ${text.muted}`}>
                      <MapPin className="w-4 h-4" /> Routed to <span className={text.body}>{run.routingTarget.replace(/_/g, ' ')}</span>
                    </div>
                  )}
                  <div className={`text-sm mt-2 inline-flex items-center gap-1 font-bold text-rose-500`}>
                    Open in Siren <ChevronRight className="w-4 h-4" />
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* My monitors — status summary linking to Monitor Management */}
        <div className="space-y-3">
          <h2 className={`text-base font-bold ${text.heading}`}>
            <Radio className="w-5 h-5 inline mr-1.5 text-cyan-500" />
            My monitors
          </h2>
          <button onClick={() => navigate('/monitor')} className="w-full text-left rounded-2xl p-4 transition-all hover:-translate-y-0.5" style={glassCard}>
            {devices.length === 0 ? (
              <div className="flex items-center justify-between gap-2">
                <span className={`text-sm ${text.muted}`}>No monitor registered yet — set one up to pull field vitals into your runs.</span>
                <span className="inline-flex items-center gap-1 text-sm font-bold text-cyan-600 shrink-0">Set up <ChevronRight className="w-4 h-4" /></span>
              </div>
            ) : (
              <div className="space-y-2">
                {devices.map((d) => (
                  <div key={d.id} className="rounded-xl px-3 py-2.5 flex items-center justify-between gap-2" style={glassInner}>
                    <div className="min-w-0">
                      <div className={`text-sm font-bold truncate ${text.heading}`}>{d.deviceName}</div>
                      <div className={`text-xs font-mono truncate ${text.muted}`}>{d.serialNumber}</div>
                    </div>
                    <div className="flex items-center gap-1.5 shrink-0">
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${
                        (d.status === 'ONLINE' || d.status === 'MONITORING') ? 'text-emerald-600' : 'text-slate-600'}`}
                        style={(d.status === 'ONLINE' || d.status === 'MONITORING')
                          ? { background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }
                          : { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                        {d.status}
                      </span>
                      {!d.recordingEnabled && (
                        <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg text-amber-600"
                          style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}>
                          Paused
                        </span>
                      )}
                    </div>
                  </div>
                ))}
                <div className={`text-sm inline-flex items-center gap-1 font-bold text-cyan-600`}>
                  Manage monitors <ChevronRight className="w-4 h-4" />
                </div>
              </div>
            )}
          </button>
        </div>

        {/* Recent handoffs — compact, links to Siren for full history/PCR */}
        {runs.some((r) => r.status === 'HANDED_OFF') && (
          <div className="space-y-3">
            <h2 className={`text-base font-bold ${text.heading}`}>
              <ClipboardList className="w-5 h-5 inline mr-1.5 text-slate-500" />
              Recent handoffs
            </h2>
            <div className="rounded-2xl p-2 space-y-1" style={glassCard}>
              {runs.filter((r) => r.status === 'HANDED_OFF').slice(0, 5).map((r) => (
                <div key={r.id} className={`px-3 py-2.5 rounded-xl flex items-center justify-between gap-2 ${isDark ? 'hover:bg-white/5' : 'hover:bg-slate-50'}`}>
                  <div className="min-w-0">
                    <PatientContextLine
                      patientName={r.patientName}
                      visitNumber={r.visitNumber}
                      className={`text-sm ${text.body}`}
                    />
                    <div className={`text-xs ${text.muted}`}>{r.mechanism ?? 'Patient'}</div>
                  </div>
                  <span className={`text-xs shrink-0 ${text.muted}`}>
                    {r.handedOffAt ? formatDistanceToNow(new Date(r.handedOffAt), { addSuffix: true }) : ''}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function Kpi({ icon: Icon, tone, label, value, glassCard, text }: {
  icon: typeof Activity; tone: string; label: string; value: number | string;
  glassCard: React.CSSProperties; text: any;
}) {
  return (
    <div className="rounded-2xl p-4 flex items-center gap-3" style={glassCard}>
      <Icon className={`w-6 h-6 flex-shrink-0 ${tone}`} />
      <div className="min-w-0">
        <div className={`text-2xl font-extrabold leading-none ${tone}`}>{value}</div>
        <div className={`text-xs font-medium mt-1 ${text.muted}`}>{label}</div>
      </div>
    </div>
  );
}

export default ParamedicHome;
