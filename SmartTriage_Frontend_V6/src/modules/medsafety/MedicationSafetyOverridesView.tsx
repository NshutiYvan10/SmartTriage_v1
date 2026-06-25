/* ═══════════════════════════════════════════════════════════════
   Phase 14 — Medication Safety Overrides Audit Dashboard
   ─────────────────────────────────────────────────────────────────
   Why this exists, separate from MedicationSafetyView and AlertsView:

     • MedicationSafetyView is per-visit / forward-facing — it shows
       the safety-check pipeline for a specific patient and lets a
       prescriber act on it.
     • AlertsView is the operational-now stream — every alert type
       across the ED, optimised for "what needs my attention right
       this minute".
     • This view is the FORENSIC layer. After Phase 9–13 each clinical
       override (allergy, pregnancy, overdose, drug-interaction,
       renal-precaution, duplicate-therapy, underdose) auto-creates
       a MEDICATION_SAFETY_WARNING ClinicalAlert with a calibrated
       severity. Once you have a queryable audit trail, somebody —
       a hospital safety officer, a clinical lead, an M&M committee
       — needs to slice it: "How many Category-X pregnancy overrides
       did Dr. X sign last month? Which drug accounts for most
       overdose overrides on the paeds side?" That's this page.

   Data source: alertApi.getSafetyOverrides(hospitalId, range, …) — a
   dedicated server endpoint that filters by alertType and date window
   in SQL. The endpoint accepts the same "24h" / "7d" / "30d" / "all"
   shorthand the dashboard's range tabs use, so re-loading on range
   change pulls only the relevant window. Class filter + search still
   run client-side because they're title/message-shape predicates the
   database doesn't index — far cheaper to apply in JS over an already
   pre-filtered set.

   Override class is derived from alert.title prefix (set by
   MedicationService.createOverrideAlert / createInteractionScopedAlerts).
   That string contract is the only coupling between backend tags and
   this frontend grouping — change either side and the categorise()
   function is the seam to update.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ShieldAlert, RefreshCw, Loader2, Pill, AlertTriangle,
  Baby, Droplet, Layers, Scale, Activity, Search,
  ChevronRight, Users, Clock, Check, Download, UserMinus, Zap,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { useAlertStore } from '@/store/alertStore';
import { alertApi } from '@/api/alerts';
import type { ClinicalAlertResponse, AlertSeverity } from '@/api/types';
import { format, formatDistanceToNow } from 'date-fns';
import { BreakTheGlassIncidents } from './BreakTheGlassIncidents';

/* ── Override classes ─────────────────────────────────────────── */

type OverrideClass =
  | 'allergy'
  | 'pregnancy'
  | 'overdose'
  | 'underdose'
  | 'interaction'
  | 'renal'
  | 'duplicate'
  | 'geriatric'
  | 'emergency'
  | 'other';

interface ClassMeta {
  label: string;
  Icon: React.ComponentType<{ className?: string }>;
  color: string;       // text colour
  bg: string;          // chip background
  ring: string;        // chip border
  bar: string;         // distribution bar fill
  badgeColor: string;  // badge text colour (text-COLOR-600)
  badgeRgb: string;    // badge translucent hue "R,G,B"
}

