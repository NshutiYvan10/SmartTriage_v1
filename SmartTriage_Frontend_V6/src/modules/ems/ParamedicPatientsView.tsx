/* ═══════════════════════════════════════════════════════════════
   My Patients — the paramedic's transport log.

   A history of every patient THIS crew member transported, with the
   details as they were AT THE TIME of transport: field triage + TEWS,
   field vitals, mechanism/complaint, incident location, and how the
   handover concluded (who received, when) — plus the PCR PDF, which is
   the permanent record of the run.

   Deliberately NOT the hospital Patients registry (paramedics are
   locked out of that — other clinicians' patients are not their
   scope). Every row here comes from emsApi.myRuns(), which is
   own-run-scoped and ordered newest-first server-side.
   ═══════════════════════════════════════════════════════════════ */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Users, RefreshCw, Loader2, Search, Download, MapPin, Clock,
  CheckCircle2, ExternalLink, Siren,
} from 'lucide-react';
import { emsApi } from '@/api/ems';
import type { EmsRun, EmsRunStatus, FieldTriageCategory } from '@/api/ems';
import { PdfPreviewModal, usePdfPreview } from '@/components/PdfPreviewModal';
import { chartPath } from '@/lib/chartNav';
import { formatDistanceToNow, format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';

const STATUS_LABEL: Record<EmsRunStatus, string> = {
  DISPATCHED: 'Dispatched', EN_ROUTE: 'En route', ARRIVED: 'At ED',
  HANDED_OFF: 'Handed off', CANCELLED: 'Cancelled',
};
const STATUS_CHIP: Record<EmsRunStatus, string> = {
  DISPATCHED: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]',
  EN_ROUTE: 'bg-[rgba(245,158,11,0.08)] text-amber-600 border border-[rgba(245,158,11,0.2)]',
  ARRIVED: 'bg-[rgba(99,102,241,0.08)] text-indigo-600 border border-[rgba(99,102,241,0.2)]',
  HANDED_OFF: 'bg-[rgba(16,185,129,0.08)] text-emerald-600 border border-[rgba(16,185,129,0.2)]',
  CANCELLED: 'bg-[rgba(100,116,139,0.08)] text-slate-600 border border-[rgba(100,116,139,0.2)]',
};
function triageChip(c: FieldTriageCategory | null): string {
  switch (c) {
    case 'RED':    return 'bg-rose-500/20 text-rose-500 border border-rose-500/30';
    case 'ORANGE': return 'bg-amber-500/20 text-amber-600 border border-amber-500/30';
    case 'YELLOW': return 'bg-yellow-500/20 text-yellow-600 border border-yellow-500/30';
    case 'GREEN':  return 'bg-emerald-500/20 text-emerald-600 border border-emerald-500/30';
    case 'BLUE':   return 'bg-blue-500/20 text-blue-500 border border-blue-500/30';
    default:       return 'bg-slate-500/20 text-slate-500 border border-slate-500/30';
  }
}

type StatusFilter = 'ALL' | 'HANDED_OFF' | 'ACTIVE' | 'CANCELLED';

