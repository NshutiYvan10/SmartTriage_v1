/* ═══════════════════════════════════════════════════════════════
   Fast Track Panel — per-visit activation entry point (chart tab)

   The natural place a clinician STARTS a time-critical stroke / MI
   pathway: from the patient's chart. Before this panel the activate
   endpoint had no UI caller at all — the pathway was un-startable.

   Shows the engine's non-binding recommendation, an activation form,
   and (once active) the live status, door-to timers, ECG/CT recording,
   thrombolysis advisory, acknowledge / complete / cancel, and the
   time-stamped action trail.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Zap, Brain, Heart, Loader2, Play, RefreshCw, AlertTriangle, Clock,
  Scan, Activity, UserCheck, FileCheck, XCircle, Lightbulb,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import {
  fasttrackApi, type FastTrackActivation, type FastTrackType, type FastTrackRecommendation,
} from '@/api/fasttrack';
import { subscribeToFastTrack } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { ApiError } from '@/api/client';
import { format } from 'date-fns';

const TYPE_LABEL: Record<FastTrackType, string> = {
  STROKE_SUSPECTED: 'Suspected Stroke',
  TIA_SUSPECTED: 'Suspected TIA',
  STEMI_SUSPECTED: 'Suspected STEMI',
  NSTEMI_SUSPECTED: 'Suspected NSTEMI / ACS',
};
const STROKE_TYPES: FastTrackType[] = ['STROKE_SUSPECTED', 'TIA_SUSPECTED'];
const isStrokeType = (t: FastTrackType) => STROKE_TYPES.includes(t);

const STATUS_FALLBACK = { color: 'text-slate-400', bg: 'bg-slate-500/10' };
const STATUS_CONFIG: Record<string, { color: string; bg: string }> = {
  ACTIVATED:               { color: 'text-orange-400', bg: 'bg-orange-500/10' },
  ECG_ORDERED:             { color: 'text-amber-400', bg: 'bg-amber-500/10' },
  CT_ORDERED:              { color: 'text-amber-400', bg: 'bg-amber-500/10' },
  ECG_COMPLETED:           { color: 'text-cyan-400', bg: 'bg-cyan-500/10' },
  CT_COMPLETED:            { color: 'text-cyan-400', bg: 'bg-cyan-500/10' },
  THROMBOLYSIS_CONSIDERED: { color: 'text-indigo-400', bg: 'bg-indigo-500/10' },
  INTERVENTION_STARTED:    { color: 'text-blue-400', bg: 'bg-blue-500/10' },
  TRANSFERRED_FOR_PCI:     { color: 'text-blue-400', bg: 'bg-blue-500/10' },
  COMPLETED:               { color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
  CANCELLED:               { color: 'text-slate-400', bg: 'bg-slate-500/10' },
};

const TERMINAL = new Set(['COMPLETED', 'CANCELLED']);

function toIso(localValue: string): string | undefined {
  if (!localValue) return undefined;
  const d = new Date(localValue);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

interface FastTrackPanelProps {
  visitId: string;
  /** Called after a successful activation/transition so the chart can refresh
   *  its Alerts tab (a fast-track activation raises a CRITICAL alert). */
  onChanged?: () => void;
}

