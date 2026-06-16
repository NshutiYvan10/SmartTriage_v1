/* ═══════════════════════════════════════════════════════════════
   Hypoglycemia Panel — per-visit entry point (chart tab)

   The natural place a clinician initiates a glucose check, sees this
   patient's hypoglycemia events, and runs the treat → recheck → resolve
   workflow. Before this panel the enforce + getForVisit endpoints had no
   UI caller at all (detection was effectively un-startable from the chart).
   Glucose recorded as a vital now also auto-detects on the backend; this
   surface lets a clinician trigger/confirm and act on it directly.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Droplets, AlertTriangle, CheckCircle2, Loader2, Clock, Syringe,
  FlaskConical, RefreshCw, Play, Timer,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { hypoglycemiaApi, type HypoglycemiaEvent } from '@/api/hypoglycemia';
import { subscribeToHypoglycemia } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { ApiError } from '@/api/client';
import { format } from 'date-fns';

const SEVERITY_FALLBACK = { color: 'text-red-500', bg: 'bg-red-500/10', label: 'CHECK' };
const SEVERITY_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  SEVERE:        { color: 'text-red-600',     bg: 'bg-red-500/15',     label: 'SEVERE' },
  MODERATE:      { color: 'text-red-400',     bg: 'bg-red-500/10',     label: 'MODERATE' },
  MILD:          { color: 'text-amber-500',   bg: 'bg-amber-500/10',   label: 'MILD' },
  NORMAL:        { color: 'text-emerald-500', bg: 'bg-emerald-500/10', label: 'NORMAL' },
  PENDING_CHECK: { color: 'text-amber-500',   bg: 'bg-amber-500/10',   label: 'CHECK PENDING' },
};

const TREATMENT_OPTIONS = [
  'IV Dextrose 50% 50ml',
  'IV D10W infusion',
  'Pediatric 10% dextrose 5ml/kg',
  'Oral glucose (15–20g)',
];

function recheckLabel(dueIso: string | null): { text: string; overdue: boolean } | null {
  if (!dueIso) return null;
  const ms = new Date(dueIso).getTime() - Date.now();
  const mins = Math.round(ms / 60000);
  if (mins <= 0) return { text: `recheck overdue by ${Math.abs(mins)}m`, overdue: true };
  return { text: `recheck due in ${mins}m`, overdue: false };
}

interface HypoglycemiaPanelProps {
  visitId: string;
  onChanged?: () => void;
}

export function HypoglycemiaPanel({ visitId, onChanged }: HypoglycemiaPanelProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';
  const wsGen = useWebSocketGeneration();

  const [events, setEvents] = useState<HypoglycemiaEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, setTick] = useState(0);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Inline workflow state per active event
  const [treatFor, setTreatFor] = useState<string | null>(null);
  const [treatment, setTreatment] = useState('');
  const [repeatFor, setRepeatFor] = useState<string | null>(null);
  const [repeatGlucose, setRepeatGlucose] = useState('');
  const [repeatUnit, setRepeatUnit] = useState<'MMOL_L' | 'MG_DL'>('MMOL_L');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await hypoglycemiaApi.getForVisit(visitId);
      setEvents(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load hypoglycemia events:', err);
      setEvents([]);
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    tickRef.current = setInterval(() => setTick((t) => t + 1), 30000);
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, []);

  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToHypoglycemia(hospitalId, (event: { visitId?: string }) => {
      if (event?.visitId === visitId) load();
    });
    return () => unsub();
  }, [hospitalId, visitId, load, wsGen]);

  const fail = (err: unknown, fallback: string) =>
    setError(err instanceof ApiError ? err.message : err instanceof Error ? err.message : fallback);

  const runAction = async (fn: () => Promise<unknown>, fallback: string, after?: () => void) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      after?.();
      await load();
      onChanged?.();
    } catch (err) {
      fail(err, fallback);
    } finally {
      setBusy(false);
    }
  };

  const runCheck = () => runAction(
    () => hypoglycemiaApi.enforce(visitId),
    'Failed to run glucose check');

  const submitTreatment = (id: string) => {
    if (!treatment) return;
    runAction(
      () => hypoglycemiaApi.recordTreatment(id, { treatment }),
      'Failed to record treatment',
      () => { setTreatFor(null); setTreatment(''); });
  };

  const submitRepeat = (id: string) => {
    const v = parseFloat(repeatGlucose);
    if (isNaN(v) || v <= 0) return;
    runAction(
      () => hypoglycemiaApi.recordRepeatGlucose(id, { glucoseLevel: v, unit: repeatUnit }),
      'Failed to record repeat glucose',
      () => { setRepeatFor(null); setRepeatGlucose(''); setRepeatUnit('MMOL_L'); });
  };

  const resolve = (id: string) => runAction(() => hypoglycemiaApi.resolve(id), 'Failed to resolve event');

  const openEvents = events.filter((e) => !e.resolved);
  const history = events.filter((e) => e.resolved);

  return (
    <div className="space-y-4">
      {/* Header + run-check */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-purple-500/15 flex items-center justify-center shrink-0">
              <Droplets className="w-5 h-5 text-purple-500" />
            </div>
            <div>
              <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Glucose / Hypoglycemia</h3>
              <p className={`text-xs ${text.muted}`}>
                A low POC/monitor glucose auto-detects; you can also run a check and act here.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {events.length > 0 && (
              <button onClick={load} disabled={loading}
                className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${isDark ? 'bg-white/5 hover:bg-white/10' : 'bg-slate-100 hover:bg-slate-200'}`} title="Refresh">
                <RefreshCw className={`w-4 h-4 ${text.muted} ${loading ? 'animate-spin' : ''}`} />
              </button>
            )}
            <button onClick={runCheck} disabled={busy}
              title="Run a glucose-check enforcement against this patient's latest data"
              className="inline-flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-xl bg-gradient-to-r from-purple-500 to-fuchsia-500 text-white shadow-md hover:-translate-y-0.5 transition-all disabled:opacity-50">
              {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
              Run glucose check
            </button>
          </div>
        </div>
        {error && (
          <div className="mt-3 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[11px] font-semibold text-red-500">{error}</p>
          </div>
        )}
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-cyan-500" /></div>
      ) : events.length === 0 ? (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <CheckCircle2 className={`w-10 h-10 mx-auto mb-3 ${text.muted}`} />
          <p className={`text-sm font-bold ${text.heading}`}>No hypoglycemia events</p>
          <p className={`text-xs mt-1 ${text.muted}`}>None detected for this visit. Record a bedside glucose, or run a check above.</p>
        </div>
      ) : (
        <>
          {openEvents.map((evt) => {
            const sev = SEVERITY_CONFIG[evt.severity] || SEVERITY_FALLBACK;
            const recheck = recheckLabel(evt.recheckDueAt);
            return (
              <div key={evt.id} className="rounded-2xl overflow-hidden" style={glassCard}>
                <div className="px-5 py-4">
                  <div className="flex items-start gap-4">
                    <div className={`shrink-0 w-14 h-14 rounded-xl ${sev.bg} flex flex-col items-center justify-center`}>
                      <span className={`text-lg font-black ${sev.color}`}>{evt.glucoseLevel != null ? evt.glucoseLevel.toFixed(1) : '—'}</span>
                      <span className={`text-[8px] font-bold uppercase ${sev.color}`}>mmol/L</span>
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap mb-1.5">
                        <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${sev.bg} ${sev.color}`}>{sev.label}</span>
                        {evt.neonatal && <span className="text-[10px] font-bold px-2 py-1 rounded-lg bg-fuchsia-500/15 text-fuchsia-400">NEONATAL</span>}
                        {evt.glucoseSource && <span className={`text-[10px] px-2 py-1 rounded-lg ${isDark ? 'bg-white/5 text-slate-300' : 'bg-slate-100 text-slate-600'}`}>{evt.glucoseSource.replace(/_/g, ' ')}</span>}
                        {recheck && (
                          <span className={`text-[10px] font-bold px-2 py-1 rounded-lg inline-flex items-center gap-1 ${recheck.overdue ? 'bg-red-500/15 text-red-500' : 'bg-cyan-500/10 text-cyan-400'}`}>
                            <Timer className="w-3 h-3" />{recheck.text}
                          </span>
                        )}
                      </div>
                      <div className="flex items-center gap-3 flex-wrap">
                        <span className={`text-[10px] ${text.muted}`}>Trigger: <span className={text.body}>{evt.triggerReason?.replace(/_/g, ' ') || '—'}</span></span>
                        <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}><Clock className="w-3 h-3" />{format(new Date(evt.detectedAt), 'dd MMM HH:mm')}</span>
                        {evt.detectedByName && <span className={`text-[10px] ${text.muted}`}>by {evt.detectedByName}</span>}
                      </div>
                      {evt.treatmentGiven && (
                        <p className={`text-[11px] mt-2 ${text.body}`}>
                          <Syringe className="w-3 h-3 inline mr-1 text-emerald-500" />{evt.treatmentGiven}
                          {evt.treatmentGivenAt && <span className={`ml-1 ${text.muted}`}>at {format(new Date(evt.treatmentGivenAt), 'HH:mm')}</span>}
                          {evt.treatmentGivenByName && <span className={`ml-1 ${text.muted}`}>by {evt.treatmentGivenByName}</span>}
                        </p>
                      )}
                      {evt.repeatGlucoseLevel != null && (
                        <p className={`text-[11px] mt-1 ${text.body}`}>
                          <FlaskConical className="w-3 h-3 inline mr-1 text-cyan-500" />Repeat: {evt.repeatGlucoseLevel.toFixed(1)} mmol/L
                          {evt.repeatGlucoseAt && <span className={`ml-1 ${text.muted}`}>at {format(new Date(evt.repeatGlucoseAt), 'HH:mm')}</span>}
                        </p>
                      )}
                    </div>
                  </div>
                </div>

                {/* Actions */}
                <div className="px-5 py-3 border-t flex items-center gap-2 flex-wrap" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
                  {!evt.treatmentGiven && treatFor !== evt.id && (
                    <button onClick={() => { setTreatFor(evt.id); setTreatment(''); }} className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-500 hover:bg-purple-500/20 transition-colors">
                      <Syringe className="w-3.5 h-3.5" />Record Treatment
                    </button>
                  )}
                  {repeatFor !== evt.id && (
                    <button onClick={() => { setRepeatFor(evt.id); setRepeatGlucose(''); }} className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors">
                      <FlaskConical className="w-3.5 h-3.5" />Record Repeat Glucose
                    </button>
                  )}
                  <button onClick={() => resolve(evt.id)} disabled={busy} className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors disabled:opacity-50">
                    {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}Resolve
                  </button>
                </div>

                {treatFor === evt.id && (
                  <div className="px-5 py-3 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mb-2">
                      {TREATMENT_OPTIONS.map((opt) => (
                        <button key={opt} onClick={() => setTreatment(opt)}
                          className={`px-3 py-2 text-[11px] font-medium rounded-xl border text-left transition-all ${treatment === opt ? 'bg-purple-500/15 border-purple-500/40 text-purple-500' : isDark ? 'border-white/10 text-slate-300 hover:bg-white/5' : 'border-slate-200 text-slate-600 hover:bg-purple-50'}`}>
                          <Syringe className="w-3 h-3 inline mr-1.5" />{opt}
                        </button>
                      ))}
                    </div>
                    <button onClick={() => submitTreatment(evt.id)} disabled={!treatment || busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-500 hover:bg-purple-500/20 transition-colors disabled:opacity-50">Confirm treatment</button>
                  </div>
                )}
                {repeatFor === evt.id && (
                  <div className="px-5 py-3 border-t flex items-center gap-2 flex-wrap" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
                    <input type="number" step={repeatUnit === 'MG_DL' ? '1' : '0.1'} min="0" max={repeatUnit === 'MG_DL' ? '600' : '40'} value={repeatGlucose}
                      onChange={(e) => setRepeatGlucose(e.target.value)} placeholder={repeatUnit === 'MG_DL' ? 'e.g. 75' : 'e.g. 4.2'}
                      className="w-32 px-3 py-2.5 rounded-xl text-sm outline-none" style={glassInner} />
                    {/* Unit toggle — a mg/dL glucometer reading is converted server-side */}
                    <div className={`inline-flex rounded-xl p-0.5 ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
                      {(['MMOL_L', 'MG_DL'] as const).map((u) => (
                        <button key={u} type="button" onClick={() => setRepeatUnit(u)}
                          className={`px-2.5 py-1.5 text-[10px] font-bold rounded-lg transition-colors ${repeatUnit === u ? 'bg-cyan-500/20 text-cyan-500' : isDark ? 'text-slate-400 hover:text-slate-200' : 'text-slate-500 hover:text-slate-700'}`}>
                          {u === 'MMOL_L' ? 'mmol/L' : 'mg/dL'}
                        </button>
                      ))}
                    </div>
                    {repeatGlucose && !isNaN(parseFloat(repeatGlucose)) && repeatUnit === 'MG_DL' && (
                      <span className={`text-[10px] ${text.muted}`}>= {(parseFloat(repeatGlucose) / 18).toFixed(1)} mmol/L</span>
                    )}
                    <button onClick={() => submitRepeat(evt.id)} disabled={!repeatGlucose || busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors disabled:opacity-50">Save</button>
                  </div>
                )}
              </div>
            );
          })}

          {history.length > 0 && (
            <div className="rounded-2xl p-5" style={glassCard}>
              <h4 className={`text-xs font-bold uppercase tracking-wider mb-2 ${text.muted}`}>Resolved ({history.length})</h4>
              <div className="space-y-2">
                {history.map((evt) => {
                  const sev = SEVERITY_CONFIG[evt.severity] || SEVERITY_FALLBACK;
                  return (
                    <div key={evt.id} className="flex items-center gap-3 text-[11px]">
                      <span className={`font-bold ${sev.color}`}>{evt.glucoseLevel != null ? evt.glucoseLevel.toFixed(1) : '—'} mmol/L</span>
                      <span className={text.muted}>{sev.label}</span>
                      <span className={text.muted}>{format(new Date(evt.detectedAt), 'dd MMM HH:mm')}</span>
                      {evt.resolvedByName && <span className={text.muted}>· resolved by {evt.resolvedByName}</span>}
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
