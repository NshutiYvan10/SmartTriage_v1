/* ═══════════════════════════════════════════════════════════════
   Inbound EMS Board — charge-nurse widget on the dashboard.

   Shows ambulance pre-arrivals + arrived-but-not-handed-off runs in
   real time. Tap a card to open the transfer-of-care modal once the
   patient is at the door.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useState } from 'react';
import { Siren, ChevronUp, ChevronDown, AlertOctagon, MapPin, Clock, ClipboardCheck } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { emsApi } from '@/api/ems';
import type { EmsRun, FieldTriageCategory } from '@/api/ems';
import { subscribeToEmsRuns } from '@/api/websocket';
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

export function InboundEmsBoard() {
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const nurseName = user?.fullName ?? '';

  const [runs, setRuns] = useState<EmsRun[]>([]);
  const [expanded, setExpanded] = useState(true);
  const [transferTarget, setTransferTarget] = useState<EmsRun | null>(null);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    try {
      const data = await emsApi.getInbound(hospitalId);
      setRuns(data || []);
    } catch (err) {
      console.error('[InboundEmsBoard] load failed:', err);
    }
  }, [hospitalId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToEmsRuns(hospitalId, () => load());
    return () => unsub();
  }, [hospitalId, load]);

  if (runs.length === 0) return null;

  const arrived = runs.filter((r) => r.status === 'ARRIVED');
  const enRoute = runs.filter((r) => r.status === 'EN_ROUTE');

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
                  Tap to acknowledge handover when the paramedic gives MIST.
                </div>
              </div>
            </div>
            <button
              onClick={() => setExpanded((e) => !e)}
              className="px-3 py-1.5 rounded-lg bg-white/15 hover:bg-white/25 text-xs font-bold inline-flex items-center gap-1"
            >
              {expanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
              {expanded ? 'Collapse' : 'Show'}
            </button>
          </div>

          {expanded && (
            <div className="mt-3 space-y-2">
              {[...arrived, ...enRoute].map((run) => (
                <div key={run.id} className="rounded-xl bg-white/10 px-3 py-2 flex items-start gap-3">
                  <div className={`w-2 h-2 rounded-full mt-1.5 shrink-0 ${triageColor(run.fieldTriageCategory)}`} />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
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
                      <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${run.status === 'ARRIVED' ? 'bg-rose-500/40' : 'bg-white/15'}`}>
                        {run.status === 'ARRIVED' ? 'AT DOOR' : 'EN ROUTE'}
                      </span>
                    </div>
                    <div className="text-[11px] text-white/85 mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5">
                      {run.incidentLocation && <span className="flex items-center gap-1"><MapPin className="w-3 h-3" /> {run.incidentLocation}</span>}
                      {run.etaMinutes != null && run.status === 'EN_ROUTE' && <span className="flex items-center gap-1"><Clock className="w-3 h-3" /> ETA {run.etaMinutes} min</span>}
                      {run.fieldSbp != null && <span>BP {run.fieldSbp}/{run.fieldDbp ?? '—'}</span>}
                      {run.fieldSpo2 != null && <span>SpO₂ {run.fieldSpo2}%</span>}
                      {run.fieldGcs != null && <span>GCS {run.fieldGcs}</span>}
                    </div>
                    <div className="text-[10px] text-white/70 mt-0.5">
                      {run.paramedicName ?? 'Paramedic'} • {(run.interventions?.length ?? 0)} interventions logged
                    </div>
                    {run.notes && (
                      <div className="text-[11px] text-white/90 mt-0.5 italic">“{run.notes}”</div>
                    )}
                  </div>
                  {run.status === 'ARRIVED' && (
                    <button
                      onClick={() => setTransferTarget(run)}
                      className="shrink-0 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white text-amber-600 hover:bg-white/90 text-[11px] font-bold"
                    >
                      <ClipboardCheck className="w-3 h-3" /> Acknowledge
                    </button>
                  )}
                </div>
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
