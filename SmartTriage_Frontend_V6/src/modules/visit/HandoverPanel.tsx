/* ═══════════════════════════════════════════════════════════════
   HandoverPanel — per-visit SBAR / handover for the TREATING clinician.
   Lets a doctor/nurse/paramedic generate and download the handover PDF for
   THEIR patient directly from the chart, without needing the cross-zone
   shift-lead HandoverView. Backend authorizes generate (canAccessVisit) and
   read/PDF (canReadHandoverReport — clinical role + hospital scope).
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { FileSignature, Download, Plus, Loader2, CheckCircle, Clock, RefreshCw } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { handoverApi } from '@/api/handover';
import type { HandoverReport } from '@/api/handover';
import { format } from 'date-fns';

const REPORT_TYPES: { value: string; label: string }[] = [
  { value: 'SHIFT_HANDOVER', label: 'Shift Handover' },
  { value: 'WARD_TRANSFER', label: 'Ward Transfer' },
  { value: 'DISCHARGE_SUMMARY', label: 'Discharge Summary' },
  { value: 'ICU_TRANSFER', label: 'ICU Transfer' },
  { value: 'INTER_HOSPITAL_TRANSFER', label: 'Inter-hospital Transfer' },
];

// Clinical roles the backend permits to generate + read/PDF a handover.
const CLINICAL_ROLES = new Set(['SUPER_ADMIN', 'DOCTOR', 'NURSE', 'PARAMEDIC']);

export function HandoverPanel({ visitId }: { visitId: string }) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const role = useAuthStore((s) => s.user?.role);
  const isClinical = role ? CLINICAL_ROLES.has(role) : false;

  const [reports, setReports] = useState<HandoverReport[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [reportType, setReportType] = useState('SHIFT_HANDOVER');
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setReports((await handoverApi.getForVisit(visitId)) || []);
    } catch {
      setReports([]);
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { load(); }, [load]);

  const generate = async () => {
    setBusy('generate'); setError(null);
    try {
      await handoverApi.generate(visitId, reportType);
      await load();
    } catch {
      setError('Failed to generate handover. You must be a clinician assigned to this hospital.');
    } finally {
      setBusy(null);
    }
  };

  const download = async (id: string) => {
    setBusy(id); setError(null);
    try {
      await handoverApi.downloadPdf(id);
    } catch {
      setError('Failed to download the handover PDF.');
    } finally {
      setBusy(null);
    }
  };

  const input = `px-3 py-2 rounded-lg text-sm focus:outline-none ${isDark ? 'text-white' : 'text-slate-800'}`;

  return (
    <div className="space-y-4">
      <div className="rounded-2xl p-4" style={glassCard}>
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
              <FileSignature className={`w-4 h-4 ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`} />
            </div>
            <div>
              <h3 className={`text-sm font-extrabold ${text.heading}`}>Handover / SBAR</h3>
              <p className={`text-[11px] ${text.muted}`}>Generate and download a structured handover for this patient</p>
            </div>
          </div>
          <button onClick={load} className="w-9 h-9 rounded-xl flex items-center justify-center" style={glassInner}>
            <RefreshCw className={`w-4 h-4 ${text.muted} ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>

        {isClinical && (
          <div className="flex flex-col sm:flex-row sm:items-center gap-2 mt-3">
            <select value={reportType} onChange={(e) => setReportType(e.target.value)} className={input} style={glassInner}>
              {REPORT_TYPES.map((rt) => <option key={rt.value} value={rt.value}>{rt.label}</option>)}
            </select>
            <button
              onClick={generate}
              disabled={busy === 'generate'}
              className="inline-flex items-center gap-1.5 px-4 py-2 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl disabled:opacity-50"
            >
              {busy === 'generate' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
              Generate Handover
            </button>
          </div>
        )}

        {error && (
          <p className={`text-[11px] font-medium mt-2 ${isDark ? 'text-rose-300' : 'text-rose-600'}`}>{error}</p>
        )}
      </div>

      {reports.length === 0 ? (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <p className={`text-xs ${text.muted}`}>No handover reports for this visit yet.</p>
        </div>
      ) : reports.map((r) => (
        <div key={r.id} className="rounded-2xl p-4" style={glassCard}>
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-[10px] font-bold uppercase px-2 py-0.5 rounded-md" style={glassInner}>
                  {(r.reportType || '').replace(/_/g, ' ')}
                </span>
                {r.isAcknowledged ? (
                  <span className={`inline-flex items-center gap-1 text-[10px] font-bold ${isDark ? 'text-emerald-300' : 'text-emerald-600'}`}>
                    <CheckCircle className="w-3 h-3" /> Acknowledged{r.receivedByName ? ` · ${r.receivedByName}` : ''}
                  </span>
                ) : (
                  <span className={`inline-flex items-center gap-1 text-[10px] font-bold ${isDark ? 'text-amber-300' : 'text-amber-600'}`}>
                    <Clock className="w-3 h-3" /> Pending acknowledgement
                  </span>
                )}
              </div>
              <p className={`text-[11px] mt-1 ${text.muted}`}>
                By {r.generatedByName}{r.generatedAt ? ` · ${format(new Date(r.generatedAt), 'MMM d, yyyy HH:mm')}` : ''}
              </p>
            </div>
            {isClinical && (
              <button
                onClick={() => download(r.id)}
                disabled={busy === r.id}
                className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg ${isDark ? 'bg-cyan-500/15 text-cyan-300 hover:bg-cyan-500/25' : 'bg-cyan-50 text-cyan-700 hover:bg-cyan-100'}`}
              >
                {busy === r.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
                PDF
              </button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
