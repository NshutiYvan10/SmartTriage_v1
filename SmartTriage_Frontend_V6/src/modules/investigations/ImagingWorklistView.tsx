/* ═══════════════════════════════════════════════════════════════
   Imaging & Diagnostics Worklist — technician surface.

   The clinical-safety fix for the "ordered X-ray reaches no one"
   black hole. Imaging/ECG orders (X-ray, CT, MRI, ultrasound,
   radiology, ECG) do NOT route to the lab inbox and there is no
   radiographer role — so they land HERE, a cross-patient worklist
   the diagnostics technician (or a nurse) works through, exactly
   like the lab tech works the lab inbox.

   Two columns:
     • To perform  — status ORDERED (Start → In progress)
     • In progress — being performed (Enter report → Resulted)

   Recording a report fires the existing INVESTIGATION_RESULTED
   alert back to the ordering doctor, and the row leaves the queue.
   Live WebSocket push (/topic/diagnostics/{hospitalId}) so a newly
   ordered study lands without polling. STAT cards pulse.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  ScanLine, Clock, AlertTriangle, Loader2, RefreshCw, PlayCircle,
  FileText, CheckCircle2, X, ChevronRight, Activity,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { investigationApi } from '@/api/investigations';
import type { InvestigationResponse } from '@/api/types';
import { subscribeToDiagnostics } from '@/api/websocket';
import { useTheme } from '@/hooks/useTheme';
import { ModalPortal } from '@/components/ModalPortal';
import { PatientContextLine } from '@/components/PatientContextLine';
import { chartPathForRole } from '@/lib/chartNav';
import { LabDocuments, type LabDocumentsHandle } from '@/modules/lab/LabDocuments';

/** Minutes since order, with a soft target so overdue studies stand out. */
const PRIORITY_TARGET_MIN: Record<string, number> = { STAT: 30, URGENT: 120, ROUTINE: 1440 };

function priorityStyle(priority: string): { chip: string; pulse: boolean; ring: string } {
  switch ((priority || '').toUpperCase()) {
    case 'STAT':   return { chip: 'bg-rose-500/15 text-rose-500',  pulse: true,  ring: 'ring-2 ring-rose-500/40' };
    case 'URGENT': return { chip: 'bg-amber-500/15 text-amber-500', pulse: false, ring: 'ring-1 ring-amber-500/30' };
    default:       return { chip: 'bg-slate-500/15 text-slate-500', pulse: false, ring: '' };
  }
}

function elapsedInfo(iso: string, priority: string): { label: string; overdue: boolean } {
  const mins = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  const target = PRIORITY_TARGET_MIN[(priority || 'ROUTINE').toUpperCase()] ?? 1440;
  const human = mins < 60 ? `${Math.max(0, mins)}m` : `${Math.floor(mins / 60)}h ${mins % 60}m`;
  return { label: `${human} ago`, overdue: mins > target };
}