export function ParamedicPatientsView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();

  const [runs, setRuns] = useState<EmsRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  const [downloadingPcr, setDownloadingPcr] = useState<string | null>(null);
  const { showPdf, previewProps } = usePdfPreview();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await emsApi.myRuns();
      setRuns(data || []);
    } catch (e) {
      console.error('[ParamedicPatients] load failed:', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return [...runs]
      // Defensive newest-first (the backend already orders dispatchedAt DESC).
      .sort((a, b) => new Date(b.dispatchedAt).getTime() - new Date(a.dispatchedAt).getTime())
      .filter((r) => {
        if (filter === 'HANDED_OFF' && r.status !== 'HANDED_OFF') return false;
        if (filter === 'CANCELLED' && r.status !== 'CANCELLED') return false;
        if (filter === 'ACTIVE' && (r.status === 'HANDED_OFF' || r.status === 'CANCELLED')) return false;
        if (!q) return true;
        return [r.patientName, r.visitNumber, r.mechanism, r.incidentLocation]
          .some((f) => f && f.toLowerCase().includes(q));
      });
  }, [runs, query, filter]);

  const downloadPcr = async (run: EmsRun) => {
    setDownloadingPcr(run.id);
    try {
      const { blob, filename } = await emsApi.downloadPcr(run.id);
      // Preview-first: open the PCR in the in-app viewer; download/print
      // are secondary actions inside the modal (matches every other report).
      showPdf(blob, filename);
    } catch (e) {
      console.error('[ParamedicPatients] PCR download failed', e);
    } finally {
      setDownloadingPcr(null);
    }
  };

  const chips: { id: StatusFilter; label: string }[] = [
    { id: 'ALL', label: `All (${runs.length})` },
    { id: 'HANDED_OFF', label: 'Handed off' },
    { id: 'ACTIVE', label: 'Active' },
    { id: 'CANCELLED', label: 'Cancelled' },
  ];

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-5xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-rose-700 to-rose-600 px-6 py-5">
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center">
                  <Users className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-xl font-bold text-white tracking-wide">My Patients</h1>
                  <p className="text-white/70 text-sm">
                    Patients you transported — their details as recorded at the time
                  </p>
                </div>
              </div>
              <button onClick={load} className="w-11 h-11 rounded-xl bg-white/15 flex items-center justify-center hover:bg-white/25" title="Refresh">
                <RefreshCw className={`w-5 h-5 text-white ${loading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>
        </div>

        {/* Search + filters */}
        <div className="rounded-2xl p-3 flex flex-col md:flex-row md:items-center gap-2" style={glassCard}>
          <div className="relative flex-1 min-w-0">
            <Search className={`w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 ${text.muted}`} />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search name, visit number, mechanism or location…"
              className={`w-full pl-9 pr-3 py-2.5 text-sm rounded-xl focus:outline-none focus:ring-2 focus:ring-rose-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
              style={glassInner}
            />
          </div>
          <div className="flex items-center gap-1.5 flex-wrap">
            {chips.map((c) => (
              <button
                key={c.id}
                onClick={() => setFilter(c.id)}
                className={`px-3 py-2 rounded-xl text-xs font-bold transition-colors ${
                  filter === c.id
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : `${text.body} hover:bg-white/5`
                }`}
              >
                {c.label}
              </button>
            ))}
          </div>
        </div>

        {/* Transport log */}
        {loading && rows.length === 0 ? (
          <div className="flex items-center justify-center py-10"><Loader2 className="w-7 h-7 animate-spin text-rose-500" /></div>
        ) : rows.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <Users className="w-10 h-10 mx-auto mb-3 text-slate-400" />
            <p className={`text-base font-bold ${text.heading}`}>
              {runs.length === 0 ? 'No transported patients yet' : 'No matches'}
            </p>
            <p className={`text-sm ${text.muted}`}>
              {runs.length === 0
                ? 'Patients appear here as you document runs on the Siren page.'
                : 'Try widening the status filter or clearing the search.'}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {rows.map((run) => {
              const vitalsPresent = run.fieldGcs != null || run.fieldHr != null || run.fieldRespRate != null
                || run.fieldSbp != null || run.fieldSpo2 != null || run.fieldTemp != null || run.fieldGlucose != null;
              return (
                <div key={run.id} className="rounded-2xl p-4" style={glassCard}>
                  {/* Chips + when */}
                  <div className="flex items-start justify-between gap-2 mb-2 flex-wrap">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${STATUS_CHIP[run.status]}`}>
                        {STATUS_LABEL[run.status]}
                      </span>
                      {run.fieldTriageCategory && (
                        <span className={`text-xs font-bold uppercase tracking-wider px-2 py-1 rounded-lg ${triageChip(run.fieldTriageCategory)}`}>
                          {run.fieldTriageCategory}{run.fieldTewsScore != null ? ` · TEWS ${run.fieldTewsScore}` : ''}
                        </span>
                      )}
                    </div>
                    <span className={`text-xs shrink-0 ${text.muted}`} title={format(new Date(run.dispatchedAt), 'dd MMM yyyy, HH:mm')}>
                      {format(new Date(run.dispatchedAt), 'dd MMM yyyy')} · {formatDistanceToNow(new Date(run.dispatchedAt), { addSuffix: true })}
                    </span>
                  </div>

                  {/* Who + what */}
                  <PatientContextLine
                    patientName={run.patientName}
                    visitNumber={run.visitNumber}
                    className={`text-sm mb-0.5 ${text.body}`}
                  />
                  <div className={`text-base font-bold ${text.heading}`}>{run.mechanism ?? 'Patient'}</div>
                  {run.incidentLocation && (
                    <div className={`text-sm ${text.muted} flex items-center gap-1`}><MapPin className="w-4 h-4" /> {run.incidentLocation}</div>
                  )}

                  {/* Field vitals AT THE TIME */}
                  {vitalsPresent && (
                    <div className="rounded-xl px-3 py-2 mt-2 grid grid-cols-4 sm:grid-cols-7 gap-1 text-sm" style={glassInner}>
                      <AtTimeStat label="GCS" value={run.fieldGcs} text={text} />
                      <AtTimeStat label="HR" value={run.fieldHr} text={text} />
                      <AtTimeStat label="RR" value={run.fieldRespRate} text={text} />
                      <AtTimeStat label="BP" value={run.fieldSbp != null ? `${run.fieldSbp}/${run.fieldDbp ?? '—'}` : null} text={text} />
                      <AtTimeStat label="SpO₂" value={run.fieldSpo2 != null ? `${run.fieldSpo2}%` : null} text={text} />
                      <AtTimeStat label="Temp" value={run.fieldTemp != null ? `${run.fieldTemp}°` : null} text={text} />
                      <AtTimeStat label="Gluc" value={run.fieldGlucose} text={text} />
                    </div>
                  )}

                  {/* Handover outcome */}
                  {run.handedOffToName && (
                    <div className={`text-sm mt-2 flex items-center gap-1 ${text.muted}`}>
                      <CheckCircle2 className="w-4 h-4 text-emerald-500" /> Handed off to <span className={text.body}>{run.handedOffToName}</span>
                      {run.handedOffAt && <> · {format(new Date(run.handedOffAt), 'dd MMM, HH:mm')}</>}
                    </div>
                  )}

                  {/* Actions — the PCR is the permanent at-the-time record */}
                  <div className="flex flex-wrap gap-2 mt-3">
                    <button onClick={() => downloadPcr(run)} disabled={downloadingPcr === run.id}
                      className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-sm font-bold bg-cyan-600 text-white hover:bg-cyan-700 disabled:opacity-50">
                      {downloadingPcr === run.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />} PCR
                    </button>
                    {run.visitId && (
                      <button onClick={() => navigate(chartPath(run.visitId!))}
                        className={`inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-sm font-bold ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
                        <ExternalLink className="w-4 h-4" /> Open chart
                      </button>
                    )}
                    {run.status !== 'HANDED_OFF' && run.status !== 'CANCELLED' && (
                      <button onClick={() => navigate('/ems')}
                        className={`inline-flex items-center gap-1.5 px-4 py-2 rounded-xl text-sm font-bold ${isDark ? 'bg-white/10 text-white hover:bg-white/15' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}>
                        <Siren className="w-4 h-4" /> Manage in Siren
                      </button>
                    )}
                    {run.handedOffAt && (
                      <span className={`inline-flex items-center gap-1 text-xs ml-auto ${text.muted}`}>
                        <Clock className="w-3.5 h-3.5" /> Record as of transport time
                      </span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
      <PdfPreviewModal {...previewProps} />
    </div>
  );
}

function AtTimeStat({ label, value, text }: { label: string; value: string | number | null | undefined; text: any }) {
  return (
    <div>
      <div className={`text-[10px] uppercase font-bold ${text.label}`}>{label}</div>
      <div className={`font-bold ${text.heading}`}>{value ?? '—'}</div>
    </div>
  );
}

export default ParamedicPatientsView;
