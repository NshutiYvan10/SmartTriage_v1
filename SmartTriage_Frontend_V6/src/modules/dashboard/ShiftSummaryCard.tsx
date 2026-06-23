/* ── ShiftSummaryCard (R10) ──
 *
 * The "how is my shift right now" picture for the charge nurse / shift lead. Assembled CLIENT-SIDE
 * from existing endpoints (no new backend): census by zone (from the live patient store), who's on
 * duty per zone + staffing gaps (current shift assignments), open critical alerts, and pending
 * handovers. Read-only. Shown to whoever has cross-zone oversight (useCanSeeAllZones).
 */
import { useCallback, useEffect, useState } from 'react';
import {
  Users, AlertTriangle, FileSignature, UserCheck, Loader2, RefreshCw, LayoutGrid,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { usePatientStore } from '@/store/patientStore';
import { getZoneForCategory } from '@/hooks/useMyShift';
import { shiftApi } from '@/api/shifts';
import { alertApi } from '@/api/alerts';
import { handoverApi } from '@/api/handover';
import { ApiError } from '@/api/client';
import type { EdZone, ShiftAssignmentResponse } from '@/api/types';

const ZONE_LABELS: Record<string, string> = {
  RESUS: 'Resus', ACUTE: 'Acute', GENERAL: 'General', AMBULATORY: 'Ambulatory',
  TRIAGE: 'Triage', OBSERVATION: 'Obs', ISOLATION: 'Isolation', PEDIATRIC: 'Peds', NEONATAL: 'Neonatal',
};
// Zones we always want to see staffing for (a gap here with patients waiting is a safety signal).
const CORE_ZONES: EdZone[] = ['TRIAGE', 'RESUS', 'ACUTE', 'GENERAL'];

export function ShiftSummaryCard() {
  const { glassCard, isDark, text } = useTheme();
  const hospitalId = useAuthStore((s) => s.user?.hospitalId) || '';
  const patients = usePatientStore((s) => s.patients);

  const [assignments, setAssignments] = useState<ShiftAssignmentResponse[]>([]);
  const [openCriticals, setOpenCriticals] = useState(0);
  const [pendingHandovers, setPendingHandovers] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    setError(null);
    try {
      const [shifts, criticals, handovers] = await Promise.all([
        shiftApi.getCurrentShift(hospitalId),
        alertApi.getCritical(hospitalId, 0, 100),
        handoverApi.getForHospital(hospitalId),
      ]);
      setAssignments((shifts ?? []).filter((a) => a.active));
      setOpenCriticals((criticals?.content ?? []).filter((a) => !a.acknowledgedAt).length);
      setPendingHandovers((handovers ?? []).filter((h) => !h.isAcknowledged).length);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load shift summary');
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { load(); }, [load]);

  // Census by zone (untriaged → TRIAGE), from the live patient store.
  const censusByZone: Record<string, number> = {};
  for (const p of patients) {
    const zone = (getZoneForCategory(p.category) ?? 'TRIAGE') as string;
    censusByZone[zone] = (censusByZone[zone] ?? 0) + 1;
  }
  // Staff grouped by zone (active assignments only).
  const staffByZone: Record<string, ShiftAssignmentResponse[]> = {};
  for (const a of assignments) {
    (staffByZone[a.zone] ??= []).push(a);
  }
  // Zones to display: core zones + any zone that currently has patients or staff.
  const zones = Array.from(new Set<string>([
    ...CORE_ZONES, ...Object.keys(censusByZone), ...Object.keys(staffByZone),
  ]));
  const gapZones = zones.filter((z) => (censusByZone[z] ?? 0) > 0 && (staffByZone[z]?.length ?? 0) === 0);
  const totalPatients = patients.length;
  const onDuty = assignments.length;

  const cell = isDark ? 'bg-white/[0.04] border-white/10' : 'bg-white/60 border-slate-200/60';

  return (
    <div className="rounded-2xl p-5" style={glassCard}>
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: 'rgba(99,102,241,0.12)' }}>
            <LayoutGrid className="w-[18px] h-[18px] text-indigo-500" />
          </div>
          <div>
            <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Shift Summary</h3>
            <p className={`text-xs font-medium mt-0.5 ${text.body}`}>Charge-nurse view — live census, staffing & open work</p>
          </div>
        </div>
        <button onClick={load} className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-white/10 transition-colors" aria-label="Refresh shift summary">
          {loading ? <Loader2 className="w-4 h-4 animate-spin text-slate-400" /> : <RefreshCw className="w-4 h-4 text-slate-400" />}
        </button>
      </div>

      {error ? (
        <div className="rounded-lg p-3 text-xs font-semibold text-red-700 bg-red-50 border border-red-200">{error}</div>
      ) : (
        <>
          {/* KPI strip */}
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
            <Kpi icon={Users} label="In department" value={totalPatients} tone="text-cyan-600" />
            <Kpi icon={UserCheck} label="Staff on duty" value={onDuty} tone="text-emerald-600" />
            <Kpi icon={AlertTriangle} label="Open criticals" value={openCriticals} tone={openCriticals > 0 ? 'text-red-600' : 'text-slate-500'} />
            <Kpi icon={FileSignature} label="Pending handovers" value={pendingHandovers} tone={pendingHandovers > 0 ? 'text-amber-600' : 'text-slate-500'} />
          </div>

          {/* Staffing gaps — the safety signal */}
          {gapZones.length > 0 && (
            <div className="rounded-lg px-3 py-2 mb-4 flex items-center gap-2 bg-red-50 border border-red-200">
              <AlertTriangle className="w-4 h-4 text-red-600 flex-shrink-0" />
              <span className="text-xs font-bold text-red-700">
                Staffing gap: {gapZones.map((z) => `${ZONE_LABELS[z] ?? z} (${censusByZone[z]})`).join(', ')} — patients present, no staff assigned
              </span>
            </div>
          )}

          {/* Census + staffing by zone */}
          <div className="space-y-2">
            {zones.filter((z) => (censusByZone[z] ?? 0) > 0 || (staffByZone[z]?.length ?? 0) > 0).map((z) => {
              const staff = staffByZone[z] ?? [];
              return (
                <div key={z} className={`rounded-lg border p-2.5 ${cell}`}>
                  <div className="flex items-center justify-between">
                    <span className={`text-xs font-bold ${text.heading}`}>{ZONE_LABELS[z] ?? z}</span>
                    <span className="text-[11px] font-semibold text-slate-500">
                      {censusByZone[z] ?? 0} patient{(censusByZone[z] ?? 0) === 1 ? '' : 's'} · {staff.length} staff
                    </span>
                  </div>
                  {staff.length > 0 && (
                    <p className="text-[11px] text-slate-500 mt-1">
                      {staff.map((s) => `${s.userName}${s.isShiftLead ? ' (lead)' : ''} — ${s.shiftFunction}`).join('; ')}
                    </p>
                  )}
                </div>
              );
            })}
            {zones.every((z) => (censusByZone[z] ?? 0) === 0 && (staffByZone[z]?.length ?? 0) === 0) && (
              <p className="text-sm text-slate-400 text-center py-4">No active patients or shift assignments.</p>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function Kpi({ icon: Icon, label, value, tone }: { icon: typeof Users; label: string; value: number; tone: string }) {
  const { isDark } = useTheme();
  return (
    <div className="flex items-center gap-3 p-3 rounded-xl" style={{
      background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.6)',
      border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)',
    }}>
      <Icon className={`w-[18px] h-[18px] flex-shrink-0 ${tone}`} />
      <div className="min-w-0">
        <div className={`text-lg font-extrabold leading-none ${tone}`}>{value}</div>
        <div className="text-[11px] text-slate-400 font-medium mt-0.5">{label}</div>
      </div>
    </div>
  );
}
