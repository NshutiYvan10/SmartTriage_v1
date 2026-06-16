/* ═══════════════════════════════════════════════════════════════
   Sepsis Panel — per-visit screening entry point (chart tab)

   This is the natural place a clinician starts a sepsis screen:
   from the patient's chart, with one click, using the latest
   recorded vitals. It renders the result inline (qSOFA / SIRS /
   status + safety caveats), drives the 1-hour bundle for a positive
   screen, and lists the screening history for the visit.

   It is the only UI caller of sepsisApi.screen — before this panel
   the screening endpoint had no front-door at all.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  ShieldAlert, Stethoscope, Play, Loader2, CheckCircle2, Circle,
  Clock, Activity, Droplets, Syringe, Pill, FlaskConical, RotateCcw,
  AlertTriangle, RefreshCw, History,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { sepsisApi, type SepsisScreening, type SepsisScreeningRequest } from '@/api/sepsis';
import { subscribeToSepsis } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { useAuthStore } from '@/store/authStore';
import type { VitalSignsResponse } from '@/api/types';
import { ApiError } from '@/api/client';
import { format } from 'date-fns';

/* ── Sepsis status badge — keys MUST match the backend SepsisStatus enum. ── */
const STATUS_FALLBACK = { color: 'text-slate-400', bg: 'bg-slate-500/10', label: 'Unknown' };
const STATUS_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  NO_SEPSIS:        { color: 'text-emerald-400', bg: 'bg-emerald-500/10', label: 'No Sepsis' },
  SIRS_POSITIVE:    { color: 'text-amber-400',   bg: 'bg-amber-500/10',   label: 'SIRS Positive' },
  SEPSIS_SUSPECTED: { color: 'text-red-400',     bg: 'bg-red-500/10',     label: 'Sepsis Suspected' },
  SEVERE_SEPSIS:    { color: 'text-red-500',     bg: 'bg-red-500/15',     label: 'Severe Sepsis' },
  SEPTIC_SHOCK:     { color: 'text-red-600',     bg: 'bg-red-500/20',     label: 'Septic Shock' },
};

/* Statuses for which the 1-hour bundle is required (mirrors the backend). */
const BUNDLE_REQUIRED_STATUSES = ['SEPSIS_SUSPECTED', 'SEVERE_SEPSIS', 'SEPTIC_SHOCK'];

/* ── Bundle checklist items ── */
const BUNDLE_ITEMS: {
  key: keyof SepsisScreening;
  enumValue: string;
  label: string;
  icon: typeof Droplets;
}[] = [
  { key: 'bloodCultureObtained',     enumValue: 'BLOOD_CULTURE_OBTAINED',     label: 'Blood Culture',  icon: Droplets },
  { key: 'broadSpectrumAntibiotics', enumValue: 'BROAD_SPECTRUM_ANTIBIOTICS', label: 'Antibiotics',    icon: Pill },
  { key: 'ivCrystalloidBolus',       enumValue: 'IV_CRYSTALLOID_BOLUS',       label: 'IV Crystalloid', icon: Syringe },
  { key: 'lactateMeasured',          enumValue: 'LACTATE_MEASURED',           label: 'Lactate',        icon: FlaskConical },
  { key: 'vasopressorsIfNeeded',     enumValue: 'VASOPRESSORS_IF_NEEDED',     label: 'Vasopressors',   icon: Activity },
  { key: 'repeatLactateIfElevated',  enumValue: 'REPEAT_LACTATE_IF_ELEVATED', label: 'Repeat Lactate', icon: RotateCcw },
];

const qsofaColor = (score: number) => {
  if (score >= 3) return { text: 'text-red-500', bg: 'bg-red-500/10', border: 'border-red-500/20' };
  if (score >= 2) return { text: 'text-amber-500', bg: 'bg-amber-500/10', border: 'border-amber-500/20' };
  return { text: 'text-emerald-500', bg: 'bg-emerald-500/10', border: 'border-emerald-500/20' };
};

