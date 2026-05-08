/* ═══════════════════════════════════════════════════════════════
   Pre-hospital Tab — render on the visit detail page when a visit
   has a linked EmsRun. Surfaces the full pre-hospital story so the
   doctor's first 30 seconds with the chart are productive.
   ═══════════════════════════════════════════════════════════════ */

import { useEffect, useState } from 'react';
import { Siren, Loader2, MapPin, Clock, ClipboardCheck, AlertOctagon } from 'lucide-react';
import { emsApi } from '@/api/ems';
import type { EmsRun, FieldTriageCategory } from '@/api/ems';
import { useTheme } from '@/hooks/useTheme';

function triageChip(c: FieldTriageCategory | null): string {
  switch (c) {
    case 'RED':    return 'bg-rose-500/15 text-rose-500';
    case 'ORANGE': return 'bg-amber-500/15 text-amber-500';
    case 'YELLOW': return 'bg-yellow-500/15 text-yellow-600';
    case 'GREEN':  return 'bg-emerald-500/15 text-emerald-500';
    case 'BLUE':   return 'bg-blue-500/15 text-blue-500';
    default:       return 'bg-slate-500/15 text-slate-500';
  }
}

interface Props {
  visitId: string;
  edTriageCategory?: string | null;
}

export function PrehospitalTab({ visitId, edTriageCategory }: Props) {
  const { glassCard, glassInner, text } = useTheme();
  const [run, setRun] = useState<EmsRun | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    emsApi.getByVisit(visitId)
      .then((r) => { if (!cancelled) setRun(r); })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [visitId]);

  if (loading) return <div className="flex items-center justify-center py-8"><Loader2 className="w-6 h-6 animate-spin text-amber-500" /></div>;
  if (!run) {
    return (
      <div className="rounded-2xl p-8 text-center" style={glassCard}>
        <Siren className="w-8 h-8 mx-auto mb-2 text-slate-400" />
        <p className={`text-sm font-bold ${text.heading}`}>No pre-hospital record</p>
        <p className={`text-xs ${text.muted}`}>This visit was not brought in by an ambulance.</p>
      </div>
    );
  }

  const discordant = run.fieldTriageCategory && edTriageCategory
    && run.fieldTriageCategory.toUpperCase() !== edTriageCategory.toUpperCase();

  return (
    <div className="space-y-4">
      {/* Timeline */}
      <div className="rounded-2xl p-4" style={glassCard}>
        <div className={`text-[10px] uppercase font-bold mb-3 ${text.label}`}>Run timeline</div>
        <ol className={`text-xs space-y-1.5 ${text.body}`}>
          {run.dispatchedAt    && <Step label="Dispatched"        at={run.dispatchedAt}    text={text} />}
          {run.sceneArrivedAt  && <Step label="On scene"          at={run.sceneArrivedAt}  text={text} />}
          {run.sceneLeftAt     && <Step label="Left scene"        at={run.sceneLeftAt}     text={text} />}
          {run.edArrivedAt     && <Step label="Arrived at ED"     at={run.edArrivedAt}     text={text} />}
          {run.handedOffAt     && <Step label="Handed off"        at={run.handedOffAt}     text={text} extra={run.handedOffToName} />}
          {run.cancelledAt     && <Step label="Cancelled"         at={run.cancelledAt}     text={text} />}
        </ol>
      </div>

      {/* Field triage + discordance */}
      <div className="rounded-2xl p-4" style={glassCard}>
        <div className={`text-[10px] uppercase font-bold mb-2 ${text.label}`}>Field triage</div>
        <div className="flex flex-wrap items-center gap-2">
          {run.fieldTriageCategory && (
            <span className={`text-xs font-bold px-2 py-0.5 rounded-lg ${triageChip(run.fieldTriageCategory)}`}>
              {run.fieldTriageCategory}
            </span>
          )}
          {edTriageCategory && (
            <>
              <span className={`text-[10px] ${text.muted}`}>→ ED</span>
              <span className={`text-xs font-bold px-2 py-0.5 rounded-lg ${triageChip(edTriageCategory as FieldTriageCategory)}`}>
                {edTriageCategory}
              </span>
            </>
          )}
        </div>
        {run.fieldTriageReason && (
          <p className={`text-xs italic mt-2 ${text.body}`}>"{run.fieldTriageReason}"</p>
        )}
        {discordant && (
          <div className="mt-3 rounded-xl p-2 bg-amber-500/10 ring-1 ring-amber-500/20 flex items-start gap-2">
            <AlertOctagon className="w-3.5 h-3.5 text-amber-500 mt-0.5" />
            <p className={`text-[11px] ${text.body}`}>
              Field-vs-ED discordance: paramedic called <b>{run.fieldTriageCategory}</b>, ED triaged <b>{edTriageCategory}</b>.
            </p>
          </div>
        )}
      </div>

      {/* Vitals */}
      <div className="rounded-2xl p-4" style={glassCard}>
        <div className={`text-[10px] uppercase font-bold mb-2 ${text.label}`}>Field vitals</div>
        <div className={`text-xs ${text.body} grid grid-cols-2 sm:grid-cols-4 gap-2`}>
          <Cell label="GCS"     value={run.fieldGcs} text={text} />
          <Cell label="HR"      value={run.fieldHr} text={text} />
          <Cell label="BP"      value={run.fieldSbp != null ? `${run.fieldSbp}/${run.fieldDbp ?? '—'}` : null} text={text} />
          <Cell label="SpO₂"    value={run.fieldSpo2 != null ? `${run.fieldSpo2}%` : null} text={text} />
          <Cell label="RR"      value={run.fieldRespRate} text={text} />
          <Cell label="Temp"    value={run.fieldTemp} text={text} />
          <Cell label="Glucose" value={run.fieldGlucose} text={text} />
        </div>
      </div>

      {/* Interventions */}
      {run.interventions && run.interventions.length > 0 && (
        <div className="rounded-2xl p-4" style={glassCard}>
          <div className={`text-[10px] uppercase font-bold mb-2 ${text.label}`}>
            Pre-hospital interventions ({run.interventions.length})
          </div>
          <div className="space-y-1.5">
            {run.interventions.map((iv) => (
              <div key={iv.id} className="rounded-xl px-3 py-2 text-xs" style={glassInner}>
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0">
                    <span className={`text-[10px] font-bold ${text.label}`}>{iv.type}</span>
                    <span className={`ml-2 ${text.body}`}>{iv.detail}</span>
                    {iv.dose  && <span className={`ml-1 ${text.muted}`}>• {iv.dose}</span>}
                    {iv.route && <span className={`ml-1 ${text.muted}`}>• {iv.route}</span>}
                  </div>
                  <span className={`text-[10px] ${text.muted} shrink-0`}>
                    {new Date(iv.givenAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  </span>
                </div>
                {(iv.givenByName || iv.outcome) && (
                  <div className={`text-[10px] mt-0.5 ${text.muted}`}>
                    {iv.givenByName && <>by {iv.givenByName}</>}
                    {iv.givenByName && iv.outcome && ' • '}
                    {iv.outcome && <>outcome: {iv.outcome}</>}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Handover */}
      {run.handedOffAt && (
        <div className="rounded-2xl p-4" style={glassCard}>
          <div className={`text-[10px] uppercase font-bold mb-2 ${text.label}`}>Transfer of care</div>
          <p className={`text-xs ${text.body}`}>
            <ClipboardCheck className="w-3.5 h-3.5 inline mr-1 text-emerald-500" />
            Paramedic <b>{run.paramedicName ?? '—'}</b> handed off to <b>{run.handedOffToName ?? '—'}</b>{' '}
            at {new Date(run.handedOffAt).toLocaleTimeString()}.
          </p>
          {run.handoverAcknowledgementText && (
            <p className={`text-xs italic mt-2 ${text.body}`}>"{run.handoverAcknowledgementText}"</p>
          )}
        </div>
      )}
    </div>
  );
}

function Step({ label, at, text, extra }: any) {
  return (
    <li className="flex items-center justify-between gap-3">
      <span className={text.body}>
        <Clock className="w-3 h-3 inline mr-1 text-slate-400" />
        {label}{extra ? ` — ${extra}` : ''}
      </span>
      <span className={`text-[10px] ${text.muted}`}>
        {new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
      </span>
    </li>
  );
}

function Cell({ label, value, text }: any) {
  return (
    <div>
      <div className={`text-[10px] uppercase ${text.label}`}>{label}</div>
      <div className={`font-bold ${text.heading}`}>{value ?? '—'}</div>
    </div>
  );
}