export function ImagingWorklistView() {
  const { cardClass, glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [rows, setRows] = useState<InvestigationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState<Set<string>>(new Set());
  const [reportFor, setReportFor] = useState<InvestigationResponse | null>(null);

  const load = useCallback(async () => {
    if (!hospitalId) { setLoading(false); return; }
    setLoading(true);
    setErr(null);
    try {
      const data = await investigationApi.imagingWorklist(hospitalId);
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load the imaging worklist');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { void load(); }, [load]);

  // Live push — a newly ordered / transitioned study lands without polling.
  // Debounced full re-fetch mirrors the lab worklist's live behaviour.
  const reloadTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToDiagnostics(hospitalId, () => {
      if (reloadTimer.current) clearTimeout(reloadTimer.current);
      reloadTimer.current = setTimeout(() => { void load(); }, 400);
    });
    return () => {
      if (reloadTimer.current) clearTimeout(reloadTimer.current);
      unsub();
    };
  }, [hospitalId, load]);

  const withBusy = useCallback(async (id: string, fn: () => Promise<unknown>) => {
    setBusy((s) => new Set(s).add(id));
    try {
      await fn();
      await load();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Action failed');
    } finally {
      setBusy((s) => { const n = new Set(s); n.delete(id); return n; });
    }
  }, [load]);

  const toPerform = rows.filter((r) => r.status === 'ORDERED');
  const inProgress = rows.filter((r) => r.status === 'IN_PROGRESS');

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <ScanLine className="w-5 h-5 text-cyan-300" />
            </div>
            <div className="flex-1">
              <h1 className="text-lg font-bold text-white">Imaging &amp; Diagnostics</h1>
              <p className="text-sm text-white/50">
                Ordered X-ray, CT, MRI, ultrasound and ECG studies to perform and report.
                Recording a report notifies the ordering doctor.
              </p>
            </div>
            <button
              onClick={load}
              disabled={loading}
              className="inline-flex items-center gap-2 px-3 py-2 text-xs font-bold rounded-xl bg-white/10 hover:bg-white/20 text-white transition-colors"
            >
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              Refresh
            </button>
          </div>
          <div className="px-5 md:px-6 py-3 flex flex-wrap items-center gap-2 text-[11px]" style={{ borderTop: borderStyle }}>
            <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-lg font-bold ${text.body}`} style={glassInner}>
              <Clock className="w-3 h-3" /> To perform {toPerform.length}
            </span>
            <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-lg font-bold ${text.body}`} style={glassInner}>
              <Activity className="w-3 h-3" /> In progress {inProgress.length}
            </span>
          </div>
        </div>

        {err && (
          <div className="rounded-md border border-red-500/30 bg-red-500/20 px-3 py-2 text-sm text-red-300 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 mt-0.5" />
            <span>{err}</span>
          </div>
        )}

        {loading && rows.length === 0 ? (
          <div className={`text-center py-16 ${text.muted}`}>
            <Loader2 className="w-8 h-8 animate-spin mx-auto mb-3 opacity-50" />
            <p className="text-sm">Loading the imaging worklist…</p>
          </div>
        ) : rows.length === 0 ? (
          <div className={`${cardClass} px-5 py-12 text-center ${text.muted}`} style={glassCard}>
            <ScanLine className="w-8 h-8 mx-auto mb-3 opacity-50" />
            <p className="text-sm">No imaging or ECG studies waiting.</p>
            <p className="text-[11px] mt-1">Ordered X-ray/CT/MRI/ultrasound/ECG studies appear here for you to perform.</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <WorklistColumn
              title="To perform" helper="Ordered — study not yet started"
              icon={Clock} rows={toPerform}
              glassCard={glassCard} isDark={isDark} text={text}
              chartHref={(vid) => chartPathForRole(user?.role, vid)}
              renderActions={(r) => (
                <button
                  type="button"
                  disabled={busy.has(r.id)}
                  onClick={() => withBusy(r.id, () => investigationApi.markInProgress(r.id))}
                  className="inline-flex items-center gap-1 px-3 py-1.5 text-[11px] font-bold rounded-xl bg-cyan-600 hover:bg-cyan-700 text-white transition-colors disabled:opacity-50"
                >
                  {busy.has(r.id) ? <Loader2 className="w-3 h-3 animate-spin" /> : <PlayCircle className="w-3 h-3" />}
                  Start
                </button>
              )}
            />
            <WorklistColumn
              title="In progress" helper="Being performed — enter the report"
              icon={Activity} rows={inProgress}
              glassCard={glassCard} isDark={isDark} text={text}
              chartHref={(vid) => chartPathForRole(user?.role, vid)}
              renderActions={(r) => (
                <button
                  type="button"
                  onClick={() => setReportFor(r)}
                  className="inline-flex items-center gap-1 px-3 py-1.5 text-[11px] font-bold rounded-xl bg-emerald-600 hover:bg-emerald-700 text-white transition-colors"
                >
                  <FileText className="w-3 h-3" /> Enter report
                </button>
              )}
            />
          </div>
        )}
      </div>

      {reportFor && (
        <ReportModal
          investigation={reportFor}
          onClose={() => setReportFor(null)}
          onSaved={async () => { setReportFor(null); await load(); }}
        />
      )}
    </div>
  );
}