const CLASS_META: Record<OverrideClass, ClassMeta> = {
  allergy:     { label: 'Allergy',     Icon: AlertTriangle, color: 'text-red-300',     bg: 'bg-red-500/10',     ring: 'border-red-500/30',     bar: 'bg-red-500',     badgeColor: 'text-red-600',     badgeRgb: '239,68,68' },
  pregnancy:   { label: 'Pregnancy',   Icon: Baby,          color: 'text-pink-300',    bg: 'bg-pink-500/10',    ring: 'border-pink-500/30',    bar: 'bg-pink-500',    badgeColor: 'text-pink-600',    badgeRgb: '236,72,153' },
  overdose:    { label: 'Overdose',    Icon: Scale,         color: 'text-rose-300',    bg: 'bg-rose-500/10',    ring: 'border-rose-500/30',    bar: 'bg-rose-500',    badgeColor: 'text-rose-600',    badgeRgb: '244,63,94' },
  underdose:   { label: 'Underdose',   Icon: Scale,         color: 'text-blue-300',    bg: 'bg-blue-500/10',    ring: 'border-blue-500/30',    bar: 'bg-blue-500',    badgeColor: 'text-blue-600',    badgeRgb: '59,130,246' },
  interaction: { label: 'Interaction', Icon: Activity,      color: 'text-orange-300',  bg: 'bg-orange-500/10',  ring: 'border-orange-500/30',  bar: 'bg-orange-500',  badgeColor: 'text-orange-600',  badgeRgb: '249,115,22' },
  renal:       { label: 'Renal',       Icon: Droplet,       color: 'text-violet-300',  bg: 'bg-violet-500/10',  ring: 'border-violet-500/30',  bar: 'bg-violet-500',  badgeColor: 'text-violet-600',  badgeRgb: '139,92,246' },
  duplicate:   { label: 'Duplicate',   Icon: Layers,        color: 'text-amber-300',   bg: 'bg-amber-500/10',   ring: 'border-amber-500/30',   bar: 'bg-amber-500',   badgeColor: 'text-amber-600',   badgeRgb: '245,158,11' },
  geriatric:   { label: 'Geriatric',   Icon: UserMinus,     color: 'text-fuchsia-300', bg: 'bg-fuchsia-500/10', ring: 'border-fuchsia-500/30', bar: 'bg-fuchsia-500', badgeColor: 'text-fuchsia-600', badgeRgb: '217,70,239' },
  emergency:   { label: 'Emergency / administration', Icon: Zap, color: 'text-red-300', bg: 'bg-red-500/10', ring: 'border-red-500/30', bar: 'bg-red-500', badgeColor: 'text-red-600', badgeRgb: '239,68,68' },
  other:       { label: 'Other',       Icon: ShieldAlert,   color: 'text-slate-300',   bg: 'bg-slate-500/10',   ring: 'border-slate-500/30',   bar: 'bg-slate-500',   badgeColor: 'text-slate-600',   badgeRgb: '100,116,139' },
};

const CLASS_ORDER: OverrideClass[] = [
  'emergency', 'allergy', 'pregnancy', 'overdose', 'interaction', 'renal', 'geriatric', 'duplicate', 'underdose', 'other',
];

/** Classify a MEDICATION_SAFETY_WARNING alert by its title prefix.
 *  Title strings are written by MedicationService — keep this in
 *  sync with createOverrideAlert / createInteractionScopedAlerts. */
function categorise(title: string | null): OverrideClass {
  const t = (title || '').toLowerCase();
  // High-alert approval-gate skip + administration-time gate bypass — these are
  // MEDICATION_EMERGENCY_OVERRIDE alerts (emitted by MedicationService /
  // MedicationScheduleService), surfaced here alongside prescribe-time overrides.
  if (t.startsWith('emergency override') || t.startsWith('administration override')) return 'emergency';
  if (t.startsWith('allergy override')) return 'allergy';
  if (t.startsWith('pregnancy override') || t.startsWith('pregnancy/lactation')) return 'pregnancy';
  if (t.startsWith('overdose override')) return 'overdose';
  if (t.startsWith('underdose override')) return 'underdose';
  // Phase 12a screening hits and Phase 12b Cockcroft-Gault eGFR hits
  // both fold into the same 'renal' bucket — the dashboard groups by
  // clinical vertical, not by which specific check fired. The raw alert
  // title still distinguishes "Renal-precaution" vs "Renal-eGFR override"
  // for an officer who drills in.
  if (t.startsWith('renal-precaution') || t.startsWith('renal-egfr')) return 'renal';
  if (t.startsWith('duplicate-therapy')) return 'duplicate';
  // Phase 16 — geriatric (Beers Criteria) overrides. MedicationService
  // emits "Geriatric prescribing override (avoid)" / "(caution)" titles
  // for [geriatric][avoid] / [geriatric][caution] tags.
  if (t.startsWith('geriatric')) return 'geriatric';
  // "Interaction override" is the default label MedicationService emits
  // for any tagged safety segment that doesn't match a more specific
  // class — covers [contraindicated], [major], and the fail-safe path.
  if (t.startsWith('interaction override')) return 'interaction';
  if (
    t.includes('contraindicated') ||
    t.includes('major drug') ||
    t.includes('drug interaction')
  ) return 'interaction';
  return 'other';
}