export function FastTrackPanel({ visitId, onChanged }: FastTrackPanelProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';
  const wsGen = useWebSocketGeneration();

  const [activation, setActivation] = useState<FastTrackActivation | null>(null);
  const [recommendation, setRecommendation] = useState<FastTrackRecommendation | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, setTick] = useState(0);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Activation form
  const [form, setForm] = useState({
    fastTrackType: '' as FastTrackType | '',
    symptomOnsetTime: '',
    chestPainOnsetTime: '',
    beFastScore: '',
    nihssScore: '',
    notes: '',
  });

  // Inline ECG/CT capture
  const [ecgOpen, setEcgOpen] = useState(false);
  const [ecg, setEcg] = useState({ result: '', stElevation: false });
  const [ctOpen, setCtOpen] = useState(false);
  const [ct, setCt] = useState({ result: '', hemorrhagic: false });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [act, rec] = await Promise.allSettled([
        fasttrackApi.getForVisit(visitId),
        fasttrackApi.getRecommendation(visitId),
      ]);
      if (act.status === 'fulfilled') setActivation(act.value ?? null);
      if (rec.status === 'fulfilled') setRecommendation(rec.value ?? null);
    } catch (err) {
      console.error('Failed to load fast-track:', err);
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
    const unsub = subscribeToFastTrack(hospitalId, (event: { visitId?: string }) => {
      if (event?.visitId === visitId) load();
    });
    return () => unsub();
  }, [hospitalId, visitId, load, wsGen]);

  const fail = (err: unknown, fallback: string) => {
    setError(err instanceof ApiError ? err.message : err instanceof Error ? err.message : fallback);
  };

  const active = activation && !TERMINAL.has(activation.status) ? activation : null;

  const activate = async () => {
    if (!form.fastTrackType) { setError('Select a pathway type first.'); return; }
    setBusy(true);
    setError(null);
    try {
      const stroke = isStrokeType(form.fastTrackType);
      const nihss = form.nihssScore.trim() !== '' && Number.isFinite(Number(form.nihssScore))
        ? Number(form.nihssScore) : undefined;
      if (nihss !== undefined && (nihss < 0 || nihss > 42)) {
        setError('NIHSS must be between 0 and 42.');
        setBusy(false);
        return;
      }
      await fasttrackApi.activate({
        visitId,
        fastTrackType: form.fastTrackType,
        symptomOnsetTime: stroke ? toIso(form.symptomOnsetTime) : undefined,
        chestPainOnsetTime: !stroke ? toIso(form.chestPainOnsetTime) : undefined,
        beFastScore: stroke && form.beFastScore.trim() !== '' ? form.beFastScore.trim() : undefined,
        nihssScore: stroke ? nihss : undefined,
        notes: form.notes.trim() !== '' ? form.notes.trim() : undefined,
      });
      setForm({ fastTrackType: '', symptomOnsetTime: '', chestPainOnsetTime: '', beFastScore: '', nihssScore: '', notes: '' });
      await load();
      onChanged?.();
    } catch (err) {
      fail(err, 'Failed to activate fast track');
    } finally {
      setBusy(false);
    }
  };

  const runAction = async (fn: () => Promise<unknown>, fallback: string) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await load();
      onChanged?.();
    } catch (err) {
      fail(err, fallback);
    } finally {
      setBusy(false);
    }
  };

  const submitEcg = () => runAction(async () => {
    await fasttrackApi.recordEcg(active!.id, { ecgResult: ecg.result, stElevation: ecg.stElevation });
    setEcgOpen(false); setEcg({ result: '', stElevation: false });
  }, 'Failed to record ECG');

  const submitCt = () => runAction(async () => {
    await fasttrackApi.recordCt(active!.id, { ctResult: ct.result, isHemorrhagic: ct.hemorrhagic });
    setCtOpen(false); setCt({ result: '', hemorrhagic: false });
  }, 'Failed to record CT');

  const complete = () => {
    // eslint-disable-next-line no-alert
    const outcome = window.prompt('Outcome / disposition (optional):', '') ?? undefined;
    runAction(() => fasttrackApi.complete(active!.id, { outcome }), 'Failed to complete fast track');
  };
  const cancel = () => {
    // eslint-disable-next-line no-alert
    const reason = window.prompt('Reason for cancelling this fast track:', '');
    if (reason === null) return;
    runAction(() => fasttrackApi.cancel(active!.id, { reason }), 'Failed to cancel fast track');
  };

  if (loading) {
    return <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-cyan-500" /></div>;
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-yellow-500/15 flex items-center justify-center shrink-0">
            <Zap className="w-5 h-5 text-yellow-500" />
          </div>
          <div className="flex-1">
            <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Fast Track</h3>
            <p className={`text-xs ${text.muted}`}>Time-critical stroke &amp; MI/ACS pathway activation and timing.</p>
          </div>
          {activation && (
            <button onClick={load} disabled={loading}
              className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${isDark ? 'bg-white/5 hover:bg-white/10' : 'bg-slate-100 hover:bg-slate-200'}`} title="Refresh">
              <RefreshCw className={`w-4 h-4 ${text.muted}`} />
            </button>
          )}
        </div>

        {error && (
          <div className="mt-3 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[11px] font-semibold text-red-500">{error}</p>
          </div>
        )}
      </div>

      {/* Engine recommendation (advisory) — only when nothing active */}
      {!active && recommendation && (
        <div className="rounded-2xl p-4" style={{ ...glassInner, border: '1px solid rgba(234,179,8,0.25)' }}>
          <div className="flex items-start gap-2">
            <Lightbulb className="w-4 h-4 text-yellow-500 shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <p className={`text-xs font-bold ${text.heading}`}>
                Decision support suggests: {TYPE_LABEL[recommendation.type]}{' '}
                <span className={`font-normal ${text.muted}`}>({Math.round(recommendation.confidence * 100)}% confidence)</span>
              </p>
              <p className={`text-[11px] mt-1 leading-relaxed ${text.body}`}>{recommendation.reasoning}</p>
              <p className={`text-[10px] mt-1 ${text.muted}`}>Advisory only — confirm clinically before activating.</p>
              <button
                onClick={() => setForm((f) => ({ ...f, fastTrackType: recommendation.type }))}
                className="mt-2 inline-flex items-center gap-1.5 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-yellow-500/15 text-yellow-600 hover:bg-yellow-500/25 transition-colors"
              >
                Use this suggestion
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Activation form (no active pathway) */}
      {!active && (
        <div className="rounded-2xl p-5" style={glassCard}>
          <h4 className={`text-xs font-bold uppercase tracking-wider mb-3 ${text.muted}`}>
            {activation ? 'Re-activate a pathway' : 'Activate a pathway'}
          </h4>
          <div className="grid grid-cols-2 gap-2 mb-3">
            {(Object.keys(TYPE_LABEL) as FastTrackType[]).map((t) => {
              const selected = form.fastTrackType === t;
              const stroke = isStrokeType(t);
              return (
                <button key={t} onClick={() => setForm((f) => ({ ...f, fastTrackType: t }))}
                  className={`flex items-center gap-2 px-3 py-2.5 rounded-xl text-[11px] font-bold border transition-all ${
                    selected
                      ? (stroke ? 'bg-purple-500/15 text-purple-500 border-purple-500/30' : 'bg-red-500/15 text-red-500 border-red-500/30')
                      : isDark ? 'bg-white/5 text-slate-300 border-white/5 hover:bg-white/10' : 'bg-slate-50 text-slate-600 border-slate-200/50 hover:bg-slate-100'
                  }`}>
                  {stroke ? <Brain className="w-3.5 h-3.5 shrink-0" /> : <Heart className="w-3.5 h-3.5 shrink-0" />}
                  <span className="truncate">{TYPE_LABEL[t]}</span>
                </button>
              );
            })}
          </div>

          {form.fastTrackType && (
            <div className="space-y-3">
              {isStrokeType(form.fastTrackType) ? (
                <>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div>
                      <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>Symptom onset / last-known-well</label>
                      <input type="datetime-local" value={form.symptomOnsetTime}
                        onChange={(e) => setForm((f) => ({ ...f, symptomOnsetTime: e.target.value }))}
                        className="w-full px-3 py-2.5 rounded-xl text-sm outline-none" style={glassInner} />
                      <p className={`text-[9px] mt-1 ${text.muted}`}>Drives the thrombolysis-window advisory</p>
                    </div>
                    <div>
                      <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>NIHSS (0–42)</label>
                      <input type="number" min="0" max="42" inputMode="numeric" value={form.nihssScore}
                        onChange={(e) => setForm((f) => ({ ...f, nihssScore: e.target.value }))}
                        placeholder="e.g. 8" className="w-full px-3 py-2.5 rounded-xl text-sm outline-none" style={glassInner} />
                    </div>
                  </div>
                  <div>
                    <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>BE-FAST findings</label>
                    <input type="text" value={form.beFastScore}
                      onChange={(e) => setForm((f) => ({ ...f, beFastScore: e.target.value }))}
                      placeholder="e.g. Face droop + Arm weakness + Speech" className="w-full px-3 py-2.5 rounded-xl text-sm outline-none" style={glassInner} />
                  </div>
                </>
              ) : (
                <div>
                  <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>Chest-pain / symptom onset</label>
                  <input type="datetime-local" value={form.chestPainOnsetTime}
                    onChange={(e) => setForm((f) => ({ ...f, chestPainOnsetTime: e.target.value }))}
                    className="w-full px-3 py-2.5 rounded-xl text-sm outline-none" style={glassInner} />
                  <p className={`text-[9px] mt-1 ${text.muted}`}>An ECG is auto-ordered on activation</p>
                </div>
              )}
              <div>
                <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>Notes</label>
                <textarea rows={2} value={form.notes}
                  onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))}
                  placeholder="Optional context" className="w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none" style={glassInner} />
              </div>
              <button onClick={activate} disabled={busy}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-xl bg-gradient-to-r from-orange-500 to-red-500 text-white shadow-md hover:-translate-y-0.5 transition-all disabled:opacity-50">
                {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
                Activate {TYPE_LABEL[form.fastTrackType]}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Active pathway */}
      {active && <ActiveCard
        a={active}
        busy={busy}
        ecgOpen={ecgOpen} setEcgOpen={setEcgOpen} ecg={ecg} setEcg={setEcg} submitEcg={submitEcg}
        ctOpen={ctOpen} setCtOpen={setCtOpen} ct={ct} setCt={setCt} submitCt={submitCt}
        onAcknowledge={() => runAction(() => fasttrackApi.acknowledge(active.id), 'Failed to acknowledge')}
        onComplete={complete} onCancel={cancel}
        glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text}
      />}

      {/* Terminal (completed/cancelled) summary */}
      {activation && TERMINAL.has(activation.status) && !active && (
        <div className="rounded-2xl p-5" style={glassCard}>
          <p className={`text-xs ${text.muted}`}>
            Last fast track ({TYPE_LABEL[activation.fastTrackType]}) {activation.status.toLowerCase()}
            {activation.completedByName ? ` by ${activation.completedByName}` : ''}
            {activation.completedAt ? ` at ${format(new Date(activation.completedAt), 'dd MMM HH:mm')}` : ''}.
            {activation.outcome ? ` Outcome: ${activation.outcome}` : ''}
          </p>
        </div>
      )}
    </div>
  );
}

/* ── Active-pathway card ── */
function ActiveCard({
  a, busy, ecgOpen, setEcgOpen, ecg, setEcg, submitEcg,
  ctOpen, setCtOpen, ct, setCt, submitCt, onAcknowledge, onComplete, onCancel,
  glassCard, glassInner, isDark, text,
}: any) {
  const stroke = isStrokeType(a.fastTrackType);
  const statusCfg = STATUS_CONFIG[a.status] || STATUS_FALLBACK;
  return (
    <div className="rounded-2xl overflow-hidden" style={glassCard}>
      <div className="px-5 py-4">
        <div className="flex items-start gap-4">
          <div className={`shrink-0 w-12 h-12 rounded-xl flex items-center justify-center ${stroke ? 'bg-purple-500/10 border border-purple-500/20' : 'bg-red-500/10 border border-red-500/20'}`}>
            {stroke ? <Brain className="w-6 h-6 text-purple-400" /> : <Heart className="w-6 h-6 text-red-400" />}
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 flex-wrap mb-1.5">
              <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${statusCfg.bg} ${statusCfg.color}`}>{a.status.replace(/_/g, ' ')}</span>
              <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${stroke ? 'bg-purple-500/10 text-purple-400' : 'bg-red-500/10 text-red-400'}`}>{TYPE_LABEL[a.fastTrackType as FastTrackType]}</span>
              {a.isHemorrhagic === true && <span className="text-[10px] font-bold px-2 py-1 rounded-lg bg-red-500/15 text-red-500"><AlertTriangle className="w-3 h-3 inline mr-1" />Hemorrhagic</span>}
              {a.stElevation === true && <span className="text-[10px] font-bold px-2 py-1 rounded-lg bg-red-500/15 text-red-500"><AlertTriangle className="w-3 h-3 inline mr-1" />ST Elevation</span>}
              {a.acknowledgedAt && <span className="text-[10px] font-bold px-2 py-1 rounded-lg bg-emerald-500/10 text-emerald-400"><UserCheck className="w-3 h-3 inline mr-1" />Acknowledged</span>}
            </div>
            <div className="flex items-center gap-3 flex-wrap">
              <p className={`text-xs ${text.body}`}>Activated by <span className={`font-semibold ${text.heading}`}>{a.activatedByName || 'Unknown'}</span></p>
              <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}><Clock className="w-3 h-3" />{format(new Date(a.activatedAt), 'dd MMM HH:mm')}</span>
            </div>

            {/* Metrics */}
            <div className="flex items-center gap-3 mt-2 flex-wrap">
              {a.nihssScore != null && a.nihssScore !== undefined && (
                <span className={`text-[10px] px-2 py-0.5 rounded ${isDark ? 'bg-white/5' : 'bg-slate-100'} ${text.body}`}>NIHSS {a.nihssScore}</span>
              )}
              {a.beFastScore && <span className={`text-[10px] px-2 py-0.5 rounded ${isDark ? 'bg-white/5' : 'bg-slate-100'} ${text.body}`}>BE-FAST: {a.beFastScore}</span>}
              {a.doorToEcgMinutes != null && <span className={`text-[10px] font-bold ${a.doorToEcgMinutes <= 10 ? 'text-emerald-400' : 'text-red-400'}`}>Door-to-ECG {a.doorToEcgMinutes}m</span>}
              {a.doorToCtMinutes != null && <span className={`text-[10px] font-bold ${a.doorToCtMinutes <= 25 ? 'text-emerald-400' : 'text-red-400'}`}>Door-to-CT {a.doorToCtMinutes}m</span>}
              {a.doorToNeedleMinutes != null && <span className={`text-[10px] font-bold ${a.doorToNeedleMinutes <= 60 ? 'text-emerald-400' : 'text-red-400'}`}>Door-to-needle {a.doorToNeedleMinutes}m</span>}
            </div>

            {a.ecgResult && <p className={`text-[11px] mt-2 ${text.body}`}>ECG: {a.ecgResult}</p>}
            {a.ctResult && <p className={`text-[11px] mt-1 ${text.body}`}>CT: {a.ctResult}</p>}
            {stroke && a.thrombolysisAdvisory && (
              <p className={`text-[10px] mt-2 leading-relaxed ${a.thrombolysisEligible === true ? 'text-emerald-400' : a.thrombolysisEligible === false ? 'text-red-400' : 'text-amber-400'}`}>💉 {a.thrombolysisAdvisory}</p>
            )}
            {a.lastUpdatedByName && <p className={`text-[10px] mt-2 ${text.muted}`}>Last updated by {a.lastUpdatedByName}</p>}
          </div>
        </div>
      </div>

      {/* Actions */}
      <div className="px-5 py-3 border-t flex items-center gap-2 flex-wrap" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
        {!a.acknowledgedAt && (
          <button onClick={onAcknowledge} disabled={busy} className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-400 hover:bg-cyan-500/20 transition-colors disabled:opacity-50">
            <UserCheck className="w-3.5 h-3.5" />Acknowledge
          </button>
        )}
        {stroke && !a.ctCompletedAt && (
          <button onClick={() => setCtOpen((o: boolean) => !o)} className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-400 hover:bg-purple-500/20 transition-colors">
            <Scan className="w-3.5 h-3.5" />Record CT
          </button>
        )}
        {!stroke && !a.ecgCompletedAt && (
          <button onClick={() => setEcgOpen((o: boolean) => !o)} className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors">
            <Activity className="w-3.5 h-3.5" />Record ECG
          </button>
        )}
        <button onClick={onComplete} disabled={busy} className="inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20 transition-colors disabled:opacity-50">
          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <FileCheck className="w-3.5 h-3.5" />}Complete
        </button>
        <button onClick={onCancel} disabled={busy} className={`inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl transition-colors disabled:opacity-50 ${isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-100'}`}>
          <XCircle className="w-3.5 h-3.5" />Cancel
        </button>
      </div>

      {/* Inline ECG capture */}
      {ecgOpen && (
        <div className="px-5 py-3 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
          <input type="text" value={ecg.result} onChange={(e: any) => setEcg({ ...ecg, result: e.target.value })}
            placeholder="ECG result (e.g. anterior STEMI)" className="w-full px-3 py-2.5 rounded-xl text-sm outline-none mb-2" style={glassInner} />
          <label className="flex items-center gap-2 cursor-pointer mb-2">
            <input type="checkbox" checked={ecg.stElevation} onChange={(e: any) => setEcg({ ...ecg, stElevation: e.target.checked })} className="w-4 h-4" />
            <span className={`text-[11px] font-bold ${text.heading}`}>ST elevation present (upgrades to STEMI)</span>
          </label>
          <button onClick={submitEcg} disabled={!ecg.result.trim() || busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors disabled:opacity-50">Save ECG</button>
        </div>
      )}
      {/* Inline CT capture */}
      {ctOpen && (
        <div className="px-5 py-3 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
          <input type="text" value={ct.result} onChange={(e: any) => setCt({ ...ct, result: e.target.value })}
            placeholder="CT result (e.g. no acute findings)" className="w-full px-3 py-2.5 rounded-xl text-sm outline-none mb-2" style={glassInner} />
          <label className="flex items-center gap-2 cursor-pointer mb-2">
            <input type="checkbox" checked={ct.hemorrhagic} onChange={(e: any) => setCt({ ...ct, hemorrhagic: e.target.checked })} className="w-4 h-4" />
            <span className={`text-[11px] font-bold ${text.heading}`}>Hemorrhagic (thrombolysis contraindicated)</span>
          </label>
          <button onClick={submitCt} disabled={!ct.result.trim() || busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-400 hover:bg-purple-500/20 transition-colors disabled:opacity-50">Save CT</button>
        </div>
      )}
    </div>
  );
}