function WorklistColumn({
  title, helper, icon: Icon, rows, glassCard, isDark, text, renderActions, chartHref,
}: {
  title: string;
  helper: string;
  icon: typeof Clock;
  rows: InvestigationResponse[];
  glassCard: React.CSSProperties;
  isDark: boolean;
  text: { heading: string; muted: string; body: string; label: string };
  renderActions: (r: InvestigationResponse) => React.ReactNode;
  chartHref: (visitId: string) => string;
}) {
  return (
    <div className="rounded-2xl overflow-hidden" style={glassCard}>
      <div className={`px-4 py-2.5 flex items-center gap-2 border-b ${isDark ? 'border-white/10' : 'border-slate-200/60'}`}>
        <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-slate-600`}
              style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
          <Icon className="w-3 h-3" /> {title}
          <span className="ml-1 inline-flex items-center justify-center min-w-[20px] px-1 rounded bg-white/10">{rows.length}</span>
        </span>
        <span className={`text-[11px] ${text.muted}`}>{helper}</span>
      </div>
      {rows.length === 0 ? (
        <p className={`px-4 py-8 text-center text-[12px] ${text.muted}`}>Nothing here.</p>
      ) : (
        <ul>
          {rows.map((r) => {
            const ps = priorityStyle(r.priority);
            const el = elapsedInfo(r.orderedAt, r.priority);
            return (
              <li key={r.id} className={`px-4 py-3 border-b last:border-0 ${isDark ? 'border-white/5' : 'border-slate-100'} ${ps.ring}`}>
                <div className="flex items-start gap-3">
                  <div className="flex-1 min-w-0">
                    <PatientContextLine
                      patientName={r.patientName}
                      zone={r.currentZone}
                      bedLabel={r.currentBedLabel}
                      visitNumber={r.visitNumber}
                      className={`text-[11px] mb-0.5 ${text.body}`}
                    />
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-sm font-bold ${text.heading}`}>{r.testName}</span>
                      <span className={`inline-flex items-center px-2 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider ${ps.chip} ${ps.pulse ? 'animate-pulse' : ''}`}>
                        {(r.priority || 'ROUTINE').toUpperCase()}
                      </span>
                      <span className={`text-[10px] uppercase tracking-wider ${text.muted}`}>
                        {r.investigationType?.replace(/_/g, ' ')}
                      </span>
                    </div>
                    <div className={`text-[11px] mt-0.5 flex items-center gap-3 flex-wrap ${el.overdue ? 'text-rose-500 font-semibold' : text.muted}`}>
                      <span>Ordered {el.label}{el.overdue ? ' · overdue' : ''}</span>
                      {r.orderedByName && <span className={text.muted}>by {r.orderedByName}</span>}
                    </div>
                    {r.notes && (
                      <p className={`text-[11px] mt-1 ${text.body} line-clamp-2`}>{r.notes}</p>
                    )}
                  </div>
                  <div className="flex flex-col items-end gap-2 flex-shrink-0">
                    {renderActions(r)}
                    <Link
                      to={chartHref(r.visitId)}
                      className={`inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg transition-colors ${isDark ? 'bg-white/10 hover:bg-white/20 text-white' : 'bg-slate-100 hover:bg-slate-200 text-slate-700'}`}
                    >
                      Chart <ChevronRight className="w-3 h-3" />
                    </Link>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function ReportModal({
  investigation, onClose, onSaved,
}: {
  investigation: InvestigationResponse;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [report, setReport] = useState('');
  const [abnormal, setAbnormal] = useState(false);
  const [critical, setCritical] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const docsRef = useRef<LabDocumentsHandle>(null);

  const canSave = report.trim().length > 0 && !saving;

  const submit = async () => {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    // A file that was SELECTED but not yet attached must be uploaded as part of
    // Save — otherwise it would be silently discarded when the modal closes
    // (the "attached a report but the doctor never saw it" failure).
    const flushed = await docsRef.current?.flushPending();
    if (flushed === false) {
      setError('The report document failed to attach — fix or remove it, then save again.');
      setSaving(false);
      return;
    }
    // Keep the critical write separate from the post-save close/reload: if the
    // report fails to save we MUST keep the modal open with the error so the
    // technician can retry; only once it is safely persisted do we close.
    try {
      await investigationApi.recordResult(investigation.id, {
        investigationId: investigation.id,
        result: report.trim(),
        // A critical finding is always abnormal — keep the two flags consistent
        // so downstream alert severity (HIGH vs CRITICAL) is correct.
        isAbnormal: abnormal || critical,
        isCritical: critical,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save the report');
      setSaving(false);
      return;
    }
    // Saved. Closing + reloading the worklist is best-effort (the parent's
    // load() swallows its own errors), so it can't strand a saved report.
    onSaved();
  };

  return (
    <ModalPortal>
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(2,6,23,0.65)', backdropFilter: 'blur(4px)' }}
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg rounded-2xl p-5 space-y-4 animate-scale-in"
        style={glassCard}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3">
          <div>
            <h3 className={`text-sm font-bold flex items-center gap-2 ${text.heading}`}>
              <FileText className="w-4 h-4 text-emerald-500" /> Record report — {investigation.testName}
            </h3>
            <PatientContextLine
              patientName={investigation.patientName}
              zone={investigation.currentZone}
              bedLabel={investigation.currentBedLabel}
              visitNumber={investigation.visitNumber}
              className={`text-[11px] mt-1 ${text.body}`}
            />
          </div>
          <button type="button" onClick={onClose} className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`} aria-label="Close">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
            Findings / Report
          </label>
          <textarea
            value={report}
            onChange={(e) => setReport(e.target.value)}
            placeholder="e.g. Right lower-lobe consolidation. No pneumothorax. Heart size normal."
            rows={5}
            autoFocus
            className={`w-full px-3 py-2.5 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
        </div>

        {/* Attach the full report document (film/scan/PDF) — the interim standard:
            structured findings above + the full report here. Save flushes any
            selected-but-unattached file via docsRef. */}
        <LabDocuments ref={docsRef} investigationId={investigation.id} canManage />

        <div className="flex items-center gap-4">
          <label className={`inline-flex items-center gap-2 text-xs font-semibold ${text.body} cursor-pointer`}>
            <input type="checkbox" checked={abnormal} disabled={critical} onChange={(e) => setAbnormal(e.target.checked)} className="accent-amber-500" />
            Abnormal
          </label>
          <label className={`inline-flex items-center gap-2 text-xs font-semibold ${text.body} cursor-pointer`}>
            <input type="checkbox" checked={critical} onChange={(e) => setCritical(e.target.checked)} className="accent-rose-500" />
            Critical <span className={`text-[10px] font-normal ${text.muted}`}>(notifies doctor urgently)</span>
          </label>
        </div>

        {error && (
          <div className="rounded-md border border-red-500/30 bg-red-500/20 px-3 py-2 text-xs text-red-300 flex items-start gap-2">
            <AlertTriangle className="w-3.5 h-3.5 mt-0.5" /> <span>{error}</span>
          </div>
        )}

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={submit}
            disabled={!canSave}
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-emerald-600 hover:bg-emerald-700 text-white rounded-xl text-xs font-bold shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
            Save report
          </button>
          <button type="button" onClick={onClose} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
        </div>
      </div>
    </div>
    </ModalPortal>
  );
}

export default ImagingWorklistView;
