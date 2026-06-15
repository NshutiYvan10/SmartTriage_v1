/* ═══════════════════════════════════════════════════════════════
   Transfer of Care Modal — receiving ED nurse acknowledges the
   paramedic handover. This is the legal transfer point.
   ═══════════════════════════════════════════════════════════════ */

import { useState } from 'react';
import { ClipboardCheck, Loader2, X, Siren } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { emsApi } from '@/api/ems';
import type { EmsRun } from '@/api/ems';

interface Props {
  run: EmsRun;
  receivedByName: string;
  onClose: () => void;
  onSaved: () => void;
}

export function TransferOfCareModal({ run, receivedByName, onClose, onSaved }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [ack, setAck] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setSubmitting(true);
    setError(null);
    try {
      await emsApi.transferOfCare(run.id, {
        receivedByName: receivedByName || undefined,
        acknowledgementText: ack || undefined,
      });
      onSaved();
    } catch (err: any) {
      setError(err?.message || 'Failed to acknowledge');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
      <div className="rounded-2xl p-6 max-w-2xl w-full max-h-[95vh] overflow-y-auto animate-fade-up" style={glassCard}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-amber-500/15 flex items-center justify-center">
              <Siren className="w-5 h-5 text-amber-500" />
            </div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>Transfer of care</h3>
              <p className={`text-xs ${text.muted}`}>{run.paramedicName ?? 'Paramedic'} → you</p>
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className="w-4 h-4 text-slate-400" />
          </button>
        </div>

        {/* Patient + mechanism */}
        <div className="rounded-xl p-3 mb-3" style={glassInner}>
          <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Patient & mechanism</div>
          <div className={`text-sm ${text.heading}`}>{run.mechanism ?? 'Patient'}</div>
          <div className={`text-[11px] ${text.muted}`}>
            {run.patientAgeYears ?? '—'}y {run.patientSex ?? ''} {run.incidentLocation ? `• ${run.incidentLocation}` : ''}
          </div>
        </div>

        {/* Pre-hospital narrative — history / injuries / handover notes */}
        {(run.historySummary || run.injuriesObserved || run.notes) && (
          <div className="rounded-xl p-3 mb-3" style={glassInner}>
            <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Pre-hospital narrative</div>
            {run.historySummary && <p className={`text-xs ${text.body}`}><b>History:</b> {run.historySummary}</p>}
            {run.injuriesObserved && <p className={`text-xs ${text.body} mt-1`}><b>Injuries:</b> {run.injuriesObserved}</p>}
            {run.notes && <p className={`text-xs ${text.body} mt-1`}><b>Handover:</b> {run.notes}</p>}
          </div>
        )}

        {/* Vitals */}
        <div className="rounded-xl p-3 mb-3" style={glassInner}>
          <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Field vitals</div>
          <div className={`text-xs ${text.body} grid grid-cols-4 gap-2`}>
            <div><span className={text.muted}>GCS</span> {run.fieldGcs ?? '—'}</div>
            <div><span className={text.muted}>HR</span> {run.fieldHr ?? '—'}</div>
            <div><span className={text.muted}>BP</span> {run.fieldSbp != null ? `${run.fieldSbp}/${run.fieldDbp ?? '—'}` : '—'}</div>
            <div><span className={text.muted}>SpO₂</span> {run.fieldSpo2 != null ? `${run.fieldSpo2}%` : '—'}</div>
            <div><span className={text.muted}>RR</span> {run.fieldRespRate ?? '—'}</div>
            <div><span className={text.muted}>Temp</span> {run.fieldTemp ?? '—'}</div>
            <div><span className={text.muted}>Glucose</span> {run.fieldGlucose ?? '—'}</div>
            <div><span className={text.muted}>Triage</span> {run.fieldTriageCategory ?? '—'}</div>
          </div>
          {run.fieldTriageReason && (
            <div className={`text-[11px] italic mt-2 ${text.body}`}>"{run.fieldTriageReason}"</div>
          )}
        </div>

        {/* Interventions */}
        {run.interventions && run.interventions.length > 0 && (
          <div className="rounded-xl p-3 mb-3" style={glassInner}>
            <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Interventions ({run.interventions.length})</div>
            <ul className={`text-xs space-y-1 ${text.body}`}>
              {run.interventions.map((iv) => (
                <li key={iv.id}>• {iv.detail || iv.type}{iv.outcome ? ` — ${iv.outcome}` : ''}</li>
              ))}
            </ul>
          </div>
        )}

        {/* Read-back ack */}
        <div className="mb-4">
          <label className={`text-[10px] font-bold uppercase tracking-wider mb-1 block ${text.label}`}>Acknowledgement (optional read-back)</label>
          <textarea
            value={ack}
            onChange={(e) => setAck(e.target.value)}
            rows={2}
            placeholder='Key items you are taking responsibility for, e.g. "RTA, GCS 9, IV running, took blood at 14:08"'
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
          <p className={`text-[10px] mt-1 ${text.muted}`}>
            Tapping "Acknowledge" transfers responsibility from the paramedic to you. The 15-min ED re-triage clock will start.
          </p>
        </div>

        {error && (
          <div className="rounded-xl px-3 py-2 mb-3 text-xs font-semibold bg-rose-500/10 text-rose-500">{error}</div>
        )}

        <div className="flex items-center justify-end gap-2">
          <button onClick={onClose} disabled={submitting} className={`px-4 py-2 rounded-xl text-xs font-bold ${text.muted} hover:bg-white/5 disabled:opacity-50`}>Cancel</button>
          <button
            onClick={submit}
            disabled={submitting}
            className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-gradient-to-r from-amber-600 to-amber-500 disabled:opacity-50"
          >
            {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ClipboardCheck className="w-3.5 h-3.5" />}
            Acknowledge handover
          </button>
        </div>
      </div>
    </div>
  );
}
