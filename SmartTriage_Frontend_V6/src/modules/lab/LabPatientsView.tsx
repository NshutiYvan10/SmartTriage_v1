/* ═══════════════════════════════════════════════════════════════
   LabPatientsView — the LAB_TECHNICIAN's scoped patient list.

   SECURITY: replaces the full hospital patient registry (which the
   lab tech is now locked out of). Shows ONLY patients the lab is
   actively working — those with pending orders, unacknowledged
   critical results, or imaging/ECG studies — with just the fields
   needed to connect a specimen to the right patient (name, visit #,
   location) plus outstanding-work counts. No national ID, phone,
   address, allergies or clinical history.

   Clicking a row opens the SCOPED lab record (not the full chart).
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Users, Loader2, RefreshCw, Search, AlertOctagon, FlaskConical,
  ScanLine, ChevronRight, ShieldCheck,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { labApi, type LabPatientSummary } from '@/api/lab';
import { subscribeToLabOrders, subscribeToDiagnostics } from '@/api/websocket';
import { useTheme } from '@/hooks/useTheme';
import { PatientContextLine } from '@/components/PatientContextLine';
import { labChartPath } from '@/lib/chartNav';

export function LabPatientsView() {
  const navigate = useNavigate();
  const { cardClass, glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [rows, setRows] = useState<LabPatientSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [q, setQ] = useState('');

  const load = useCallback(async () => {
    if (!hospitalId) { setLoading(false); return; }
    setLoading(true);
    setErr(null);
    try {
      const data = await labApi.getLabPatients(hospitalId);
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load lab patients');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { void load(); }, [load]);

  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    if (!hospitalId) return;
    const bump = () => { if (timer.current) clearTimeout(timer.current); timer.current = setTimeout(() => { void load(); }, 500); };
    const u1 = subscribeToLabOrders(hospitalId, bump);
    const u2 = subscribeToDiagnostics(hospitalId, bump);
    return () => { if (timer.current) clearTimeout(timer.current); u1(); u2(); };
  }, [hospitalId, load]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter((r) =>
      (r.patientName ?? '').toLowerCase().includes(needle) ||
      (r.visitNumber ?? '').toLowerCase().includes(needle));
  }, [rows, q]);

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-5xl mx-auto space-y-4 animate-fade-in">
        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <Users className="w-5 h-5 text-cyan-300" />
            </div>
            <div className="flex-1">
              <h1 className="text-lg font-bold text-white">Lab Patients</h1>
              <p className="text-sm text-white/50">Patients you have lab or imaging work for. Tap to open the lab record.</p>
            </div>
            <button onClick={load} disabled={loading}
              className="inline-flex items-center gap-2 px-3 py-2 text-xs font-bold rounded-xl bg-white/10 hover:bg-white/20 text-white transition-colors">
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              Refresh
            </button>
          </div>
          <div className="px-5 md:px-6 py-3 flex items-center gap-2" style={{ borderTop: borderStyle }}>
            <div className="relative flex-1 max-w-sm">
              <Search className={`w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 ${text.muted}`} />
              <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search name or visit #…"
                className={`w-full pl-9 pr-3 py-2 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner} />
            </div>
            <span className={`ml-auto inline-flex items-center gap-1.5 text-[11px] ${text.muted}`}>
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-500" /> Scoped to your lab work — not the hospital registry
            </span>
          </div>
        </div>

        {err && (
          <div className="rounded-md border border-red-500/30 bg-red-500/20 px-3 py-2 text-sm text-red-300 flex items-start gap-2">
            <AlertOctagon className="w-4 h-4 mt-0.5" /> <span>{err}</span>
          </div>
        )}

        {loading && rows.length === 0 ? (
          <div className={`text-center py-16 ${text.muted}`}>
            <Loader2 className="w-8 h-8 animate-spin mx-auto mb-3 opacity-50" /><p className="text-sm">Loading lab patients…</p>
          </div>
        ) : filtered.length === 0 ? (
          <div className={`${cardClass} px-5 py-12 text-center ${text.muted}`} style={glassCard}>
            <Users className="w-8 h-8 mx-auto mb-3 opacity-50" />
            <p className="text-sm">{rows.length === 0 ? 'No patients with active lab or imaging work.' : 'No match.'}</p>
          </div>
        ) : (
          <div className={`${cardClass} overflow-hidden`} style={glassCard}>
            <ul>
              {filtered.map((p) => (
                <li key={p.visitId}
                    className={`px-4 py-3 flex items-center gap-3 border-b last:border-0 cursor-pointer ${isDark ? 'border-white/5 hover:bg-white/5' : 'border-slate-100 hover:bg-slate-50'} ${p.criticalUnackCount > 0 ? 'ring-1 ring-rose-500/30' : ''}`}
                    onClick={() => navigate(labChartPath(p.visitId))}>
                  <div className="flex-1 min-w-0">
                    <PatientContextLine patientName={p.patientName} zone={p.currentZone}
                      bedLabel={p.currentBedLabel} visitNumber={p.visitNumber} className={`text-xs mb-1 ${text.heading}`} />
                    <div className="flex items-center gap-2 flex-wrap">
                      {p.criticalUnackCount > 0 && (
                        <span className="inline-flex items-center gap-1 text-[10px] font-bold uppercase px-2 py-0.5 rounded-lg bg-rose-600 text-white animate-pulse">
                          <AlertOctagon className="w-3 h-3" /> {p.criticalUnackCount} critical
                        </span>
                      )}
                      {p.activeLabCount > 0 && (
                        <span className="inline-flex items-center gap-1 text-[10px] font-bold px-2 py-0.5 rounded-lg bg-cyan-500/15 text-cyan-600">
                          <FlaskConical className="w-3 h-3" /> {p.activeLabCount} lab
                        </span>
                      )}
                      {p.activeImagingCount > 0 && (
                        <span className="inline-flex items-center gap-1 text-[10px] font-bold px-2 py-0.5 rounded-lg bg-amber-500/15 text-amber-600">
                          <ScanLine className="w-3 h-3" /> {p.activeImagingCount} imaging
                        </span>
                      )}
                    </div>
                  </div>
                  <ChevronRight className={`w-4 h-4 flex-shrink-0 ${text.muted}`} />
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}

export default LabPatientsView;
