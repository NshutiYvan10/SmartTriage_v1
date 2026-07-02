/* ═══════════════════════════════════════════════════════════════
   LabPatientRecordView — a LAB_TECHNICIAN's SCOPED per-patient record.

   This is what the lab tech's "Chart" button opens INSTEAD of the full
   clinical chart (/visit/:id, which is triage-gated and — for a lab tech —
   an over-share of diagnoses, notes, meds, and history). It shows only the
   lab-relevant record for one visit:
     • a safe patient header (name / visit # / location) derived from the
       denormalised order context — no full-registry PHI is fetched
     • the visit's lab orders + results (drill in for per-analyte detail)
     • the visit's imaging / ECG studies
   Reuses the already-authz'd per-visit endpoints (both permit LAB_TECHNICIAN,
   hospital-scoped).
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  FlaskConical, ScanLine, Loader2, ArrowLeft, AlertTriangle, AlertOctagon,
  FileSearch, CheckCircle2, Clock,
} from 'lucide-react';
import { labApi, type LabOrder } from '@/api/lab';
import { investigationApi } from '@/api/investigations';
import type { InvestigationResponse } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';
import { LabTestDetailModal } from './LabTestDetailModal';

const IMAGING_TYPES = ['XRAY', 'CT_SCAN', 'MRI', 'ULTRASOUND', 'RADIOLOGY', 'ECG'];

function fmt(iso: string | null | undefined): string {
  if (!iso) return '—';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
}

export function LabPatientRecordView() {
  const { visitId = '' } = useParams();
  const navigate = useNavigate();
  const { cardClass, glassCard, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  const [orders, setOrders] = useState<LabOrder[]>([]);
  const [imaging, setImaging] = useState<InvestigationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ investigationId: string; testName: string } | null>(null);

  const load = useCallback(async () => {
    if (!visitId) return;
    setLoading(true);
    setErr(null);
    const [labRes, invRes] = await Promise.allSettled([
      labApi.getForVisit(visitId, 0, 100),
      investigationApi.getAllByVisit(visitId),
    ]);
    if (labRes.status === 'fulfilled' && labRes.value) {
      setOrders(Array.isArray(labRes.value.content) ? labRes.value.content : []);
    } else if (labRes.status === 'rejected') {
      setErr(labRes.reason instanceof Error ? labRes.reason.message : 'Failed to load lab orders');
    }
    if (invRes.status === 'fulfilled' && Array.isArray(invRes.value)) {
      setImaging(invRes.value.filter((i) => IMAGING_TYPES.includes(i.investigationType)));
    }
    setLoading(false);
  }, [visitId]);

  useEffect(() => { void load(); }, [load]);

  // Safe header from the denormalised order/investigation context (no PHI fetch).
  const header = orders[0] ?? null;
  const invHeader = imaging[0] ?? null;
  const patientName = header?.patientName ?? invHeader?.patientName ?? 'Patient';
  const zone = header?.currentZone ?? invHeader?.currentZone ?? null;
  const bed = header?.currentBedLabel ?? invHeader?.currentBedLabel ?? null;
  const visitNumber = header?.visitNumber ?? invHeader?.visitNumber ?? null;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-4xl mx-auto space-y-4 animate-fade-in">
        <button onClick={() => navigate(-1)} className={`inline-flex items-center gap-1.5 text-xs font-bold ${text.muted} hover:${text.heading}`}>
          <ArrowLeft className="w-3.5 h-3.5" /> Back
        </button>

        {/* Safe patient header */}
        <div className="rounded-3xl overflow-hidden" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <p className="text-[11px] uppercase tracking-wider text-white/40 font-bold mb-1">Lab record</p>
            <h1 className="text-lg font-bold text-white">{patientName}</h1>
            <PatientContextLine patientName={null} zone={zone} bedLabel={bed} visitNumber={visitNumber}
              className="text-xs text-white/60 mt-1" />
          </div>
          <div className="px-5 py-2 text-[11px]" style={{ borderTop: borderStyle }}>
            <span className={text.muted}>Lab &amp; diagnostics only — this is not the full clinical chart.</span>
          </div>
        </div>

        {err && (
          <div className="rounded-md border border-red-500/30 bg-red-500/20 px-3 py-2 text-sm text-red-300 flex items-start gap-2">
            <AlertTriangle className="w-4 h-4 mt-0.5" /> <span>{err}</span>
          </div>
        )}

        {loading && orders.length === 0 && imaging.length === 0 ? (
          <div className={`text-center py-16 ${text.muted}`}><Loader2 className="w-8 h-8 animate-spin mx-auto opacity-50" /></div>
        ) : (
          <>
            {/* Lab orders */}
            <Section title="Lab orders" icon={FlaskConical} count={orders.length} cardClass={cardClass} glassCard={glassCard} isDark={isDark} text={text} borderStyle={borderStyle}>
              {orders.map((o) => (
                <li key={o.id} className={`px-4 py-2.5 flex items-center gap-3 border-b last:border-0 ${isDark ? 'border-white/5' : 'border-slate-100'}`}>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-sm font-bold ${text.heading}`}>{o.testName}</span>
                      <span className={`text-[10px] font-bold uppercase px-1.5 py-0.5 rounded ${o.status === 'RESULTED' ? 'bg-emerald-500/15 text-emerald-600' : 'bg-slate-500/15 text-slate-500'}`}>{String(o.status).replace(/_/g, ' ')}</span>
                      {o.isCritical && <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-rose-600 text-white">Critical</span>}
                      {!o.isCritical && o.isAbnormal && <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">Abnormal</span>}
                    </div>
                    <div className={`text-[11px] mt-0.5 flex items-center gap-3 flex-wrap ${text.muted}`}>
                      <span className="inline-flex items-center gap-1"><Clock className="w-3 h-3" /> Ordered {fmt(o.orderedAt)}</span>
                      {o.resultedAt && <span className="inline-flex items-center gap-1"><CheckCircle2 className="w-3 h-3" /> Resulted {fmt(o.resultedAt)}</span>}
                    </div>
                  </div>
                  {o.investigationId && (
                    <button type="button" onClick={() => setDetail({ investigationId: o.investigationId!, testName: o.testName })}
                      className={`inline-flex items-center gap-1 px-3 py-1.5 text-[11px] font-bold rounded-xl transition-colors ${isDark ? 'bg-white/10 hover:bg-white/20 text-white' : 'bg-slate-100 hover:bg-slate-200 text-slate-700'}`}>
                      <FileSearch className="w-3 h-3" /> Details
                    </button>
                  )}
                </li>
              ))}
            </Section>

            {/* Imaging */}
            <Section title="Imaging & ECG" icon={ScanLine} count={imaging.length} cardClass={cardClass} glassCard={glassCard} isDark={isDark} text={text} borderStyle={borderStyle}>
              {imaging.map((i) => (
                <li key={i.id} className={`px-4 py-2.5 border-b last:border-0 ${isDark ? 'border-white/5' : 'border-slate-100'}`}>
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`text-sm font-bold ${text.heading}`}>{i.testName}</span>
                    <span className={`text-[10px] uppercase tracking-wider ${text.muted}`}>{i.investigationType?.replace(/_/g, ' ')}</span>
                    <span className={`text-[10px] font-bold uppercase px-1.5 py-0.5 rounded ${i.status === 'RESULTED' ? 'bg-emerald-500/15 text-emerald-600' : 'bg-slate-500/15 text-slate-500'}`}>{String(i.status).replace(/_/g, ' ')}</span>
                    {i.isCritical && <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-rose-600 text-white">Critical</span>}
                  </div>
                  {i.status === 'RESULTED' && i.result && (
                    <p className={`text-[12px] mt-1 ${text.body}`}>{i.result}</p>
                  )}
                  <div className={`text-[11px] mt-0.5 ${text.muted}`}>Ordered {fmt(i.orderedAt)}{i.resultedAt ? ` · Resulted ${fmt(i.resultedAt)}` : ''}</div>
                </li>
              ))}
            </Section>
          </>
        )}
      </div>

      {detail && (
        <LabTestDetailModal visitId={visitId} investigationId={detail.investigationId} testName={detail.testName}
          onClose={() => setDetail(null)} onChanged={load} />
      )}
    </div>
  );
}

function Section({ title, icon: Icon, count, cardClass, glassCard, isDark, text, borderStyle, children }: {
  title: string; icon: typeof FlaskConical; count: number; cardClass: string; glassCard: React.CSSProperties;
  isDark: boolean; text: { heading: string; muted: string; body: string }; borderStyle: string; children: React.ReactNode;
}) {
  return (
    <div className={`${cardClass} overflow-hidden`} style={glassCard}>
      <div className="px-4 py-2.5 flex items-center gap-2" style={{ borderBottom: borderStyle }}>
        <Icon className="w-4 h-4 text-cyan-500" />
        <span className={`text-sm font-bold ${text.heading}`}>{title}</span>
        <span className="ml-1 inline-flex items-center justify-center min-w-[20px] px-1 rounded bg-white/10 text-[11px]">{count}</span>
      </div>
      {count === 0 ? <p className={`px-4 py-6 text-center text-[12px] ${text.muted}`}>None for this visit.</p> : <ul>{children}</ul>}
    </div>
  );
}

export default LabPatientRecordView;
