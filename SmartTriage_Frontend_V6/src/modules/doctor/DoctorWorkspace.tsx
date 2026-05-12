/* ═══════════════════════════════════════════════════════════════
   Doctor Workspace — Zone-aware patient list with clinical actions
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Stethoscope, Clock, Users, AlertTriangle,
  ChevronRight, RefreshCw, Loader2, Baby,
  FileText, CheckCircle2, Search,
  ArrowUpRight, Eye, BellRing, Crown, BedDouble,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { useBedStore } from '@/store/bedStore';
import { useMyShift } from '@/hooks/useMyShift';
import { visitApi } from '@/api/visits';
import { alertApi } from '@/api/alerts';
import type { VisitResponse, ClinicalAlertResponse, EdZone, VisitStatus, BedResponse } from '@/api/types';
import { formatDistanceToNow } from 'date-fns';
import { PlacePatientDialog } from '@/modules/beds/PlacePatientDialog';
import { HandoffPriorityBadges } from '@/components/HandoffPriorityBadges';

// ─── Category config ───
const CATEGORY_CONFIG: Record<string, { label: string; color: string; bg: string; border: string; dot: string; urgency: string }> = {
  RED:    { label: 'Immediate',   color: 'text-red-500',     bg: 'bg-red-500/10',     border: 'border-red-500/30',    dot: 'bg-red-500',     urgency: '0 min' },
  ORANGE: { label: 'Very Urgent', color: 'text-orange-500',  bg: 'bg-orange-500/10',  border: 'border-orange-500/30', dot: 'bg-orange-500',  urgency: '10 min' },
  YELLOW: { label: 'Urgent',      color: 'text-yellow-500',  bg: 'bg-yellow-500/10',  border: 'border-yellow-500/30', dot: 'bg-yellow-500',  urgency: '30 min' },
  GREEN:  { label: 'Routine',     color: 'text-emerald-500', bg: 'bg-emerald-500/10', border: 'border-emerald-500/30',dot: 'bg-emerald-500', urgency: '60 min' },
  BLUE:   { label: 'DOA',         color: 'text-blue-500',    bg: 'bg-blue-500/10',    border: 'border-blue-500/30',   dot: 'bg-blue-500',    urgency: '—' },
};

const ZONE_LABELS: Record<EdZone, string> = {
  RESUS: 'Resuscitation Zone',
  ACUTE: 'Acute Treatment Zone',
  GENERAL: 'General / Sub-Acute Zone',
  AMBULATORY: 'Ambulatory Zone',
  TRIAGE: 'Triage Station',
  OBSERVATION: 'Observation Unit',
  ISOLATION: 'Isolation Area',
  PEDIATRIC: 'Pediatric Zone',
  NEONATAL: 'Neonatal Unit',
};

const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  REGISTERED:        { label: 'Registered',       color: 'text-slate-400' },
  AWAITING_TRIAGE:   { label: 'Awaiting Triage',  color: 'text-amber-500' },
  TRIAGED:           { label: 'Triaged',           color: 'text-cyan-500' },
  AWAITING_ASSESSMENT: { label: 'Awaiting Assessment', color: 'text-blue-500' },
  UNDER_ASSESSMENT:  { label: 'Under Assessment', color: 'text-violet-500' },
  UNDER_TREATMENT:   { label: 'Under Treatment',  color: 'text-emerald-500' },
  UNDER_OBSERVATION: { label: 'Under Observation', color: 'text-teal-500' },
  PENDING_DISPOSITION: { label: 'Pending Disposition', color: 'text-orange-500' },
  DISCHARGED:        { label: 'Discharged',        color: 'text-slate-500' },
  ADMITTED:          { label: 'Admitted',           color: 'text-indigo-500' },
};

type StatusFilter = 'all' | 'new' | 'assessment' | 'treatment';

export function DoctorWorkspace() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { zone: myZone, assignment: myShift, isShiftLead, isLoading: shiftLoading, refresh: refreshShift } = useMyShift();
  const hospitalId = user?.hospitalId || '';

  const [visits, setVisits] = useState<VisitResponse[]>([]);
  const [alerts, setAlerts] = useState<ClinicalAlertResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [placeVisit, setPlaceVisit] = useState<VisitResponse | null>(null);

  // ── Bed cache ── subscribe to the beds map so cards re-render on changes.
  const loadHospitalBeds = useBedStore((s) => s.loadHospital);
  const bedsMap = useBedStore((s) => s.beds);
  const bedByVisitId = useMemo(() => {
    const idx = new Map<string, BedResponse>();
    bedsMap.forEach((b) => {
      if (b.currentVisitId) idx.set(b.currentVisitId, b);
    });
    return idx;
  }, [bedsMap]);
  useEffect(() => {
    if (hospitalId) loadHospitalBeds(hospitalId).catch(() => {});
  }, [hospitalId, loadHospitalBeds]);

  // ── Data loading ──
  //
  // Phase 1 zone routing:
  //   - Shift lead (charge nurse) → cross-zone visibility, full active list
  //   - User assigned to a specific zone → that zone only (server reads
  //     current_ed_zone, so peds + ambulatory zones are honoured)
  //   - User off-shift → full active list (graceful degradation; without
  //     zone scoping we'd lock them out, which breaks small-hospital
  //     usage where a single doctor covers everything without an
  //     explicit shift assignment)
  const loadData = useCallback(async () => {
    if (!hospitalId || shiftLoading) return;
    setLoading(true);
    try {
      let visitData: VisitResponse[];
      if (myZone && !isShiftLead) {
        visitData = await visitApi.getByZone(hospitalId, myZone);
      } else {
        // RBAC fix — use the caller-aware endpoint instead of the unscoped
        // admin endpoint. The backend returns the right scope based on
        // auth (shift-lead/CN → all zones; off-shift clinician → empty).
        // We no longer rely on a frontend boolean to decide which API to hit.
        const page = await visitApi.getActiveForCallerByHospital(hospitalId, 0, 100);
        visitData = page.content || [];
      }
      setVisits(visitData);

      try {
        if (myZone && !isShiftLead) {
          const alertData = await alertApi.getByZone(hospitalId, myZone);
          setAlerts(alertData);
        } else {
          const alertPage = await alertApi.getUnacknowledged(hospitalId, 0, 50);
          setAlerts(alertPage.content || []);
        }
      } catch { setAlerts([]); }
    } catch (err) {
      console.error('[DoctorWorkspace] Failed to load data:', err);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, myZone, isShiftLead, shiftLoading]);

  useEffect(() => { loadData(); }, [loadData]);

  // Auto-refresh every 30s
  useEffect(() => {
    const interval = setInterval(loadData, 30000);
    return () => clearInterval(interval);
  }, [loadData]);

  // ── Filtering ──
  const filteredVisits = useMemo(() => {
    let list = visits;

    // Status filter
    if (statusFilter === 'new') {
      list = list.filter(v => ['TRIAGED', 'AWAITING_ASSESSMENT'].includes(v.status));
    } else if (statusFilter === 'assessment') {
      list = list.filter(v => ['UNDER_ASSESSMENT'].includes(v.status));
    } else if (statusFilter === 'treatment') {
      list = list.filter(v => ['UNDER_TREATMENT', 'UNDER_OBSERVATION', 'PENDING_DISPOSITION'].includes(v.status));
    }

    // Search filter
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      list = list.filter(v =>
        v.patientName?.toLowerCase().includes(q) ||
        v.visitNumber?.toLowerCase().includes(q) ||
        v.chiefComplaint?.toLowerCase().includes(q)
      );
    }

    // Sort: newer triage categories first (RED > ORANGE > YELLOW > GREEN > BLUE), then by arrival time
    const categoryOrder: Record<string, number> = { RED: 0, ORANGE: 1, YELLOW: 2, GREEN: 3, BLUE: 4 };
    return list.sort((a, b) => {
      const catA = categoryOrder[a.currentTriageCategory || 'GREEN'] ?? 5;
      const catB = categoryOrder[b.currentTriageCategory || 'GREEN'] ?? 5;
      if (catA !== catB) return catA - catB;
      return new Date(a.arrivalTime).getTime() - new Date(b.arrivalTime).getTime();
    });
  }, [visits, statusFilter, searchQuery]);

  // ── Stats ──
  const stats = useMemo(() => ({
    total: visits.length,
    newPatients: visits.filter(v => ['TRIAGED', 'AWAITING_ASSESSMENT'].includes(v.status)).length,
    underCare: visits.filter(v => ['UNDER_ASSESSMENT', 'UNDER_TREATMENT', 'UNDER_OBSERVATION'].includes(v.status)).length,
    pendingDisposition: visits.filter(v => v.status === 'PENDING_DISPOSITION').length,
    critical: visits.filter(v => v.currentTriageCategory === 'RED').length,
    pediatric: visits.filter(v => v.isPediatric).length,
    unackAlerts: alerts.filter(a => !a.acknowledged).length,
  }), [visits, alerts]);

  // ── Accept patient (transition to UNDER_ASSESSMENT) ──
  const handleAcceptPatient = async (visit: VisitResponse) => {
    try {
      await visitApi.updateStatus(visit.id, 'UNDER_ASSESSMENT' as VisitStatus);
      navigate(`/visit/${visit.id}`);
    } catch (err) { console.error(err); }
  };

  // ── Acknowledge alert & navigate ──
  const handleAlertAction = async (alert: ClinicalAlertResponse) => {
    try {
      await alertApi.acknowledge(alert.id);
      if (alert.visitId) navigate(`/visit/${alert.visitId}`);
      else loadData();
    } catch (err) { console.error(err); }
  };

  // ── Loading state ──
  if (shiftLoading || loading) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="w-8 h-8 animate-spin text-cyan-500" />
          <p className={`text-sm font-medium ${text.muted}`}>Loading workspace…</p>
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6 max-w-7xl mx-auto">

      {/* ── Header ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className={`text-2xl font-black tracking-tight ${text.heading}`}>
            Doctor Workspace
          </h1>
          <div className="flex items-center gap-3 mt-1">
            {myZone ? (
              <span className={`inline-flex items-center gap-1.5 text-sm font-bold ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`}>
                <span className="w-2 h-2 rounded-full bg-cyan-500 animate-pulse" />
                {ZONE_LABELS[myZone] || myZone}
              </span>
            ) : (
              <span className={`text-sm ${text.muted}`}>No zone assigned — showing all patients</span>
            )}
            {myShift && (
              <span className={`text-xs px-2 py-0.5 rounded-lg ${isDark ? 'bg-slate-700/50 text-slate-400' : 'bg-slate-100 text-slate-500'}`}>
                {myShift.shiftFunction?.replace(/_/g, ' ')}
              </span>
            )}
            {myShift?.isShiftLead && (
              <span
                title="You hold the shift-lead badge"
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-lg bg-gradient-to-r from-amber-500 to-yellow-600 text-white text-[10px] font-bold uppercase tracking-wider shadow-sm shadow-amber-500/30"
              >
                <Crown className="w-3 h-3" />
                Shift Lead
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => { loadData(); refreshShift(); }}
            className={`p-2.5 rounded-xl transition-all ${isDark ? 'hover:bg-white/5 text-slate-400' : 'hover:bg-slate-100 text-slate-500'}`}
          >
            <RefreshCw className="w-4.5 h-4.5" />
          </button>
        </div>
      </div>

      {/* ── Quick Stats ── */}
      <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-3">
        {[
          { label: 'In Zone', value: stats.total, icon: Users, color: 'text-cyan-500' },
          { label: 'New', value: stats.newPatients, icon: ArrowUpRight, color: 'text-blue-500' },
          { label: 'Under Care', value: stats.underCare, icon: Stethoscope, color: 'text-violet-500' },
          { label: 'Disposition', value: stats.pendingDisposition, icon: CheckCircle2, color: 'text-orange-500' },
          { label: 'Critical', value: stats.critical, icon: AlertTriangle, color: 'text-red-500' },
          { label: 'Pediatric', value: stats.pediatric, icon: Baby, color: 'text-pink-500' },
          { label: 'Alerts', value: stats.unackAlerts, icon: BellRing, color: 'text-rose-500' },
        ].map(({ label, value, icon: Icon, color }) => (
          <div key={label} className="rounded-2xl p-3 text-center" style={glassCard}>
            <Icon className={`w-4 h-4 mx-auto mb-1 ${color}`} />
            <p className={`text-lg font-black ${color}`}>{value}</p>
            <p className={`text-[9px] font-bold uppercase tracking-wider ${text.muted}`}>{label}</p>
          </div>
        ))}
      </div>

      {/* ── Unacknowledged Alerts Banner ── */}
      {stats.unackAlerts > 0 && (
        <div className="rounded-2xl p-4 border border-rose-500/30 bg-rose-500/5" style={glassCard}>
          <div className="flex items-center gap-2 mb-3">
            <BellRing className="w-5 h-5 text-rose-500" />
            <h3 className={`font-bold text-sm ${isDark ? 'text-rose-400' : 'text-rose-600'}`}>
              {stats.unackAlerts} Unacknowledged Alert{stats.unackAlerts > 1 ? 's' : ''}
            </h3>
          </div>
          <div className="space-y-2">
            {alerts.filter(a => !a.acknowledged).slice(0, 5).map(alert => (
              <div key={alert.id} className="flex items-center justify-between gap-3 rounded-xl p-2.5" style={glassInner}>
                <div className="flex-1 min-w-0">
                  <p className={`text-xs font-bold truncate ${text.heading}`}>{alert.title || alert.message}</p>
                  <p className={`text-[10px] ${text.muted}`}>
                    {alert.patientName} • {alert.alertType?.replace(/_/g, ' ')}
                  </p>
                </div>
                <button
                  onClick={() => handleAlertAction(alert)}
                  className="shrink-0 inline-flex items-center gap-1 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors"
                >
                  <Eye className="w-3 h-3" /> Review
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Search + Filters ── */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="relative flex-1 min-w-[200px] max-w-md">
          <Search className={`absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 ${text.muted}`} />
          <input
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="Search by name, visit #, complaint…"
            className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
        </div>
        <div className="flex gap-1.5">
          {([
            { key: 'all', label: 'All' },
            { key: 'new', label: 'New / Triaged' },
            { key: 'assessment', label: 'Under Assessment' },
            { key: 'treatment', label: 'Treatment / Obs' },
          ] as { key: StatusFilter; label: string }[]).map(f => (
            <button
              key={f.key}
              onClick={() => setStatusFilter(f.key)}
              className={`px-3 py-2 text-[11px] font-bold rounded-xl transition-all ${
                statusFilter === f.key
                  ? isDark ? 'bg-cyan-500/20 text-cyan-400 border border-cyan-500/30' : 'bg-blue-50 text-blue-600 border border-blue-200'
                  : isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {/* ── Patient List ── */}
      {filteredVisits.length === 0 ? (
        <div className="rounded-2xl p-12 text-center" style={glassCard}>
          <Users className={`w-12 h-12 mx-auto mb-3 ${text.muted}`} />
          <p className={`text-sm font-bold ${text.heading}`}>No patients found</p>
          <p className={`text-xs mt-1 ${text.muted}`}>
            {myZone ? `No active patients in ${ZONE_LABELS[myZone] || myZone}` : 'No active visits available'}
          </p>
        </div>
      ) : (
        <div className="space-y-2">
          {filteredVisits.map((visit, i) => (
            <PatientCard
              key={visit.id}
              visit={visit}
              index={i}
              isDark={isDark}
              text={text}
              glassCard={glassCard}
              bed={bedByVisitId.get(visit.id)}
              onAccept={handleAcceptPatient}
              onPlaceInBed={() => setPlaceVisit(visit)}
              onView={() => navigate(`/visit/${visit.id}`)}
            />
          ))}
        </div>
      )}

      {/* Place-in-bed modal */}
      {placeVisit && (
        <PlacePatientDialog
          open={!!placeVisit}
          mode={{ kind: 'patient-first', visit: placeVisit }}
          onClose={() => setPlaceVisit(null)}
          onPlaced={() => {
            setPlaceVisit(null);
            loadData();
          }}
        />
      )}
    </div>
  );
}

/* ─────────────────────────────────────────────────────────────
   Patient Card — Individual patient row in the doctor's list
   ───────────────────────────────────────────────────────────── */
function PatientCard({ visit, index, isDark, text, glassCard, bed, onAccept, onPlaceInBed, onView }: {
  visit: VisitResponse;
  index: number;
  isDark: boolean;
  text: any;
  glassCard: React.CSSProperties;
  bed?: BedResponse;
  onAccept: (visit: VisitResponse) => void;
  onPlaceInBed: () => void;
  onView: () => void;
}) {
  const cat = CATEGORY_CONFIG[visit.currentTriageCategory || ''] || CATEGORY_CONFIG.GREEN;
  const statusInfo = STATUS_LABELS[visit.status] || { label: visit.status, color: 'text-slate-400' };
  const waitTime = visit.arrivalTime ? formatDistanceToNow(new Date(visit.arrivalTime), { addSuffix: false }) : '—';
  const isNewPatient = ['TRIAGED', 'AWAITING_ASSESSMENT'].includes(visit.status);
  const isUnderCare = ['UNDER_ASSESSMENT', 'UNDER_TREATMENT', 'UNDER_OBSERVATION'].includes(visit.status);

  return (
    <div
      className={`rounded-2xl p-4 border transition-all animate-fade-up cursor-pointer hover:scale-[1.005] ${
        isNewPatient ? (isDark ? 'border-cyan-500/30' : 'border-blue-300/50') : 'border-transparent'
      }`}
      style={{ ...glassCard, animationDelay: `${index * 0.02}s` }}
      onClick={onView}
    >
      <div className="flex items-center gap-4">
        {/* Triage Category Indicator */}
        <div className={`w-12 h-12 rounded-2xl ${cat.bg} border ${cat.border} flex flex-col items-center justify-center shrink-0`}>
          <div className={`w-3 h-3 rounded-full ${cat.dot} ${visit.currentTriageCategory === 'RED' ? 'animate-pulse' : ''}`} />
          <span className={`text-[8px] font-black uppercase mt-0.5 ${cat.color}`}>
            {visit.currentTriageCategory || '—'}
          </span>
        </div>

        {/* Patient Info */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-0.5">
            <h3 className={`text-sm font-bold truncate ${text.heading}`}>
              {visit.patientName || 'Unknown Patient'}
            </h3>
            {visit.isPediatric && (
              <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 text-[9px] font-bold rounded-md bg-pink-500/10 text-pink-500 border border-pink-500/20">
                <Baby className="w-2.5 h-2.5" /> PED
              </span>
            )}
            {visit.retriageCount > 0 && (
              <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 text-[9px] font-bold rounded-md bg-amber-500/10 text-amber-500 border border-amber-500/20">
                RE-TRIAGE ×{visit.retriageCount}
              </span>
            )}
          </div>
          <p className={`text-xs truncate ${text.body}`}>
            {visit.chiefComplaint || 'No complaint recorded'}
          </p>
          {/* Shift-handoff priority badges — pending labs, pending meds,
              critical results back, open ICU escalation. Renders nothing
              when this patient has no outstanding work, so cards stay
              clean and the present badges genuinely demand attention. */}
          <div className="mt-1.5">
            <HandoffPriorityBadges signals={visit} />
          </div>
          <div className="flex items-center gap-3 mt-1 flex-wrap">
            <span className={`text-[10px] font-bold ${statusInfo.color}`}>{statusInfo.label}</span>
            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
              <Clock className="w-3 h-3" /> {waitTime}
            </span>
            <span className={`text-[10px] ${text.muted}`}>{visit.visitNumber}</span>
            {visit.currentTewsScore != null && (
              <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                visit.currentTewsScore >= 7 ? 'bg-red-500/10 text-red-500' :
                visit.currentTewsScore >= 5 ? 'bg-orange-500/10 text-orange-500' :
                'bg-slate-500/10 text-slate-500'
              }`}>
                TEWS: {visit.currentTewsScore}
              </span>
            )}
            {bed && (
              <span
                className="inline-flex items-center gap-1 px-1.5 py-0.5 text-[10px] font-bold rounded-md bg-emerald-500/10 text-emerald-600 border border-emerald-500/20"
                title={`${bed.code} · ${bed.zone}${bed.assignedDeviceName ? ` · monitor: ${bed.assignedDeviceName}` : ''}`}
              >
                <BedDouble className="w-2.5 h-2.5" />
                {bed.code}
              </span>
            )}
          </div>
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2 shrink-0" onClick={e => e.stopPropagation()}>
          {!bed && (isNewPatient || isUnderCare) && (
            <button
              onClick={onPlaceInBed}
              title="Assign this patient to a bed"
              className="inline-flex items-center gap-1.5 px-3 py-2.5 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20 border border-emerald-500/20 transition-all"
            >
              <BedDouble className="w-3.5 h-3.5" /> Place in bed
            </button>
          )}
          {isNewPatient && (
            <button
              onClick={() => onAccept(visit)}
              className="inline-flex items-center gap-1.5 px-4 py-2.5 text-[11px] font-bold rounded-xl text-white bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-600 hover:to-blue-700 shadow-lg shadow-cyan-500/20 transition-all"
            >
              <Stethoscope className="w-3.5 h-3.5" /> Accept Patient
            </button>
          )}
          {isUnderCare && (
            <button
              onClick={onView}
              className="inline-flex items-center gap-1.5 px-4 py-2.5 text-[11px] font-bold rounded-xl bg-violet-500/10 text-violet-500 hover:bg-violet-500/20 border border-violet-500/20 transition-all"
            >
              <FileText className="w-3.5 h-3.5" /> Continue
            </button>
          )}
          {!isNewPatient && !isUnderCare && (
            <button
              onClick={onView}
              className={`inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-xl transition-all ${
                isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
              }`}
            >
              <Eye className="w-3.5 h-3.5" /> View
            </button>
          )}
          <ChevronRight className={`w-4 h-4 ${text.muted}`} />
        </div>
      </div>
    </div>
  );
}