/** Best-effort drug-name parse. Handles every override-alert message shape the
 *  backend actually emits:
 *   - prescribe-time:  "Prescriber: X. Drug: <Drug> <dose> <route>. Acknowledged…"
 *   - emergency/admin: "X skipped/overrode … for '<Drug>' (visit V…)"
 *   - legacy:          "X prescribed <Drug> <dose> …"
 *  Falls back to '' on miss — the raw message is always shown, so this only
 *  feeds the group-by-drug aggregation. */
function parseDrug(message: string): string {
  let m = /Drug:\s*([A-Za-z][A-Za-z0-9\-/ ]*?)(?:\s+\d|\.\s|\.$|$)/.exec(message);
  if (m) return m[1].trim();
  m = /\bfor\s+'([^']+)'/.exec(message);
  if (m) return m[1].trim();
  m = /prescribed\s+([A-Za-z][A-Za-z0-9\-/ ]*?)\s+\d/.exec(message);
  return m ? m[1].trim() : '';
}

/** Best-effort prescriber/actor parse for the same message shapes. */
function parsePrescriber(message: string): string {
  let m = /^Prescriber:\s*([^.]+?)\.(?:\s|$)/.exec(message);
  if (m) return m[1].trim() === 'null' ? '' : m[1].trim();
  m = /^(.+?)\s+(?:skipped|overrode)\b/.exec(message);
  if (m) return m[1].trim();
  m = /^([A-Z][A-Za-z.\- ]{1,40}?)\s+prescribed/.exec(message);
  return m ? m[1].trim() : '';
}

/* ── Time range ───────────────────────────────────────────────── */

type TimeRange = '24h' | '7d' | '30d' | 'all';

const RANGE_HOURS: Record<TimeRange, number | null> = {
  '24h': 24,
  '7d':  24 * 7,
  '30d': 24 * 30,
  'all': null,
};

const RANGE_LABEL: Record<TimeRange, string> = {
  '24h': 'Last 24h',
  '7d':  'Last 7 days',
  '30d': 'Last 30 days',
  'all': 'All time',
};

/* ── Severity badge ───────────────────────────────────────────── */