function formatElapsed(startIso: string): string {
  const ms = Date.now() - new Date(startIso).getTime();
  if (ms < 0) return '0m';
  const totalMin = Math.floor(ms / 60000);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function bundleTimerColor(startIso: string): string {
  const minutes = Math.floor((Date.now() - new Date(startIso).getTime()) / 60000);
  if (minutes >= 60) return 'text-red-500';
  if (minutes >= 45) return 'text-amber-500';
  return 'text-emerald-500';
}

interface SepsisPanelProps {
  visitId: string;
  /** Latest recorded vitals — screening needs at least one set on file.
   *  When null we disable the run button and explain why, rather than
   *  letting the click fail with a backend error. */
  latestVitals: VitalSignsResponse | null;
  /** Called after a successful screening so the parent chart can refresh
   *  its Alerts tab (a positive screen raises real-time alerts). */
  onScreened?: () => void;
}

export function SepsisPanel({ visitId, latestVitals, onScreened }: SepsisPanelProps) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';
  const wsGen = useWebSocketGeneration();

  const [screenings, setScreenings] = useState<SepsisScreening[]>([]);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Re-render every 30s so the live bundle timer advances.
  const [, setTick] = useState(0);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Optional labs a clinician can attach to the screening (operator-entered;
  // the system has no coded lab lookup). Empty form → no body → the screen is
  // byte-for-byte the prior vitals-only request.
  const [showLabsForm, setShowLabsForm] = useState(false);
  const [labs, setLabs] = useState({ lactate: '', wbc: '', infectionSource: '', notes: '' });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await sepsisApi.getForVisit(visitId);
      setScreenings(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load sepsis screenings:', err);
      setScreenings([]);
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    tickRef.current = setInterval(() => setTick((t) => t + 1), 30000);
    return () => { if (tickRef.current) clearInterval(tickRef.current); };
  }, []);

  // Live refresh: a screening run elsewhere — or a bundle escalation — for
  // THIS visit refreshes the panel. Dedicated sepsis topic; filter by visitId.
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToSepsis(hospitalId, (event: { visitId?: string }) => {
      if (event?.visitId === visitId) load();
    });
    return () => unsub();
  }, [hospitalId, visitId, load, wsGen]);

  const runScreening = async () => {
    setRunning(true);
    setError(null);
    try {
      // Build the optional override body. Numbers are sent only when the field
      // is non-blank AND parses to a finite value; blanks are omitted so an
      // empty form posts no body (identical to the prior vitals-only screen).
      const body: SepsisScreeningRequest = {};
      const lactate = Number(labs.lactate);
      if (labs.lactate.trim() !== '' && Number.isFinite(lactate)) body.lactateLevel = lactate;
      const wbc = Number(labs.wbc);
      if (labs.wbc.trim() !== '' && Number.isFinite(wbc)) {
        // Unit guard: WBC is an ABSOLUTE count (cells/µL). A value < 100 is
        // almost certainly a ×10⁹/L mis-entry (e.g. 11.2) that would otherwise
        // read as profound leukopenia. Block it with a clear hint rather than
        // run a screening off a unit error. (Backend enforces the same floor.)
        if (wbc < 100) {
          setError('WBC looks like a ×10⁹/L value — enter the ABSOLUTE count in cells/µL (e.g. 11000, not 11.2).');
          return; // finally{} resets running; form stays open to correct
        }
        body.wbcCount = wbc;
      }
      if (labs.infectionSource.trim() !== '') body.suspectedInfectionSource = labs.infectionSource.trim();
      if (labs.notes.trim() !== '') body.notes = labs.notes.trim();
      const hasBody = Object.keys(body).length > 0;

      await sepsisApi.screen(visitId, hasBody ? body : undefined);
      setLabs({ lactate: '', wbc: '', infectionSource: '', notes: '' });
      setShowLabsForm(false);
      await load();
      onScreened?.();
    } catch (err) {
      // Most likely the backend "no vital signs recorded" guard, or an
      // authz denial. Surface it inline rather than swallowing it.
      const message = err instanceof ApiError
        ? err.message
        : err instanceof Error ? err.message : 'Failed to run sepsis screening';
      setError(message);
    } finally {
      setRunning(false);
    }
  };

  const handleStartBundle = async (screeningId: string) => {
    setActionLoading(screeningId);
    setError(null);
    try {
      await sepsisApi.startBundle(screeningId);
      await load();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to start bundle';
      setError(message);
    } finally {
      setActionLoading(null);
    }
  };

  // Bundle items are one-way (you can't un-obtain a blood culture). A click
  // on an already-done item is a no-op; the backend has no un-complete.
  const handleCompleteItem = async (screening: SepsisScreening, key: keyof SepsisScreening, enumValue: string) => {
    if (screening[key] === true) return;
    setActionLoading(`${screening.id}-${key}`);
    setError(null);
    try {
      await sepsisApi.completeBundleItem(screening.id, enumValue);
      await load();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to complete bundle item';
      setError(message);
    } finally {
      setActionLoading(null);
    }
  };

  const latest = screenings[0] ?? null;
  const history = screenings.slice(1);
  const noVitals = !latestVitals;

  return (
    <div className="space-y-4">
      {/* ── Run-screening header ── */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-orange-500/15 flex items-center justify-center shrink-0">
              <ShieldAlert className="w-5 h-5 text-orange-500" />
            </div>
            <div>
              <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Sepsis Screening</h3>
              <p className={`text-xs ${text.muted}`}>
                Runs qSOFA &amp; SIRS against this patient&rsquo;s most recent vitals.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {screenings.length > 0 && (
              <button
                onClick={load}
                disabled={loading}
                className={`w-9 h-9 rounded-xl flex items-center justify-center transition-colors ${
                  isDark ? 'bg-white/5 hover:bg-white/10' : 'bg-slate-100 hover:bg-slate-200'
                }`}
                title="Refresh"
              >
                <RefreshCw className={`w-4 h-4 ${text.muted} ${loading ? 'animate-spin' : ''}`} />
              </button>
            )}
            <button
              onClick={() => setShowLabsForm((s) => !s)}
              disabled={running}
              title="Optionally attach lactate / WBC / suspected infection source to this screening"
              className={`inline-flex items-center gap-1.5 px-3 py-2.5 text-[11px] font-bold rounded-xl transition-colors ${
                showLabsForm
                  ? 'bg-cyan-500/15 text-cyan-500'
                  : isDark ? 'bg-white/5 text-slate-300 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              <FlaskConical className="w-3.5 h-3.5" />
              {showLabsForm ? 'Hide labs' : 'Add labs'}
            </button>
            <button
              onClick={runScreening}
              disabled={running || noVitals}
              title={noVitals ? 'Record vitals first — screening needs at least one set of vitals on file.' : 'Run a sepsis screening now'}
              className={`inline-flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-xl shadow-md transition-all ${
                noVitals
                  ? 'bg-slate-200 text-slate-400 cursor-not-allowed'
                  : 'bg-gradient-to-r from-orange-500 to-red-500 text-white hover:-translate-y-0.5'
              }`}
            >
              {running ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
              {screenings.length > 0 ? 'Re-screen' : 'Run screening'}
            </button>
          </div>
        </div>

        {noVitals && (
          <div className="mt-3 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-amber-500/10 border border-amber-500/20">
            <AlertTriangle className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
            <p className="text-[11px] font-semibold text-amber-500">
              No vitals recorded for this visit yet. Record vitals on the Vitals tab, then run the screening.
            </p>
          </div>
        )}

        {error && (
          <div className="mt-3 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[11px] font-semibold text-red-500">{error}</p>
          </div>
        )}

        {showLabsForm && (
          <div className="mt-3 rounded-xl p-4 animate-fade-up" style={glassInner}>
            <p className={`text-[11px] font-bold uppercase tracking-wider mb-3 ${text.muted}`}>
              Optional labs &amp; context (operator-entered)
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>Lactate (mmol/L)</label>
                <input
                  type="number" inputMode="decimal" step="0.1" min="0"
                  value={labs.lactate}
                  onChange={(e) => setLabs((l) => ({ ...l, lactate: e.target.value }))}
                  placeholder="e.g. 2.4"
                  className="w-full px-3 py-2.5 rounded-xl text-sm outline-none"
                  style={glassCard}
                />
                <p className={`text-[9px] mt-1 ${text.muted}`}>&gt; 2.0 mmol/L escalates to severe sepsis</p>
              </div>
              <div>
                <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>WBC (cells/µL)</label>
                <input
                  type="number" inputMode="numeric" step="100" min="0"
                  value={labs.wbc}
                  onChange={(e) => setLabs((l) => ({ ...l, wbc: e.target.value }))}
                  placeholder="e.g. 14500"
                  className="w-full px-3 py-2.5 rounded-xl text-sm outline-none"
                  style={glassCard}
                />
                <p className={`text-[9px] mt-1 ${text.muted}`}>Absolute count — &gt;12000 or &lt;4000 meets the SIRS criterion</p>
              </div>
              <div className="sm:col-span-2">
                <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>Suspected infection source</label>
                <input
                  type="text"
                  value={labs.infectionSource}
                  onChange={(e) => setLabs((l) => ({ ...l, infectionSource: e.target.value }))}
                  placeholder="e.g. pneumonia, urinary, intra-abdominal, skin/soft-tissue…"
                  className="w-full px-3 py-2.5 rounded-xl text-sm outline-none"
                  style={glassCard}
                />
                <p className={`text-[9px] mt-1 ${text.muted}`}>With SIRS ≥ 2, a named source escalates to sepsis-suspected</p>
              </div>
              <div className="sm:col-span-2">
                <label className={`block text-[10px] font-semibold mb-1 ${text.muted}`}>Notes</label>
                <textarea
                  rows={2}
                  value={labs.notes}
                  onChange={(e) => setLabs((l) => ({ ...l, notes: e.target.value }))}
                  placeholder="Optional context for this screening"
                  className="w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none"
                  style={glassCard}
                />
              </div>
            </div>
            <p className={`text-[10px] mt-2 ${text.muted}`}>
              All optional — leave blank to screen on vitals alone. Click{' '}
              <span className="font-bold">{screenings.length > 0 ? 'Re-screen' : 'Run screening'}</span> to apply.
            </p>
          </div>
        )}
      </div>

      {/* ── Content ── */}
      {loading ? (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
        </div>
      ) : !latest ? (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <Stethoscope className={`w-10 h-10 mx-auto mb-3 ${text.muted}`} />
          <p className={`text-sm font-bold ${text.heading}`}>Not screened yet</p>
          <p className={`text-xs mt-1 ${text.muted}`}>
            No sepsis screening has been run for this visit. Use “Run screening” above to perform one.
          </p>
        </div>
      ) : (
        <>
          <ScreeningCard
            screening={latest}
            isLatest
            actionLoading={actionLoading}
            onStartBundle={handleStartBundle}
            onCompleteItem={handleCompleteItem}
            glassCard={glassCard}
            glassInner={glassInner}
            isDark={isDark}
            text={text}
          />

          {history.length > 0 && (
            <div className="rounded-2xl p-5" style={glassCard}>
              <div className="flex items-center gap-2 mb-3">
                <History className={`w-4 h-4 ${text.muted}`} />
                <h4 className={`text-xs font-bold uppercase tracking-wider ${text.muted}`}>
                  Earlier screenings ({history.length})
                </h4>
              </div>
              <div className="space-y-3">
                {history.map((s) => (
                  <ScreeningCard
                    key={s.id}
                    screening={s}
                    isLatest={false}
                    actionLoading={actionLoading}
                    onStartBundle={handleStartBundle}
                    onCompleteItem={handleCompleteItem}
                    glassCard={glassInner}
                    glassInner={glassInner}
                    isDark={isDark}
                    text={text}
                  />
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/* ── A single screening result card (latest = full bundle controls). ── */
interface ScreeningCardProps {
  screening: SepsisScreening;
  isLatest: boolean;
  actionLoading: string | null;
  onStartBundle: (screeningId: string) => void;
  onCompleteItem: (screening: SepsisScreening, key: keyof SepsisScreening, enumValue: string) => void;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: { heading: string; body: string; muted: string };
}

function ScreeningCard({
  screening, isLatest, actionLoading, onStartBundle, onCompleteItem,
  glassCard, isDark, text,
}: ScreeningCardProps) {
  const qc = qsofaColor(screening.qsofaScore);
  const statusCfg = STATUS_CONFIG[screening.sepsisStatus] || STATUS_FALLBACK;
  const progress = BUNDLE_ITEMS.filter((item) => screening[item.key] === true).length;
  const bundleActive = screening.bundleStartedAt && !screening.bundleCompletedAt;
  const bundleRequired = BUNDLE_REQUIRED_STATUSES.includes(screening.sepsisStatus);

  return (
    <div className="rounded-2xl overflow-hidden" style={glassCard}>
      <div className="px-5 py-4">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-start gap-4 flex-1 min-w-0">
            {/* qSOFA badge */}
            <div className={`shrink-0 w-14 h-14 rounded-xl ${qc.bg} border ${qc.border} flex flex-col items-center justify-center`}>
              <span className={`text-lg font-black ${qc.text}`}>{screening.qsofaScore}</span>
              <span className={`text-[8px] font-bold uppercase tracking-wider ${qc.text}`}>qSOFA</span>
            </div>

            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap mb-1.5">
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${statusCfg.bg} ${statusCfg.color}`}>
                  {statusCfg.label}
                </span>
                <span className={`text-[10px] font-bold px-2 py-1 rounded-lg ${isDark ? 'bg-white/5 text-slate-300' : 'bg-slate-100 text-slate-600'}`}>
                  SIRS: {screening.sirsScore}/4
                </span>
                {screening.bundleStartedAt && (
                  <span className={`text-[10px] font-bold px-2 py-1 rounded-lg ${
                    progress === 6 ? 'bg-emerald-500/10 text-emerald-400' : 'bg-cyan-500/10 text-cyan-400'
                  }`}>
                    Bundle: {progress}/6
                  </span>
                )}
              </div>

              {/* Screened-by + time */}
              <div className="flex items-center gap-3 flex-wrap">
                <p className={`text-xs ${text.body}`}>
                  Screened by <span className={`font-semibold ${text.heading}`}>{screening.screenedByName}</span>
                </p>
                <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                  <Clock className="w-3 h-3" />
                  {format(new Date(screening.screenedAt), 'dd MMM yyyy HH:mm')}
                </span>
              </div>

              {/* qSOFA criteria breakdown */}
              <div className="flex items-center gap-2 mt-2 flex-wrap">
                <CriterionChip active={screening.alteredMentation} label="Altered Mentation" isDark={isDark} />
                <CriterionChip active={screening.respiratoryRateHigh} label="RR ≥ 22" isDark={isDark} />
                <CriterionChip active={screening.systolicBpLow} label="SBP ≤ 100" isDark={isDark} />
              </div>

              {/* SIRS criteria breakdown */}
              <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                <CriterionChip active={screening.temperatureCriteriaMet} label="Temp" isDark={isDark} small />
                <CriterionChip active={screening.heartRateCriteriaMet} label="HR" isDark={isDark} small />
                <CriterionChip active={screening.respiratoryRateCriteriaMet} label="RR" isDark={isDark} small />
                <CriterionChip active={screening.wbcCriteriaMet} label="WBC" isDark={isDark} small />
              </div>

              {/* Safety banners — data quality + pediatric caveat */}
              {screening.insufficientData && (
                <p className="text-[10px] font-semibold text-amber-400 mt-2 leading-relaxed">
                  ⚠ Insufficient vitals — a negative screen is NOT reassuring.
                  {screening.dataQualityNote ? ` ${screening.dataQualityNote}` : ''}
                </p>
              )}
              {screening.pediatric && screening.pediatricCaveat && (
                <p className="text-[10px] font-semibold text-fuchsia-300 mt-2 leading-relaxed">
                  ⚠ {screening.pediatricCaveat}
                </p>
              )}

              {/* Infection source & lactate */}
              {(screening.suspectedInfectionSource || screening.lactateLevel != null) && (
                <div className="flex items-center gap-3 mt-2 flex-wrap">
                  {screening.suspectedInfectionSource && (
                    <span className={`text-[10px] ${text.muted}`}>
                      Source: <span className={text.body}>{screening.suspectedInfectionSource}</span>
                    </span>
                  )}
                  {screening.lactateLevel != null && (
                    <span className={`text-[10px] font-bold ${screening.lactateLevel >= 4 ? 'text-red-400' : screening.lactateLevel >= 2 ? 'text-amber-400' : 'text-emerald-400'}`}>
                      Lactate: {screening.lactateLevel} mmol/L
                    </span>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* Bundle timer */}
          <div className="shrink-0 text-right">
            {bundleActive && screening.bundleStartedAt && (
              <div className="flex flex-col items-end gap-1">
                <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Bundle Timer</span>
                <div className={`text-xl font-black tabular-nums ${bundleTimerColor(screening.bundleStartedAt)}`}>
                  {formatElapsed(screening.bundleStartedAt)}
                </div>
                <span className={`text-[9px] ${text.muted}`}>Target: 1h</span>
              </div>
            )}
            {screening.bundleCompletedAt && (
              <div className="flex items-center gap-1.5">
                <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                <span className="text-[10px] font-bold text-emerald-400">Bundle Complete</span>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Bundle checklist — interactive only on the latest screening. */}
      {screening.bundleStartedAt && (
        <div className="px-5 py-3 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2">
            {BUNDLE_ITEMS.map((item) => {
              const done = screening[item.key] === true;
              const isUpdating = actionLoading === `${screening.id}-${item.key}`;
              const Icon = item.icon;
              const locked = !isLatest || screening.bundleCompletedAt != null;
              return (
                <button
                  key={String(item.key)}
                  onClick={() => !locked && onCompleteItem(screening, item.key, item.enumValue)}
                  disabled={isUpdating || locked}
                  className={`flex items-center gap-2 px-3 py-2.5 rounded-xl text-[11px] font-bold transition-all ${
                    done
                      ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20'
                      : isDark
                        ? 'bg-white/5 text-slate-400 border border-white/5 hover:bg-white/10'
                        : 'bg-slate-50 text-slate-500 border border-slate-200/50 hover:bg-slate-100'
                  } ${locked ? 'cursor-default' : 'cursor-pointer'}`}
                >
                  {isUpdating ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin shrink-0" />
                  ) : done ? (
                    <CheckCircle2 className="w-3.5 h-3.5 shrink-0" />
                  ) : (
                    <Circle className="w-3.5 h-3.5 shrink-0" />
                  )}
                  <Icon className="w-3 h-3 shrink-0 opacity-60" />
                  <span className="truncate">{item.label}</span>
                </button>
              );
            })}
          </div>
          {screening.bundleStartedByName && (
            <p className={`text-[10px] mt-2 ${text.muted}`}>
              Bundle started by <span className={text.body}>{screening.bundleStartedByName}</span>
              {screening.bundleCompletedByName && (
                <> · completed by <span className={text.body}>{screening.bundleCompletedByName}</span></>
              )}
            </p>
          )}
        </div>
      )}

      {/* Start-bundle action — offered for every bundle-required status. */}
      {isLatest && !screening.bundleStartedAt && bundleRequired && (
        <div className="px-5 py-3 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
          <button
            onClick={() => onStartBundle(screening.id)}
            disabled={actionLoading === screening.id}
            className="inline-flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-xl bg-orange-500/10 text-orange-400 hover:bg-orange-500/20 transition-colors disabled:opacity-50"
          >
            {actionLoading === screening.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
            Start Sepsis Bundle
          </button>
        </div>
      )}

      {/* Notes */}
      {screening.notes && (
        <div className="px-5 py-2.5 border-t" style={{ borderColor: isDark ? 'rgba(2,132,199,0.12)' : 'rgba(203,213,225,0.3)' }}>
          <p className={`text-[11px] ${text.muted}`}>{screening.notes}</p>
        </div>
      )}
    </div>
  );
}

function CriterionChip({ active, label, isDark, small }: { active: boolean; label: string; isDark: boolean; small?: boolean }) {
  return (
    <span className={`${small ? 'text-[9px] px-1.5' : 'text-[10px] px-2'} py-0.5 rounded ${
      active
        ? 'bg-red-500/10 text-red-400'
        : isDark ? 'bg-white/5 text-slate-500' : 'bg-slate-100 text-slate-400'
    }`}>
      {active ? '✓' : '✗'} {label}
    </span>
  );
}
