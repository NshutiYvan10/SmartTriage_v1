/* ═══════════════════════════════════════════════════════════════
   LabHome — the LAB_TECHNICIAN dashboard.

   Replaces the generic HospitalDashboard (ER triage metrics, arrival
   charts, whole-hospital census) that a lab tech was wrongly shown.
   This surfaces ONLY the lab's own work:
     • Unacknowledged critical results (most urgent)
     • Pending inbox orders (with STAT count)
     • In-progress orders
     • Imaging/ECG studies awaiting the technician
     • Scoped "Lab Patients" count
   with one-tap links into the worklists that action them. Live via
   the same WebSocket streams the worklists use.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  FlaskConical, Inbox, Activity, AlertOctagon, ScanLine, Users,
  Loader2, RefreshCw, ClipboardCheck, ChevronRight, Clock,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { labApi, type LabOrder, type LabPatientSummary } from '@/api/lab';
import { investigationApi } from '@/api/investigations';
import type { InvestigationResponse } from '@/api/types';
import { subscribeToLabOrders, subscribeToDiagnostics } from '@/api/websocket';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';
import { labChartPath } from '@/lib/chartNav';

export function LabHome() {
  const navigate = useNavigate();
  const { cardClass, glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const firstName = user?.fullName?.split(' ')?.[0] || 'there';

  const [inbox, setInbox] = useState<LabOrder[]>([]);
  const [inProgress, setInProgress] = useState<LabOrder[]>([]);
  const [critical, setCritical] = useState<LabOrder[]>([]);
  const [stat, setStat] = useState<LabOrder[]>([]);
  const [imaging, setImaging] = useState<InvestigationResponse[]>([]);
  const [patients, setPatients] = useState<LabPatientSummary[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!hospitalId) { setLoading(false); return; }
    setLoading(true);
    const results = await Promise.allSettled([
      labApi.getInbox(hospitalId),
      labApi.getInProgress(hospitalId),
      labApi.getCritical(hospitalId),
      labApi.getStat(hospitalId),
      investigationApi.imagingWorklist(hospitalId),
      labApi.getLabPatients(hospitalId),
    ]);
    const val = <T,>(r: PromiseSettledResult<T>, d: T): T => (r.status === 'fulfilled' && r.value != null ? r.value : d);
    setInbox(val(results[0] as PromiseSettledResult<LabOrder[]>, []));
    setInProgress(val(results[1] as PromiseSettledResult<LabOrder[]>, []));
    setCritical(val(results[2] as PromiseSettledResult<LabOrder[]>, []));
    setStat(val(results[3] as PromiseSettledResult<LabOrder[]>, []));
    setImaging(val(results[4] as PromiseSettledResult<InvestigationResponse[]>, []));
    setPatients(val(results[5] as PromiseSettledResult<LabPatientSummary[]>, []));
    setLoading(false);
  }, [hospitalId]);

  useEffect(() => { void load(); }, [load]);

  // Live — reload (debounced) when lab or diagnostics events arrive.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!hospitalId) return;
    const bump = () => { if (timer.current) clearTimeout(timer.current); timer.current = setTimeout(() => { void load(); }, 500); };
    const u1 = subscribeToLabOrders(hospitalId, bump);
    const u2 = subscribeToDiagnostics(hospitalId, bump);
    return () => { if (timer.current) clearTimeout(timer.current); u1(); u2(); };
  }, [hospitalId, load]);

  const statCount = stat.length;

  const tiles = [
    { key: 'critical', label: 'Critical — unacknowledged', value: critical.length, icon: AlertOctagon,
      tone: 'text-rose-600', bg: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.25)',
      to: '/lab', pulse: critical.length > 0 },
    { key: 'inbox', label: 'Pending inbox', value: inbox.length, sub: statCount > 0 ? `${statCount} STAT` : undefined,
      icon: Inbox, tone: 'text-cyan-600', bg: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.25)', to: '/lab' },
    { key: 'inprogress', label: 'In progress', value: inProgress.length, icon: Activity,
      tone: 'text-violet-600', bg: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.25)', to: '/lab' },
    { key: 'imaging', label: 'Imaging & ECG to perform', value: imaging.length, icon: ScanLine,
      tone: 'text-amber-600', bg: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.25)', to: '/imaging' },
    { key: 'patients', label: 'Lab patients', value: patients.length, icon: Users,
      tone: 'text-emerald-600', bg: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)', to: '/lab-patients' },
  ];

  const urgent = [...critical, ...stat].slice(0, 6);

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <FlaskConical className="w-5 h-5 text-cyan-300" />
            </div>
            <div className="flex-1">
              <h1 className="text-lg font-bold text-white">Laboratory — Good day, {firstName}</h1>
              <p className="text-sm text-white/50">Your lab &amp; diagnostics workload at a glance.</p>
            </div>
            <button onClick={load} disabled={loading}
              className="inline-flex items-center gap-2 px-3 py-2 text-xs font-bold rounded-xl bg-white/10 hover:bg-white/20 text-white transition-colors">
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              Refresh
            </button>
          </div>
        </div>

        {/* Metric tiles */}
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
          {tiles.map((t) => {
            const Icon = t.icon;
            return (
              <button key={t.key} type="button" onClick={() => navigate(t.to)}
                className={`text-left rounded-2xl p-4 transition-all hover:-translate-y-0.5 ${t.pulse ? 'animate-pulse' : ''}`}
                style={{ ...glassCard, border: t.border }}>
                <div className="flex items-center justify-between">
                  <Icon className={`w-5 h-5 ${t.tone}`} />
                  <span className={`text-2xl font-bold ${text.heading}`}>{t.value}</span>
                </div>
                <p className={`text-[11px] mt-1 font-semibold ${text.body}`}>{t.label}</p>
                {t.sub && <p className="text-[10px] mt-0.5 font-bold text-rose-500">{t.sub}</p>}
              </button>
            );
          })}
        </div>

        {/* Most urgent work */}
        <div className={`${cardClass} overflow-hidden`} style={glassCard}>
          <div className="px-4 py-2.5 flex items-center gap-2" style={{ borderBottom: borderStyle }}>
            <AlertOctagon className="w-4 h-4 text-rose-500" />
            <span className={`text-sm font-bold ${text.heading}`}>Needs attention now</span>
            <span className={`text-[11px] ${text.muted}`}>Unacknowledged criticals &amp; STAT orders</span>
          </div>
          {loading && urgent.length === 0 ? (
            <div className={`text-center py-10 ${text.muted}`}><Loader2 className="w-6 h-6 animate-spin mx-auto opacity-50" /></div>
          ) : urgent.length === 0 ? (
            <p className={`px-4 py-8 text-center text-sm ${text.muted}`}>
              <ClipboardCheck className="w-6 h-6 mx-auto mb-2 opacity-50" />
              Nothing urgent. Pending work is in your <button className="underline" onClick={() => navigate('/lab')}>worklist</button>.
            </p>
          ) : (
            <ul>
              {urgent.map((o) => (
                <li key={o.id} className={`px-4 py-2.5 flex items-center gap-3 border-b last:border-0 ${isDark ? 'border-white/5 hover:bg-white/5' : 'border-slate-100 hover:bg-slate-50'} cursor-pointer`}
                    onClick={() => navigate(labChartPath(o.visitId))}>
                  <div className="flex-1 min-w-0">
                    <PatientContextLine patientName={o.patientName} zone={o.currentZone}
                      bedLabel={o.currentBedLabel} visitNumber={o.visitNumber} className={`text-[11px] mb-0.5 ${text.body}`} />
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-sm font-bold ${text.heading}`}>{o.testName}</span>
                      {o.isCritical && <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-rose-600 text-white">Critical</span>}
                      {o.priority === 'STAT' && <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-rose-500/15 text-rose-500">STAT</span>}
                    </div>
                  </div>
                  <ChevronRight className={`w-4 h-4 ${text.muted}`} />
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* Quick links */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <QuickLink icon={FlaskConical} label="Lab Worklist" desc="Inbox · in-progress · verify · critical" onClick={() => navigate('/lab')} glassInner={glassInner} text={text} />
          <QuickLink icon={ScanLine} label="Imaging & Diagnostics" desc="X-ray · CT · US · ECG to perform" onClick={() => navigate('/imaging')} glassInner={glassInner} text={text} />
          <QuickLink icon={Clock} label="Lab Patients" desc="Patients you have work for" onClick={() => navigate('/lab-patients')} glassInner={glassInner} text={text} />
        </div>
      </div>
    </div>
  );
}

function QuickLink({ icon: Icon, label, desc, onClick, glassInner, text }: {
  icon: typeof FlaskConical; label: string; desc: string; onClick: () => void;
  glassInner: React.CSSProperties; text: { heading: string; muted: string };
}) {
  return (
    <button type="button" onClick={onClick} className="text-left rounded-2xl p-4 flex items-center gap-3 transition-all hover:-translate-y-0.5" style={glassInner}>
      <div className="w-9 h-9 rounded-xl bg-cyan-500/15 flex items-center justify-center flex-shrink-0">
        <Icon className="w-4 h-4 text-cyan-500" />
      </div>
      <div className="min-w-0">
        <p className={`text-sm font-bold ${text.heading}`}>{label}</p>
        <p className={`text-[11px] ${text.muted}`}>{desc}</p>
      </div>
    </button>
  );
}

export default LabHome;