function severityStyle(s: AlertSeverity): { color: string; badgeStyle: { background: string; border: string } } {
  switch (s) {
    case 'CRITICAL': return { color: 'text-red-600',    badgeStyle: { background: 'rgba(239,68,68,0.08)',  border: '1px solid rgba(239,68,68,0.2)' } };
    case 'HIGH':     return { color: 'text-orange-600', badgeStyle: { background: 'rgba(249,115,22,0.08)', border: '1px solid rgba(249,115,22,0.2)' } };
    case 'MEDIUM':   return { color: 'text-amber-600',  badgeStyle: { background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' } };
    case 'LOW':      return { color: 'text-blue-600',   badgeStyle: { background: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)' } };
    default:         return { color: 'text-slate-600',  badgeStyle: { background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' } };
  }
}

/* ═══════════════════════════════════════════════════════════════ */

export function MedicationSafetyOverridesView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [alerts, setAlerts] = useState<ClinicalAlertResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState<TimeRange>('7d');
  const [classFilter, setClassFilter] = useState<OverrideClass | 'all'>('all');
  const [search, setSearch] = useState('');
  // Per-row acknowledgment is optimistic — flip the local copy
  // immediately on click, then fire the API call. If the call fails
  // we roll back and surface a console error rather than block the
  // safety officer's flow on a slow network. The next refresh would
  // re-sync from the server in any case.
  const [ackingId, setAckingId] = useState<string | null>(null);

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  /* ── Fetch ─────────────────────────────────────────────────── */
  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      // Phase 14b — server-side filter. Endpoint applies the date
      // window in SQL and returns only MEDICATION_SAFETY_WARNING rows,
      // so we can scale past a few hundred overrides per hospital
      // without dragging unrelated alerts across the wire. The
      // dashboard's class filter + search still run client-side
      // because they're text-shape work the database doesn't index.
      const page = await alertApi.getSafetyOverrides(hospitalId, range, 0, 500);
      setAlerts(page.content);
    } catch (err) {
      console.error('Failed to load medication safety overrides', err);
      setAlerts([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, range]);

  useEffect(() => { load(); }, [load]);

  // Live update: override-alert creation now publishes to the hospital alert topic, which
  // the app-root WebSocket subscriber folds into the alert store. We OBSERVE that store's
  // signature (rather than open our own subscription, which would clobber the single global
  // sub) and re-fetch the audit list when a new override lands or an existing one is acked —
  // plus a 5-minute backstop for any missed frame. Mirrors AlertsView.
  const liveAlertSignature = useAlertStore((s) => {
    let unack = 0;
    for (const a of s.alerts) if (!a.acknowledged) unack += 1;
    return `${s.alerts.length}:${unack}`;
  });
  useEffect(() => { load(); }, [load, liveAlertSignature]);
  useEffect(() => {
    if (!hospitalId) return;
    const id = setInterval(load, 5 * 60 * 1000);
    return () => clearInterval(id);
  }, [load, hospitalId]);

  /* ── Acknowledge a single override ─────────────────────────── */
  const handleAcknowledge = useCallback(async (alertId: string) => {
    setAckingId(alertId);
    // Optimistic flip — the server response will overwrite the row
    // with its canonical timestamp & acknowledgedByName, but we
    // don't want the safety officer waiting on a network round-trip
    // before they see their own click register.
    setAlerts((prev) => prev.map((a) =>
      a.id === alertId ? { ...a, acknowledged: true } : a,
    ));
    try {
      // Override-specific ack endpoint — works for the full governance
      // audience (admin, safety officer, doctor, charge nurse), unlike the
      // clinical-only generic acknowledge.
      const updated = await alertApi.acknowledgeSafetyOverride(alertId);
      setAlerts((prev) => prev.map((a) => (a.id === alertId ? updated : a)));
    } catch (err) {
      console.error('Failed to acknowledge override', err);
      // Roll back the optimistic flip.
      setAlerts((prev) => prev.map((a) =>
        a.id === alertId ? { ...a, acknowledged: false } : a,
      ));
    } finally {
      setAckingId(null);
    }
  }, []);

  /* ── Filtered set ──────────────────────────────────────────── */
  const filtered = useMemo(() => {
    const cutoffMs = (() => {
      const hrs = RANGE_HOURS[range];
      return hrs == null ? null : Date.now() - hrs * 3600 * 1000;
    })();

    const q = search.trim().toLowerCase();

    return alerts.filter((a) => {
      if (cutoffMs != null && new Date(a.createdAt).getTime() < cutoffMs) return false;
      if (classFilter !== 'all' && categorise(a.title) !== classFilter) return false;
      if (q) {
        const hay = `${a.title || ''} ${a.message} ${a.patientName} ${a.visitNumber || ''}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [alerts, range, classFilter, search]);

  /* ── Aggregations ─────────────────────────────────────────── */
  const stats = useMemo(() => {
    const byClass: Record<OverrideClass, number> = {
      allergy: 0, pregnancy: 0, overdose: 0, underdose: 0,
      interaction: 0, renal: 0, duplicate: 0, geriatric: 0, emergency: 0, other: 0,
    };
    const bySeverity: Record<string, number> = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
    const prescribers = new Set<string>();
    const drugs = new Set<string>();
    const byDrug: Record<string, number> = {};
    const byPrescriber: Record<string, number> = {};

    for (const a of filtered) {
      byClass[categorise(a.title)] += 1;
      bySeverity[a.severity] = (bySeverity[a.severity] || 0) + 1;
      const p = parsePrescriber(a.message);
      if (p) { prescribers.add(p); byPrescriber[p] = (byPrescriber[p] || 0) + 1; }
      const d = parseDrug(a.message);
      if (d) { drugs.add(d); byDrug[d] = (byDrug[d] || 0) + 1; }
    }

    const topDrug = Object.entries(byDrug).sort((a, b) => b[1] - a[1])[0];
    const topPrescriber = Object.entries(byPrescriber).sort((a, b) => b[1] - a[1])[0];

    return {
      total: filtered.length,
      byClass,
      bySeverity,
      uniquePrescribers: prescribers.size,
      uniqueDrugs: drugs.size,
      topDrug: topDrug ? { name: topDrug[0], count: topDrug[1] } : null,
      topPrescriber: topPrescriber ? { name: topPrescriber[0], count: topPrescriber[1] } : null,
    };
  }, [filtered]);

  /* ── CSV export ────────────────────────────────────────────────
     What downloads matches what's currently visible — the active
     time range, class filter, and search query all apply. That
     means a safety officer who has filtered to "Pregnancy overrides
     in the last 30 days" gets exactly that file when they hit
     Export, not the entire alert pool.

     We render the file in-browser via a Blob + temporary anchor
     rather than going through the server, so this works regardless
     of whether anyone has wired up an export endpoint. The trade-
     off: very large exports could lock the tab. With our 200-cap
     server-side that's not yet a real concern. */
  const handleExportCsv = useCallback(() => {
    if (filtered.length === 0) return;
    const rows = filtered
      .slice()
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .map((a) => ({
        timestamp: format(new Date(a.createdAt), 'yyyy-MM-dd HH:mm:ss'),
        visitNumber: a.visitNumber ?? '',
        patient: a.patientName ?? '',
        drug: parseDrug(a.message),
        prescriber: parsePrescriber(a.message),
        overrideClass: CLASS_META[categorise(a.title)].label,
        title: a.title ?? '',
        severity: a.severity,
        acknowledged: a.acknowledged ? 'yes' : 'no',
        acknowledgedBy: a.acknowledgedByName ?? '',
        acknowledgedAt: a.acknowledgedAt
          ? format(new Date(a.acknowledgedAt), 'yyyy-MM-dd HH:mm:ss')
          : '',
        message: a.message,
      }));

    // CSV escaping: wrap in quotes, double-up internal quotes.
    // Also strip CR/LF from message bodies so a multi-line clinical
    // note doesn't shift columns when opened in Excel.
    const esc = (s: string | number | null | undefined) => {
      const v = s == null ? '' : String(s).replace(/[\r\n]+/g, ' ');
      return `"${v.replace(/"/g, '""')}"`;
    };

    const headers = [
      'Timestamp', 'Visit', 'Patient', 'Drug', 'Prescriber',
      'Override class', 'Title', 'Severity',
      'Acknowledged', 'Acknowledged by', 'Acknowledged at', 'Message',
    ];
    const lines = [
      headers.map(esc).join(','),
      ...rows.map((r) => [
        r.timestamp, r.visitNumber, r.patient, r.drug, r.prescriber,
        r.overrideClass, r.title, r.severity,
        r.acknowledged, r.acknowledgedBy, r.acknowledgedAt, r.message,
      ].map(esc).join(',')),
    ];

    // BOM so Excel auto-detects UTF-8 (clinician names with accents,
    // pregnancy/teratogen labels with category symbols, etc.).
    const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `medication-safety-overrides_${range}_${format(new Date(), 'yyyy-MM-dd_HHmm')}.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }, [filtered, range]);

  /* ── Render ───────────────────────────────────────────────── */
  return (
    <div className="px-6 py-6">
      <div className="max-w-[1400px] mx-auto space-y-4">

        {/* ── Header card ── */}
        <div className="rounded-3xl overflow-hidden" style={glassCard}>
          <div
            className="px-5 py-4"
            style={{
              background: 'linear-gradient(135deg, rgba(244,63,94,0.18) 0%, rgba(99,102,241,0.10) 100%)',
              borderBottom: borderStyle,
            }}
          >
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-rose-500/20 border border-rose-500/30 flex items-center justify-center">
                  <ShieldAlert className="w-5 h-5 text-rose-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Medication Safety Overrides</h1>
                  <p className="text-white/50 text-xs">
                    Forensic audit of every medication override — prescribe-time safety warnings plus
                    administration-time &amp; emergency approval-gate bypasses — {RANGE_LABEL[range]}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={handleExportCsv}
                  disabled={filtered.length === 0}
                  className="flex items-center gap-1.5 px-3 py-2 rounded-xl bg-white/10 border border-white/15 text-white text-xs font-semibold hover:bg-white/15 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  aria-label="Export filtered overrides to CSV"
                  title={filtered.length === 0
                    ? 'No overrides match current filters'
                    : `Download ${filtered.length} override${filtered.length === 1 ? '' : 's'} as CSV`}
                >
                  <Download className="w-3.5 h-3.5" />
                  Export CSV
                </button>
                <button
                  onClick={load}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                  aria-label="Refresh"
                >
                  {loading ? <Loader2 className="w-4 h-4 text-white animate-spin" /> : <RefreshCw className="w-4 h-4 text-white" />}
                </button>
              </div>
            </div>
          </div>

          {/* Time range tabs */}
          <div className="flex gap-1 px-4 py-2" style={{ borderTop: borderStyle }}>
            {(Object.keys(RANGE_LABEL) as TimeRange[]).map((r) => (
              <button
                key={r}
                onClick={() => setRange(r)}
                className={`px-3 py-1.5 rounded-xl text-xs font-semibold transition-all ${
                  range === r
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : `${text.muted} hover:bg-white/5 border border-transparent`
                }`}
              >
                {RANGE_LABEL[r]}
              </button>
            ))}
          </div>
        </div>

        {/* ── Loading ── */}
        {loading && (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 animate-spin text-rose-400" />
          </div>
        )}

        {/* ── Empty ── */}
        {!loading && alerts.length === 0 && (
          <div className="rounded-3xl overflow-hidden" style={glassCard}>
            <div className="flex flex-col items-center justify-center py-16 px-6">
              <ShieldAlert className="w-12 h-12 text-slate-500 mb-4" />
              <p className={`text-sm font-semibold ${text.heading}`}>No safety overrides recorded</p>
              <p className={`text-xs mt-1 ${text.muted}`}>
                Every prescription that bypassed a safety warning will appear here.
              </p>
            </div>
          </div>
        )}

        {!loading && alerts.length > 0 && (
          <>
            {/* ── KPI tiles ── */}
            <div className="rounded-3xl overflow-hidden" style={glassCard}>
              <div className="grid grid-cols-2 lg:grid-cols-5 gap-px">
                <KpiTile label="Total overrides" value={stats.total} accent="text-white" Icon={ShieldAlert} bg="bg-rose-500/10" />
                <KpiTile label="Critical" value={stats.bySeverity.CRITICAL || 0} accent="text-red-300" Icon={AlertTriangle} bg="bg-red-500/10" />
                <KpiTile label="High" value={stats.bySeverity.HIGH || 0} accent="text-orange-300" Icon={AlertTriangle} bg="bg-orange-500/10" />
                <KpiTile label="Unique prescribers" value={stats.uniquePrescribers} accent="text-cyan-300" Icon={Users} bg="bg-cyan-500/10" />
                <KpiTile label="Unique drugs" value={stats.uniqueDrugs} accent="text-violet-300" Icon={Pill} bg="bg-violet-500/10" />
              </div>
            </div>

            {/* ── By override class ── */}
            <div className="rounded-3xl overflow-hidden" style={glassCard}>
              <div className="px-5 py-3 flex items-center justify-between" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2">
                  <Layers className="w-4 h-4 text-rose-300" />
                  <h2 className={`text-sm font-bold ${text.heading}`}>By override class</h2>
                </div>
                <span className={`text-[10px] ${text.muted}`}>click a row to filter the log</span>
              </div>
              <div className="px-5 py-4 space-y-2">
                <ClassRow
                  label="All classes"
                  count={stats.total}
                  total={stats.total || 1}
                  bar="bg-white/40"
                  active={classFilter === 'all'}
                  onClick={() => setClassFilter('all')}
                />
                {CLASS_ORDER.map((c) => {
                  const meta = CLASS_META[c];
                  const count = stats.byClass[c];
                  if (count === 0 && classFilter !== c) return null;
                  return (
                    <ClassRow
                      key={c}
                      label={meta.label}
                      count={count}
                      total={stats.total || 1}
                      bar={meta.bar}
                      active={classFilter === c}
                      onClick={() => setClassFilter(c)}
                      Icon={meta.Icon}
                    />
                  );
                })}
              </div>
            </div>

            {/* ── Top contributors ── */}
            {(stats.topDrug || stats.topPrescriber) && (
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                {stats.topDrug && (
                  <ContributorCard
                    title="Most overridden drug"
                    name={stats.topDrug.name}
                    count={stats.topDrug.count}
                    total={stats.total}
                    Icon={Pill}
                    accent="text-violet-300"
                    bg="bg-violet-500/10"
                  />
                )}
                {stats.topPrescriber && (
                  <ContributorCard
                    title="Most prolific prescriber"
                    name={stats.topPrescriber.name}
                    count={stats.topPrescriber.count}
                    total={stats.total}
                    Icon={Users}
                    accent="text-cyan-300"
                    bg="bg-cyan-500/10"
                  />
                )}
              </div>
            )}

            {/* ── Override log ── */}
            <div className="rounded-3xl overflow-hidden" style={glassCard}>
              <div className="px-5 py-3 flex items-center gap-3" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-2 flex-1">
                  <Clock className="w-4 h-4 text-rose-300" />
                  <h2 className={`text-sm font-bold ${text.heading}`}>Override log</h2>
                  <span className={`text-[10px] ${text.muted}`}>
                    {filtered.length} of {alerts.length} {classFilter === 'all' ? '' : `· ${CLASS_META[classFilter].label}`}
                  </span>
                </div>
                <div className="relative">
                  <Search className="w-3.5 h-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
                  <input
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    placeholder="Patient, drug, visit#…"
                    style={glassInner}
                    className={`pl-8 pr-3 py-1.5 rounded-lg text-xs ${text.body} placeholder:text-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 w-56`}
                  />
                </div>
              </div>

              {filtered.length === 0 ? (
                <div className="px-5 py-10 text-center">
                  <p className={`text-xs ${text.muted}`}>No overrides match the current filters.</p>
                </div>
              ) : (
                <div className="divide-y divide-white/5">
                  {filtered
                    .slice() // copy before sort
                    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
                    .map((a) => {
                      const cls = categorise(a.title);
                      const meta = CLASS_META[cls];
                      const sev = severityStyle(a.severity);
                      const drug = parseDrug(a.message);
                      const prescriber = parsePrescriber(a.message);
                      const isAcking = ackingId === a.id;
                      const goToVisit = () => navigate(`/visit/${a.visitId}`);
                      return (
                        <div
                          key={a.id}
                          role="button"
                          tabIndex={0}
                          onClick={goToVisit}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              goToVisit();
                            }
                          }}
                          className="w-full text-left px-5 py-3 hover:bg-white/[0.03] transition-colors flex items-start gap-3 group cursor-pointer focus:outline-none focus:bg-white/[0.04]"
                        >
                          <div className={`w-8 h-8 rounded-lg ${meta.bg} border ${meta.ring} flex items-center justify-center flex-shrink-0 mt-0.5`}>
                            <meta.Icon className={`w-4 h-4 ${meta.color}`} />
                          </div>
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className={`text-xs font-bold ${text.heading}`}>
                                {a.title || 'Override'}
                              </span>
                              <span
                                className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${sev.color}`}
                                style={sev.badgeStyle}
                              >
                                {a.severity}
                              </span>
                              <span
                                className={`inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${meta.badgeColor}`}
                                style={{ background: `rgba(${meta.badgeRgb},0.08)`, border: `1px solid rgba(${meta.badgeRgb},0.2)` }}
                              >
                                {meta.label}
                              </span>
                              {a.visitNumber && (
                                <span className={`text-[10px] ${text.muted}`}>
                                  visit <span className="font-mono">{a.visitNumber}</span>
                                </span>
                              )}
                            </div>
                            <p className={`text-xs mt-1 ${text.muted} line-clamp-2`}>
                              {a.message}
                            </p>
                            <div className="flex items-center gap-3 mt-1.5 flex-wrap">
                              <span className={`text-[10px] ${text.muted}`}>
                                <span className="text-slate-500">Patient:</span> {a.patientName}
                              </span>
                              {drug && (
                                <span className={`text-[10px] ${text.muted}`}>
                                  <span className="text-slate-500">Drug:</span> {drug}
                                </span>
                              )}
                              {prescriber && (
                                <span className={`text-[10px] ${text.muted}`}>
                                  <span className="text-slate-500">By:</span> {prescriber}
                                </span>
                              )}
                              <span className={`text-[10px] ${text.muted}`} title={format(new Date(a.createdAt), 'PPpp')}>
                                {formatDistanceToNow(new Date(a.createdAt), { addSuffix: true })}
                              </span>
                              {a.acknowledged && (
                                <span className="text-[10px] text-emerald-400" title={
                                  a.acknowledgedByName
                                    ? `Reviewed by ${a.acknowledgedByName}${a.acknowledgedAt ? ` ${formatDistanceToNow(new Date(a.acknowledgedAt), { addSuffix: true })}` : ''}`
                                    : 'Reviewed'
                                }>
                                  ✓ acknowledged{a.acknowledgedByName ? ` · ${a.acknowledgedByName}` : ''}
                                </span>
                              )}
                            </div>
                          </div>
                          {/* Acknowledge action — stops propagation so the
                              row's click-to-visit handler doesn't fire. The
                              row stays clickable everywhere else. */}
                          {!a.acknowledged && (
                            <button
                              onClick={(e) => { e.stopPropagation(); handleAcknowledge(a.id); }}
                              disabled={isAcking}
                              className="flex-shrink-0 mt-1 flex items-center gap-1 px-2 py-1 rounded-md bg-emerald-500/15 border border-emerald-500/30 text-emerald-300 text-[10px] font-semibold hover:bg-emerald-500/25 transition-colors disabled:opacity-50"
                              aria-label="Mark this override as reviewed"
                              title="Mark this override as reviewed"
                            >
                              {isAcking ? <Loader2 className="w-3 h-3 animate-spin" /> : <Check className="w-3 h-3" />}
                              Ack
                            </button>
                          )}
                          <ChevronRight className="w-4 h-4 text-slate-500 group-hover:text-white transition-colors flex-shrink-0 mt-2" />
                        </div>
                      );
                    })}
                </div>
              )}
            </div>
          </>
        )}

        {/* ── Cross-hospital break-the-glass governance feed (Phase 3) ── */}
        {hospitalId && <BreakTheGlassIncidents hospitalId={hospitalId} />}
      </div>
    </div>
  );
}

