/* ═══════════════════════════════════════════════════════════════
   Transfer of Care Modal — receiving ED nurse acknowledges the
   paramedic handover. This is the legal transfer point.
   ═══════════════════════════════════════════════════════════════ */

import { useEffect, useState } from 'react';
import { ClipboardCheck, Loader2, X, Siren, CheckCircle2, RefreshCw } from 'lucide-react';
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
  // The board's card may be a stale snapshot (vitals/interventions logged after
  // it loaded, or another nurse already received the patient). Pull the current
  // run on open so the ED nurse acknowledges what is actually true now.
  const [live, setLive] = useState<EmsRun>(run);
  const [refreshing, setRefreshing] = useState(true);
  const [alreadyDone, setAlreadyDone] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const fresh = await emsApi.getById(run.id);
        if (cancelled || !fresh) return;
        setLive(fresh);
        // Someone else completed the handover while this modal was open.
        if (fresh.status === 'HANDED_OFF') setAlreadyDone(true);
      } catch {
        /* keep the snapshot we were handed — better stale than blank */
      } finally {
        if (!cancelled) setRefreshing(false);
      }
    })();
    return () => { cancelled = true; };
  }, [run.id]);

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
      const msg = err?.message || 'Failed to acknowledge';
      // A duplicate ack (another nurse beat us, or a double-tap) comes back as a
      // status-transition rejection — handle it as "already done", not a scary error.
      if (/handed.?off|already|invalid.*status|status.*transition/i.test(msg)) {
        setAlreadyDone(true);
      } else {
        setError(msg);
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }}>
      <div className="rounded-2xl p-6 max-w-2xl w-full max-h-[95vh] overflow-y-auto shadow-2xl animate-scale-in" style={glassCard}>
        <div className="flex items-start justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-amber-500/15 flex items-center justify-center">
              <Siren className="w-5 h-5 text-amber-500" />
            </div>
            <div>
              <h3 className={`text-base font-bold ${text.heading}`}>Transfer of care</h3>
              <p className={`text-xs ${text.muted}`}>{live.paramedicName ?? 'Paramedic'} → you</p>
            </div>
          </div>
          <button onClick={onClose} className={`w-8 h-8 rounded-lg flex items-center justify-center ${isDark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
            <X className={`w-4 h-4 ${text.muted}`} />
          </button>
        </div>

        {alreadyDone ? (
          /* The run was already handed off (another nurse, or a double-tap). Show
             a calm confirmation and let the caller's load() clear the stale card. */
          <div className="rounded-xl p-4 mb-4 flex items-start gap-3 bg-emerald-500/10 border border-emerald-400/30">
            <CheckCircle2 className="w-5 h-5 text-emerald-500 shrink-0 mt-0.5" />
            <div>
              <div className={`text-sm font-bold ${text.heading}`}>Already handed off</div>
              <p className={`text-xs ${text.body} mt-0.5`}>
                This patient has already been received into the ED. No further acknowledgement is needed.
              </p>
            </div>
          </div>
        ) : (
          <>
            {refreshing && (
              <div className={`text-[10px] inline-flex items-center gap-1.5 mb-2 ${text.muted}`}>
                <RefreshCw className="w-3 h-3 animate-spin" /> Loading the latest field data…
              </div>
            )}

            {/* Patient + mechanism */}
            <div className="rounded-xl p-3 mb-3" style={glassInner}>
              <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Patient & mechanism</div>
              <div className={`text-sm ${text.heading}`}>{live.mechanism ?? 'Patient'}</div>
              <div className={`text-[11px] ${text.muted}`}>
                {live.patientAgeYears ?? '—'}y {live.patientSex ?? ''} {live.incidentLocation ? `• ${live.incidentLocation}` : ''}
              </div>
            </div>

            {/* Pre-hospital narrative — history / injuries / handover notes */}
            {(live.historySummary || live.injuriesObserved || live.notes) && (
              <div className="rounded-xl p-3 mb-3" style={glassInner}>
                <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Pre-hospital narrative</div>
                {live.historySummary && <p className={`text-xs ${text.body}`}><b>History:</b> {live.historySummary}</p>}
                {live.injuriesObserved && <p className={`text-xs ${text.body} mt-1`}><b>Injuries:</b> {live.injuriesObserved}</p>}
                {live.notes && <p className={`text-xs ${text.body} mt-1`}><b>Handover:</b> {live.notes}</p>}
              </div>
            )}

            {/* Vitals */}
            <div className="rounded-xl p-3 mb-3" style={glassInner}>
              <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Field vitals</div>
              <div className={`text-xs ${text.body} grid grid-cols-4 gap-2`}>
                <div><span className={text.muted}>GCS</span> {live.fieldGcs ?? '—'}</div>
                <div><span className={text.muted}>HR</span> {live.fieldHr ?? '—'}</div>
                <div><span className={text.muted}>BP</span> {live.fieldSbp != null ? `${live.fieldSbp}/${live.fieldDbp ?? '—'}` : '—'}</div>
                <div><span className={text.muted}>SpO₂</span> {live.fieldSpo2 != null ? `${live.fieldSpo2}%` : '—'}</div>
                <div><span className={text.muted}>RR</span> {live.fieldRespRate ?? '—'}</div>
                <div><span className={text.muted}>Temp</span> {live.fieldTemp ?? '—'}</div>
                <div><span className={text.muted}>Glucose</span> {live.fieldGlucose ?? '—'}</div>
                <div><span className={text.muted}>Triage</span> {live.fieldTriageCategory ?? '—'}</div>
              </div>
              {live.fieldTriageReason && (
                <div className={`text-[11px] italic mt-2 ${text.body}`}>"{live.fieldTriageReason}"</div>
              )}
            </div>

            {/* Interventions */}
            {live.interventions && live.interventions.length > 0 && (
              <div className="rounded-xl p-3 mb-3" style={glassInner}>
                <div className={`text-[10px] uppercase font-bold mb-1 ${text.label}`}>Interventions ({live.interventions.length})</div>
                <ul className={`text-xs space-y-1 ${text.body}`}>
                  {live.interventions.map((iv) => (
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
          </>
        )}

        <div className="flex items-center justify-end gap-2">
          {alreadyDone ? (
            <button
              onClick={onSaved}
              className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700"
            >
              <CheckCircle2 className="w-3.5 h-3.5" /> Close
            </button>
          ) : (
            <>
              <button onClick={onClose} disabled={submitting} className={`px-4 py-2 rounded-xl text-xs font-bold ${text.muted} hover:bg-white/5 disabled:opacity-50`}>Cancel</button>
              <button
                onClick={submit}
                disabled={submitting || refreshing}
                className="inline-flex items-center gap-2 px-5 py-2 rounded-xl text-xs font-bold text-white bg-cyan-600 hover:bg-cyan-700 disabled:opacity-50"
              >
                {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ClipboardCheck className="w-3.5 h-3.5" />}
                Acknowledge handover
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
