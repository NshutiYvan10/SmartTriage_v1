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
import {
  hypoglycemiaApi, protocolIncomplete, treatmentOptionsFor, protocolHintFor,
  recheckCountdown, type HypoglycemiaEvent, type HypoglycemiaCheckResponse,
} from '@/api/hypoglycemia';
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

/* Treatment options + recheck countdown come from api/hypoglycemia.ts (shared with
   the hospital dashboard) — neonates are never offered 50% dextrose. */

/* ═══════════════════════════════════════════════════════════════
   HypoglycemiaEventBanner — page-level signage on the patient chart.

   Rendered ABOVE the tab bar so an UNRESOLVED hypoglycemia event — fatal in
   minutes — is visible from EVERY tab: severity + glucose value, treatment
   state, and the mandatory 15-minute recheck countdown (pulsing red once
   overdue). Previously the event was only visible inside the Glucose tab;
   a doctor on Medications had no idea the patient was mid-protocol. Renders
   nothing when the visit has no open event.
   ═══════════════════════════════════════════════════════════════ */
export function HypoglycemiaEventBanner({ visitId, onOpen }: { visitId: string; onOpen?: () => void }) {
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';
  const wsGen = useWebSocketGeneration();
  const [open, setOpen] = useState<HypoglycemiaEvent[]>([]);
  const [, setTick] = useState(0);

  const load = useCallback(async () => {
    try {
      const data = await hypoglycemiaApi.getForVisit(visitId);
      setOpen((Array.isArray(data) ? data : []).filter((e) => !e.resolved));
    } catch {
      // Banner is best-effort signage — the Glucose tab surfaces load errors.
      setOpen([]);
    }
  }, [visitId]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToHypoglycemia(hospitalId, (event: { visitId?: string }) => {
      if (event?.visitId === visitId) load();
    });
    return () => unsub();
  }, [hospitalId, visitId, load, wsGen]);
  useEffect(() => {
    const t = setInterval(() => setTick((v) => v + 1), 30000);
    return () => clearInterval(t);
  }, []);

  if (open.length === 0) return null;
  // Show the most urgent open event: recheck-overdue first, then severest.
  const evt = [...open].sort((a, b) => {
    const ao = recheckCountdown(a.recheckDueAt)?.overdue ? 0 : 1;
    const bo = recheckCountdown(b.recheckDueAt)?.overdue ? 0 : 1;
    if (ao !== bo) return ao - bo;
    const rank = (e: HypoglycemiaEvent) => (e.severity === 'SEVERE' ? 0 : e.severity === 'MODERATE' ? 1 : 2);
    return rank(a) - rank(b);
  })[0];
  const sev = SEVERITY_CONFIG[evt.severity] || SEVERITY_FALLBACK;
  const recheck = recheckCountdown(evt.recheckDueAt);

  return (
    <div className={`rounded-2xl px-4 py-3 ${sev.bg} border border-red-500/25 flex items-center gap-3 flex-wrap animate-fade-up`}>
      <Droplets className={`w-5 h-5 shrink-0 ${sev.color}`} />
      <span className={`text-xs font-black uppercase tracking-wide ${sev.color}`}>
        {sev.label} HYPOGLYCEMIA{evt.glucoseLevel != null ? ` — ${evt.glucoseLevel.toFixed(1)} mmol/L` : ''}
      </span>
      {evt.neonatal && (
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-lg bg-fuchsia-500/15 text-fuchsia-400">NEONATAL</span>
      )}
      {evt.treatmentGiven ? (
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-lg bg-emerald-500/15 text-emerald-500 inline-flex items-center gap-1">
          <Syringe className="w-3 h-3" /> {evt.treatmentGiven}
        </span>
      ) : (
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-lg bg-amber-500/15 text-amber-500 animate-pulse inline-flex items-center gap-1">
          <AlertTriangle className="w-3 h-3" /> AWAITING TREATMENT
        </span>
      )}
      <span className="ml-auto inline-flex items-center gap-2">
        {recheck && (
          <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg inline-flex items-center gap-1 ${
            recheck.overdue ? 'bg-red-600/20 text-red-600 animate-pulse' : 'bg-cyan-500/10 text-cyan-500'}`}>
            <Timer className="w-3 h-3" /> {recheck.text}
          </span>
        )}
        {onOpen && (
          <button onClick={onOpen}
            className="text-[10px] font-bold px-2.5 py-1 rounded-lg bg-red-500/15 text-red-500 hover:bg-red-500/25 transition-colors">
            Manage
          </button>
        )}
      </span>
    </div>
  );
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
  const [resolveFor, setResolveFor] = useState<string | null>(null);
  const [resolveReason, setResolveReason] = useState('');
  /** Verdict of the last "Run glucose check" — previously DISCARDED, so a check
      that (correctly) filed nothing looked like the button did nothing at all. */
  const [checkResult, setCheckResult] = useState<HypoglycemiaCheckResponse | null>(null);

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

  /* Run the enforcement check and SHOW ITS VERDICT. Only propagate the heavy
     page-level refresh (onChanged → VisitDetailPage.loadData → full-page spinner,
     which reads as a "reload") when the check actually filed an event. */
  const runCheck = async () => {
    setBusy(true);
    setError(null);
    setCheckResult(null);
    try {
      const res = await hypoglycemiaApi.enforce(visitId);
      setCheckResult(res);
      await load();
      if (res.eventId || res.isHypoglycemic) onChanged?.();
    } catch (err) {
      fail(err, 'Failed to run glucose check');
    } finally {
      setBusy(false);
    }
  };

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

  /* Resolving is FREE only when the recheck protocol completed; otherwise the
     backend demands a documented reason — open the reason input instead. */
  const resolve = (evt: HypoglycemiaEvent) => {
    if (protocolIncomplete(evt)) {
      setResolveFor(evt.id);
      setResolveReason('');
      return;
    }
    runAction(() => hypoglycemiaApi.resolve(evt.id), 'Failed to resolve event');
  };

  const resolveWithReason = (id: string) => {
    if (!resolveReason.trim()) return;
    runAction(
      () => hypoglycemiaApi.resolve(id, resolveReason.trim()),
      'Failed to resolve event',
      () => { setResolveFor(null); setResolveReason(''); });
  };

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

        {/* ── Check verdict — every "Run glucose check" now says what it concluded. ── */}
        {checkResult && (() => {
          const r = checkResult;
          const tone = r.isHypoglycemic
            ? { bg: 'bg-red-500/10 border-red-500/25', text: 'text-red-500', Icon: AlertTriangle }
            : r.staleReading
              ? { bg: 'bg-amber-500/10 border-amber-500/25', text: 'text-amber-500', Icon: Timer }
              : r.glucoseValue == null && r.requiresCheck
                ? { bg: 'bg-amber-500/10 border-amber-500/25', text: 'text-amber-500', Icon: Timer }
                : r.glucoseValue == null
                  ? { bg: 'bg-slate-500/10 border-slate-500/20', text: text.body, Icon: FlaskConical }
                  : { bg: 'bg-emerald-500/10 border-emerald-500/25', text: 'text-emerald-500', Icon: CheckCircle2 };
          const Icon = tone.Icon;
          return (
            <div className={`mt-3 rounded-xl px-3 py-2.5 border ${tone.bg}`}>
              <div className="flex items-start gap-2">
                <Icon className={`w-4 h-4 shrink-0 mt-0.5 ${tone.text}`} />
                <div className="flex-1 min-w-0">
                  {r.isHypoglycemic ? (
                    <>
                      <p className={`text-[11px] font-bold ${tone.text}`}>
                        HYPOGLYCEMIA {r.severity} — {r.glucoseValue?.toFixed(1)} mmol/L. Event filed, care team paged.
                      </p>
                      {r.treatmentProtocol && <p className={`text-[10px] mt-1 ${text.muted}`}>{r.treatmentProtocol}</p>}
                    </>
                  ) : r.staleReading ? (
                    <>
                      <p className={`text-[11px] font-bold ${tone.text}`}>
                        STALE READING — latest glucose {r.glucoseValue?.toFixed(1)} mmol/L
                        {r.glucoseSource ? ` (${r.glucoseSource.toLowerCase()})` : ''} is{' '}
                        {r.readingAgeMinutes != null && r.readingAgeMinutes >= 90
                          ? `${Math.floor(r.readingAgeMinutes / 60)} h ${r.readingAgeMinutes % 60} min`
                          : `${r.readingAgeMinutes ?? '?'} min`} old.
                      </p>
                      <p className={`text-[10px] mt-1 ${text.muted}`}>
                        This patient is on {r.monitoringTier?.toLowerCase() ?? 'scheduled'} glucose monitoring
                        (every {r.monitoringIntervalMinutes} min) — an old value cannot reassure.
                        Recheck a bedside glucose now; the zone has been reminded.
                      </p>
                    </>
                  ) : r.glucoseValue == null && r.requiresCheck ? (
                    <p className={`text-[11px] font-bold ${tone.text}`}>
                      GLUCOSE CHECK {r.checkMandatory ? 'REQUIRED' : 'RECOMMENDED'} — no reading on file
                      {r.triggerReasons?.length ? ` (${r.triggerReasons.join(', ').replace(/_/g, ' ')})` : ''}.
                      Record a bedside glucose now{r.checkMandatory ? ' — the care team has been paged' : ''}.
                    </p>
                  ) : r.glucoseValue == null ? (
                    <p className={`text-[11px] font-semibold ${text.body}`}>
                      No glucose reading on file and no mandatory-check triggers (patient alert, not a known
                      diabetic). Nothing filed — record a bedside glucose in Vitals to screen.
                    </p>
                  ) : (
                    <>
                      <p className={`text-[11px] font-bold ${tone.text}`}>
                        Latest glucose {r.glucoseValue.toFixed(1)} mmol/L
                        {r.glucoseSource ? ` (${r.glucoseSource.toLowerCase()}` : ''}
                        {r.glucoseSource && r.readingAgeMinutes != null ? `, ${r.readingAgeMinutes} min ago)` : r.glucoseSource ? ')' : ''}
                        {' '}— {r.severity}. No event filed.
                      </p>
                      {r.monitoringTier && r.nextDueAt && (
                        <p className={`text-[10px] mt-1 ${text.muted}`}>
                          On scheduled monitoring: {r.monitoringTier.toLowerCase()}, every {r.monitoringIntervalMinutes} min —
                          next reading due {new Date(r.nextDueAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}.
                        </p>
                      )}
                    </>
                  )}
                </div>
                <button onClick={() => setCheckResult(null)} className={`text-[10px] font-bold ${text.muted} hover:opacity-70`}>✕</button>
              </div>
            </div>
          );
        })()}
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
            const recheck = recheckCountdown(evt.recheckDueAt);
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
                          <FlaskConical className="w-3 h-3 inline mr-1 text-cyan-500" />Repeat:{' '}
                          {evt.repeatGlucoseUnit === 'MG_DL'
                            ? `${Math.round(evt.repeatGlucoseLevel * 18)} mg/dL (${evt.repeatGlucoseLevel.toFixed(1)} mmol/L)`
                            : `${evt.repeatGlucoseLevel.toFixed(1)} mmol/L`}
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
                  <button onClick={() => resolve(evt)} disabled={busy}
                    title={protocolIncomplete(evt) ? 'Protocol incomplete — resolving requires a documented reason' : 'Resolve this event'}
                    className={`inline-flex items-center gap-2 px-4 py-2 text-[11px] font-bold rounded-xl transition-colors disabled:opacity-50 ${
                      protocolIncomplete(evt) ? `${text.muted} hover:bg-white/5` : 'bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20'}`}>
                    {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                    {protocolIncomplete(evt) ? 'Resolve…' : 'Resolve'}
                  </button>
                </div>

                {/* Guarded resolve — protocol incomplete, reason required */}
                {resolveFor === evt.id && (
                  <div className="px-5 py-3 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
                    <p className={`text-[10px] mb-2 ${text.muted}`}>
                      {evt.repeatGlucoseLevel == null
                        ? 'No post-treatment repeat glucose is on record — record it, or document why this event is being closed (e.g. patient departed, filed in error).'
                        : 'The repeat glucose on record is still hypoglycemic — document why this event is being closed (e.g. escalated to ICU care).'}
                    </p>
                    <div className="flex items-center gap-2 flex-wrap">
                      <input type="text" value={resolveReason} onChange={(e) => setResolveReason(e.target.value)}
                        placeholder="Reason (required)" style={glassInner}
                        className={`w-80 px-3 py-2 text-xs rounded-xl focus:outline-none focus:ring-2 focus:ring-emerald-500/20 ${text.body}`} />
                      <button onClick={() => resolveWithReason(evt.id)} disabled={!resolveReason.trim() || busy}
                        className="px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors disabled:opacity-40">
                        Resolve
                      </button>
                      <button onClick={() => { setResolveFor(null); setResolveReason(''); }}
                        className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors ${text.muted} hover:bg-white/5`}>
                        Cancel
                      </button>
                    </div>
                  </div>
                )}

                {treatFor === evt.id && (
                  <div className="px-5 py-3 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
                    <p className={`text-[10px] mb-2 ${evt.neonatal ? 'text-fuchsia-400 font-bold' : text.muted}`}>
                      {protocolHintFor(evt.neonatal)}
                    </p>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mb-2">
                      {treatmentOptionsFor(evt.neonatal).map((opt) => (
                        <button key={opt} onClick={() => setTreatment(opt)}
                          className={`px-3 py-2 text-[11px] font-medium rounded-xl border text-left transition-all ${treatment === opt ? 'bg-purple-500/15 border-purple-500/40 text-purple-500' : isDark ? 'border-white/10 text-slate-300 hover:bg-white/5' : 'border-slate-200 text-slate-600 hover:bg-purple-50'}`}>
                          <Syringe className="w-3 h-3 inline mr-1.5" />{opt}
                        </button>
                      ))}
                    </div>
                    <button onClick={() => submitTreatment(evt.id)} disabled={!treatment.trim() || busy} className="px-4 py-2 text-[11px] font-bold rounded-xl bg-purple-500/10 text-purple-500 hover:bg-purple-500/20 transition-colors disabled:opacity-50">Confirm treatment</button>
                  </div>
                )}
                {repeatFor === evt.id && (
                  <div className="px-5 py-3 border-t flex items-center gap-2 flex-wrap" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
                    <input type="number" step={repeatUnit === 'MG_DL' ? '1' : '0.1'} min="0" max={repeatUnit === 'MG_DL' ? '600' : '33.3'} value={repeatGlucose}
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