/* ── Sub-components ───────────────────────────────────────────── */

interface KpiTileProps {
  label: string;
  value: number;
  accent: string;
  Icon: React.ComponentType<{ className?: string }>;
  bg: string;
}
function KpiTile({ label, value, accent, Icon, bg }: KpiTileProps) {
  const { text } = useTheme();
  return (
    <div className="px-5 py-4">
      <div className="flex items-center justify-between mb-2">
        <div className={`w-8 h-8 rounded-lg ${bg} flex items-center justify-center`}>
          <Icon className={`w-4 h-4 ${accent}`} />
        </div>
      </div>
      <p className={`text-2xl font-bold ${accent}`}>{value.toLocaleString()}</p>
      <p className={`text-xs mt-0.5 ${text.muted}`}>{label}</p>
    </div>
  );
}

interface ClassRowProps {
  label: string;
  count: number;
  total: number;
  bar: string;
  active: boolean;
  onClick: () => void;
  Icon?: React.ComponentType<{ className?: string }>;
}
function ClassRow({ label, count, total, bar, active, onClick, Icon }: ClassRowProps) {
  const { text } = useTheme();
  const pct = total > 0 ? (count / total) * 100 : 0;
  return (
    <button
      onClick={onClick}
      className={`w-full text-left px-3 py-2 rounded-lg transition-colors ${
        active ? 'bg-white/[0.06]' : 'hover:bg-white/[0.03]'
      }`}
    >
      <div className="flex items-center gap-3">
        <div className="w-32 flex items-center gap-2">
          {Icon && <Icon className={`w-3.5 h-3.5 ${text.muted}`} />}
          <span className={`text-xs font-semibold ${active ? text.heading : text.muted}`}>{label}</span>
        </div>
        <div className="flex-1 h-1.5 rounded-full bg-white/[0.04] overflow-hidden">
          <div
            className={`h-full rounded-full ${bar} transition-all`}
            style={{ width: `${Math.min(pct, 100)}%` }}
          />
        </div>
        <span className={`w-12 text-right text-xs font-bold ${text.heading}`}>{count}</span>
        <span className={`w-12 text-right text-[10px] ${text.muted}`}>{pct.toFixed(0)}%</span>
      </div>
    </button>
  );
}

interface ContributorCardProps {
  title: string;
  name: string;
  count: number;
  total: number;
  Icon: React.ComponentType<{ className?: string }>;
  accent: string;
  bg: string;
}
function ContributorCard({ title, name, count, total, Icon, accent, bg }: ContributorCardProps) {
  const { glassCard, text } = useTheme();
  const pct = total > 0 ? (count / total) * 100 : 0;
  return (
    <div className="rounded-3xl overflow-hidden" style={glassCard}>
      <div className="px-5 py-4">
        <div className="flex items-center gap-3 mb-3">
          <div className={`w-8 h-8 rounded-lg ${bg} flex items-center justify-center`}>
            <Icon className={`w-4 h-4 ${accent}`} />
          </div>
          <p className={`text-xs ${text.muted}`}>{title}</p>
        </div>
        <p className={`text-lg font-bold ${text.heading} truncate`} title={name}>{name}</p>
        <p className={`text-xs mt-1 ${text.muted}`}>
          <span className={`font-bold ${accent}`}>{count}</span> override{count !== 1 ? 's' : ''} · {pct.toFixed(0)}% of period
        </p>
      </div>
    </div>
  );
}
