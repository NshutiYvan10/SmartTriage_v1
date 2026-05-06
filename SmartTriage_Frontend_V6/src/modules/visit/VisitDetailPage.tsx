/* ═══════════════════════════════════════════════════════════════
   Visit Detail Page — Full Clinical Workspace
   Tabs: Overview, Vitals, Triage, Notes, Diagnoses,
         Investigations, Medications, Monitor, Alerts
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft, Activity, FileText, Stethoscope, ClipboardList,
  FlaskConical, Pill, Monitor, BellRing, Heart, Thermometer,
  Wind, Droplets, Brain, Clock, User, AlertTriangle, ChevronRight,
  Plus, Send, CheckCircle2, XCircle, Eye, Loader2, RefreshCw, LogOut,
  Wifi, WifiOff, BatteryWarning, MonitorSmartphone, TrendingUp,
} from 'lucide-react';
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, Tooltip } from 'recharts';
import { useDeviceStore } from '@/store/deviceStore';
import { iotApi } from '@/api/iot';
import { PrescribePanel, type PrescribeSafetyContext } from './PrescribePanel';
import { DiagnosisPanel } from './DiagnosisPanel';
import { InvestigationPanel } from './InvestigationPanel';
import { ClinicalSignsTab } from './ClinicalSignsTab';
import { medsafetyApi, type MedicationSafetyCheck } from '@/api/medsafety';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { visitApi } from '@/api/visits';
import type { DispositionRequest } from '@/api/visits';
import { vitalApi } from '@/api/vitals';
import { triageApi } from '@/api/triage';
import { clinicalNoteApi } from '@/api/clinicalNotes';
import { diagnosisApi } from '@/api/diagnoses';
import { investigationApi } from '@/api/investigations';
import { medicationApi } from '@/api/medications';
import { alertApi } from '@/api/alerts';
import { patientApi } from '@/api/patients';
import { Pencil, Save } from 'lucide-react';
import type {
  VisitResponse, VitalSignsResponse, TriageRecordResponse,
  ClinicalNoteResponse, DiagnosisResponse, InvestigationResponse,
  MedicationResponse, ClinicalAlertResponse,
  RecordVitalsRequest, CreateClinicalNoteRequest, CreateDiagnosisRequest,
  OrderInvestigationRequest, PrescribeMedicationRequest,
  NoteType, DiagnosisType, InvestigationType, MedicationRoute,
  AvpuScore, TriageCategory, DispositionType,
  PatientResponse, PregnancyStatus,
} from '@/api/types';
import { format } from 'date-fns';

// ── Category color config ──
const CATEGORY_COLORS: Record<string, { bg: string; text: string; border: string; dot: string }> = {
  RED:    { bg: 'bg-red-500/10', text: 'text-red-500', border: 'border-red-500/30', dot: 'bg-red-500' },
  ORANGE: { bg: 'bg-orange-500/10', text: 'text-orange-500', border: 'border-orange-500/30', dot: 'bg-orange-500' },
  YELLOW: { bg: 'bg-yellow-500/10', text: 'text-yellow-500', border: 'border-yellow-500/30', dot: 'bg-yellow-500' },
  GREEN:  { bg: 'bg-emerald-500/10', text: 'text-emerald-500', border: 'border-emerald-500/30', dot: 'bg-emerald-500' },
  BLUE:   { bg: 'bg-blue-500/10', text: 'text-blue-500', border: 'border-blue-500/30', dot: 'bg-blue-500' },
};

// ── Tab config ──
const TABS = [
  { id: 'overview', label: 'Overview', icon: Eye },
  { id: 'vitals', label: 'Vitals', icon: Activity },
  { id: 'triage', label: 'Triage', icon: Stethoscope },
  // Clinical Signs sits adjacent to Triage because it tracks the
  // evolution of the very signs that triage captured at entry —
  // emergency signs and mSAT discriminators over time.
  { id: 'clinical-signs', label: 'Clinical Signs', icon: TrendingUp },
  { id: 'notes', label: 'Notes', icon: FileText },
  { id: 'diagnoses', label: 'Diagnoses', icon: ClipboardList },
  { id: 'investigations', label: 'Investigations', icon: FlaskConical },
  { id: 'medications', label: 'Medications', icon: Pill },
  { id: 'monitor', label: 'Monitor', icon: Monitor },
  { id: 'alerts', label: 'Alerts', icon: BellRing },
  { id: 'disposition', label: 'Disposition', icon: LogOut },
] as const;

type TabId = typeof TABS[number]['id'];

// ── Note type labels ──
const NOTE_TYPE_LABELS: Record<NoteType, string> = {
  PHYSICAL_FINDINGS: 'Physical Findings',
  PROGRESS_NOTE: 'Progress Note',
  NURSING_NOTE: 'Nursing Note',
  DOCTOR_NOTE: 'Doctor Note',
  TRIAGE_NOTE: 'Triage Note',
  HISTORY_OF_PRESENTING_COMPLAINT: 'HPC',
  PAST_MEDICAL_HISTORY: 'Past Medical Hx',
  SOCIAL_HISTORY: 'Social History',
  FAMILY_HISTORY: 'Family History',
  REVIEW_OF_SYSTEMS: 'Review of Systems',
  ALLERGIES: 'Allergies',
  CURRENT_MEDICATIONS: 'Current Meds',
  TREATMENT_PLAN: 'Treatment Plan',
  DISCHARGE_SUMMARY: 'Discharge Summary',
  HANDOVER: 'Handover',
  OTHER: 'Other',
};

const INVESTIGATION_STATUS_COLORS: Record<string, string> = {
  ORDERED: 'text-blue-500 bg-blue-500/10',
  SPECIMEN_COLLECTED: 'text-amber-500 bg-amber-500/10',
  IN_PROGRESS: 'text-cyan-500 bg-cyan-500/10',
  RESULTED: 'text-emerald-500 bg-emerald-500/10',
  CANCELLED: 'text-red-500 bg-red-500/10',
};

const MEDICATION_STATUS_COLORS: Record<string, string> = {
  PRESCRIBED: 'text-blue-500 bg-blue-500/10',
  ADMINISTERED: 'text-emerald-500 bg-emerald-500/10',
  HELD: 'text-amber-500 bg-amber-500/10',
  REFUSED: 'text-red-500 bg-red-500/10',
  CANCELLED: 'text-slate-500 bg-slate-500/10',
};

export function VisitDetailPage() {
  const { visitId } = useParams<{ visitId: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);

  // Initial tab is taken from ?tab= so deep-links like
  //   /visit/:id?tab=monitor   (from ConstantMonitoring or /vitals redirect)
  //   /visit/:id?tab=vitals    (from any "show me vitals" surface)
  // land on the right tab without a second click. Falls back to overview.
  const initialTab: TabId = (() => {
    const fromUrl = searchParams.get('tab');
    if (fromUrl && (TABS as readonly { id: string }[]).some((t) => t.id === fromUrl)) {
      return fromUrl as TabId;
    }
    return 'overview';
  })();
  const [activeTab, setActiveTabState] = useState<TabId>(initialTab);

  // Keep the URL ?tab= in sync when the user clicks a tab — preserves
  // back/forward navigation and shareable deep links.
  const setActiveTab = useCallback((tab: TabId) => {
    setActiveTabState(tab);
    const next = new URLSearchParams(searchParams);
    if (tab === 'overview') next.delete('tab'); else next.set('tab', tab);
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);

  const [loading, setLoading] = useState(true);

  // Data
  const [visit, setVisit] = useState<VisitResponse | null>(null);
  // Patient profile — fetched separately because VisitResponse only carries
  // patientId/patientName. Without this, the doctor's chart could not see
  // allergies, weight, blood type, chronic conditions, or pregnancy status.
  // That was a clinical-safety silent failure: a doctor prescribing a
  // medication had no allergy banner, no weight for pediatric dosing, no
  // pregnancy flag for teratogen risk.
  const [patient, setPatient] = useState<PatientResponse | null>(null);
  const [vitals, setVitals] = useState<VitalSignsResponse[]>([]);
  const [latestVitals, setLatestVitals] = useState<VitalSignsResponse | null>(null);
  const [triageHistory, setTriageHistory] = useState<TriageRecordResponse[]>([]);
  const [latestTriage, setLatestTriage] = useState<TriageRecordResponse | null>(null);
  const [notes, setNotes] = useState<ClinicalNoteResponse[]>([]);
  const [diagnoses, setDiagnoses] = useState<DiagnosisResponse[]>([]);
  const [investigations, setInvestigations] = useState<InvestigationResponse[]>([]);
  const [medications, setMedications] = useState<MedicationResponse[]>([]);
  const [visitAlerts, setVisitAlerts] = useState<ClinicalAlertResponse[]>([]);

  // Forms
  const [showVitalsForm, setShowVitalsForm] = useState(false);
  const [showNoteForm, setShowNoteForm] = useState(false);
  const [showDiagnosisForm, setShowDiagnosisForm] = useState(false);
  const [showInvestigationForm, setShowInvestigationForm] = useState(false);
  const [showMedicationForm, setShowMedicationForm] = useState(false);
  const [formLoading, setFormLoading] = useState(false);
  // The most recent safety-engine validation result. Surfaced as a banner
  // on the medications tab so the doctor sees server-side warnings (dose
  // exceeded, drug-drug interaction, duplicate therapy) that may not have
  // been caught by the client-side allergy / pregnancy precheck. Cleared
  // on dismiss or when the form re-opens.
  const [lastSafetyCheck, setLastSafetyCheck] = useState<MedicationSafetyCheck | null>(null);

  const userName = user?.fullName || 'Unknown';

  const loadData = useCallback(async () => {
    if (!visitId) return;
    setLoading(true);
    try {
      const [v, vit, lt, th, triLatest, n, d, inv, med, al] = await Promise.allSettled([
        visitApi.getById(visitId),
        vitalApi.getByVisit(visitId, 0, 50),
        vitalApi.getLatest(visitId),
        triageApi.getHistory(visitId),
        triageApi.getLatest(visitId),
        clinicalNoteApi.getAllByVisit(visitId),
        diagnosisApi.getAllByVisit(visitId),
        investigationApi.getAllByVisit(visitId),
        medicationApi.getAllByVisit(visitId),
        alertApi.getByVisit(visitId, 0, 50),
      ]);

      if (v.status === 'fulfilled') setVisit(v.value);
      if (vit.status === 'fulfilled') setVitals(vit.value.content);
      if (lt.status === 'fulfilled') setLatestVitals(lt.value);
      if (th.status === 'fulfilled') setTriageHistory(th.value.content);
      if (triLatest.status === 'fulfilled') setLatestTriage(triLatest.value);
      if (n.status === 'fulfilled') setNotes(Array.isArray(n.value) ? n.value : []);
      if (d.status === 'fulfilled') setDiagnoses(Array.isArray(d.value) ? d.value : []);
      if (inv.status === 'fulfilled') setInvestigations(Array.isArray(inv.value) ? inv.value : []);
      if (med.status === 'fulfilled') setMedications(Array.isArray(med.value) ? med.value : []);
      if (al.status === 'fulfilled') setVisitAlerts(al.value.content);

      // Patient profile fetched after the visit so we have a patientId.
      // Failure here is logged but doesn't block the rest of the chart —
      // the rest of the tabs render with `patient = null` and degrade
      // gracefully (e.g. allergy banner shows "Patient profile unavailable —
      // verify allergies before prescribing"). We never silently fall back
      // to "no allergies on record" — that would be the same class of
      // safety bug we just fixed.
      if (v.status === 'fulfilled' && v.value?.patientId) {
        try {
          const p = await patientApi.getById(v.value.patientId);
          setPatient(p);
        } catch (err) {
          console.error('Failed to load patient profile:', err);
          setPatient(null);
        }
      }
    } catch (err) {
      console.error('Failed to load visit data:', err);
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { loadData(); }, [loadData]);

  const category = visit?.currentTriageCategory || latestTriage?.triageCategory;
  const catColor = CATEGORY_COLORS[category || 'GREEN'] || CATEGORY_COLORS.GREEN;

  if (loading) {
    return (
      <div className="min-h-full flex items-center justify-center">
        <Loader2 className="w-8 h-8 animate-spin text-cyan-500" />
      </div>
    );
  }

  if (!visit) {
    return (
      <div className="min-h-full flex items-center justify-center">
        <p className={text.body}>Visit not found</p>
      </div>
    );
  }

  // ────────── FORM HANDLERS ──────────

  const handleRecordVitals = async (data: Partial<RecordVitalsRequest>) => {
    setFormLoading(true);
    try {
      await vitalApi.record({ visitId: visit.id, ...data } as RecordVitalsRequest);
      setShowVitalsForm(false);
      loadData();
    } catch (err) { console.error(err); } finally { setFormLoading(false); }
  };

  const handleCreateNote = async (data: Partial<CreateClinicalNoteRequest>) => {
    setFormLoading(true);
    try {
      await clinicalNoteApi.create({ visitId: visit.id, recordedByName: userName, ...data } as CreateClinicalNoteRequest);
      setShowNoteForm(false);
      loadData();
    } catch (err) { console.error(err); } finally { setFormLoading(false); }
  };

  const handleCreateDiagnosis = async (data: Partial<CreateDiagnosisRequest>) => {
    setFormLoading(true);
    try {
      await diagnosisApi.create({ visitId: visit.id, diagnosedByName: userName, ...data } as CreateDiagnosisRequest);
      setShowDiagnosisForm(false);
      loadData();
    } catch (err) { console.error(err); } finally { setFormLoading(false); }
  };

  const handleOrderInvestigation = async (data: Partial<OrderInvestigationRequest>) => {
    setFormLoading(true);
    try {
      await investigationApi.order({ visitId: visit.id, orderedByName: userName, ...data } as OrderInvestigationRequest);
      setShowInvestigationForm(false);
      loadData();
    } catch (err) { console.error(err); } finally { setFormLoading(false); }
  };

  /**
   * Prescribing safety chain:
   *   1. POST /medications        — creates the medication record
   *   2. POST /med-safety/validate — runs allergy / dose / interaction /
   *                                  duplicate-therapy checks against the
   *                                  drug formulary; persists a
   *                                  MedicationSafetyCheck row keyed to
   *                                  the medication id, regardless of pass
   *                                  or fail. This is the audit trail.
   *   3. PUT  /med-safety/{id}/override — only when the validation failed
   *                                  AND the doctor pre-supplied an
   *                                  override reason in the prescribe panel.
   *
   * The medication is prescribed first because the backend safety endpoint
   * keys on medication_id; we cannot validate a hypothetical prescription.
   * That means a *failed* check arrives after the order is already in the
   * record. The check banner exposes the failure to the doctor so they can
   * cancel / hold the medication if appropriate.
   *
   * Errors at any step are swallowed only at the prescribe step (it is the
   * doctor's primary action). Validation / override failures are logged
   * but not surfaced as blocking errors — the prescription itself succeeded
   * and the audit trail will show the gap, which we want over silently
   * pretending the safety chain completed.
   */
  const handlePrescribeMedication = async (
    data: Partial<PrescribeMedicationRequest>,
    safety?: PrescribeSafetyContext,
  ) => {
    if (!visit) return;
    setFormLoading(true);
    setLastSafetyCheck(null);
    try {
      // 1. Prescribe.
      const prescribed = await medicationApi.prescribe({
        visitId: visit.id,
        prescribedByName: userName,
        ...data,
      } as PrescribeMedicationRequest);

      setShowMedicationForm(false);

      // 2. Validate. Best-effort — failures here do NOT block; they leave
      //    the audit trail thinner but the prescription itself stands.
      let check: MedicationSafetyCheck | null = null;
      try {
        check = await medsafetyApi.validate({
          visitId: visit.id,
          medicationId: prescribed.id,
          weightKg: safety?.weightKg ?? null,
          doseMg: safety?.doseMg ?? null,
        });
      } catch (validateErr) {
        console.error('[medsafety] validate failed for medication', prescribed.id, validateErr);
      }

      // 3. Override — only when validation actually flagged something AND
      //    the doctor recorded a reason in the panel. We do NOT auto-override
      //    "warnings the doctor never saw" — those should display so the
      //    doctor can decide whether to act on them.
      if (check && !check.overallSafe && safety?.overrideReason) {
        try {
          await medsafetyApi.override(check.id, safety.overrideReason, userName);
          // Re-fetch the check to capture the override fields for display.
          check = { ...check, overrideReason: safety.overrideReason, overriddenBy: userName, overriddenAt: new Date().toISOString() };
        } catch (overrideErr) {
          console.error('[medsafety] override failed for check', check.id, overrideErr);
        }
      }

      setLastSafetyCheck(check);
      loadData();
    } catch (err) {
      console.error('Prescribe failed:', err);
    } finally {
      setFormLoading(false);
    }
  };

  const handleAcknowledgeAlert = async (alertId: string) => {
    try {
      await alertApi.acknowledge(alertId);
      loadData();
    } catch (err) { console.error(err); }
  };

  const handleInvestigationAction = async (id: string, action: string, data?: unknown) => {
    try {
      switch (action) {
        case 'specimen': await investigationApi.specimenCollected(id); break;
        case 'progress': await investigationApi.markInProgress(id); break;
        case 'result': await investigationApi.recordResult(id, data as Parameters<typeof investigationApi.recordResult>[1]); break;
      }
      loadData();
    } catch (err) { console.error(err); }
  };

  const handleMedicationAction = async (id: string, action: string) => {
    try {
      switch (action) {
        case 'administer':
          await medicationApi.administer(id, { medicationId: id, administeredByName: userName });
          break;
        case 'countersign':
          await medicationApi.countersign(id, { medicationId: id, countersignedByName: userName });
          break;
      }
      loadData();
    } catch (err) { console.error(err); }
  };

  const handleRecordDisposition = async (data: DispositionRequest) => {
    if (!visit) return;
    setFormLoading(true);
    try {
      await visitApi.recordDisposition(visit.id, data);
      loadData();
    } catch (err) { console.error(err); } finally { setFormLoading(false); }
  };

  // ────────── RENDER ──────────

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center gap-4">
              <button onClick={() => navigate(-1)} className="w-10 h-10 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                <ArrowLeft className="w-5 h-5 text-white" />
              </button>
              <div className="flex-1">
                <div className="flex items-center gap-3">
                  <h1 className="text-lg font-bold text-white tracking-wide">{visit.patientName}</h1>
                  {category && (
                    <span className={`px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider rounded-lg ${catColor.bg} ${catColor.text} border ${catColor.border}`}>
                      {category}
                    </span>
                  )}
                  <span className="px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider rounded-lg bg-white/10 text-white/70">
                    {visit.status.replace(/_/g, ' ')}
                  </span>
                </div>
                <div className="flex items-center gap-4 mt-1">
                  <p className="text-white/70 text-xs font-medium">Visit: {visit.visitNumber}</p>
                  {visit.chiefComplaint && (
                    <p className="text-white/50 text-xs">CC: {visit.chiefComplaint}</p>
                  )}
                  {visit.currentTewsScore !== null && (
                    <p className="text-cyan-400 text-xs font-bold">TEWS: {visit.currentTewsScore}</p>
                  )}
                </div>
              </div>
              <button onClick={loadData} className="w-10 h-10 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                <RefreshCw className="w-4 h-4 text-white" />
              </button>
            </div>
          </div>

          {/* ── Patient Safety Banner ──
              Always visible regardless of active tab. The doctor must NEVER
              have to click into a tab to see allergies, weight, or pregnancy
              status — these drive prescribing safety on every screen.
              Renders in a degraded "verify before prescribing" state if the
              patient profile failed to load, never silently as "no allergies". */}
          <PatientSafetyBanner patient={patient} latestTriage={latestTriage} isDark={isDark} />

          {/* ── Tabs ── */}
          <div className="flex overflow-x-auto gap-1 px-4 py-2" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}>
            {TABS.map((tab) => {
              const Icon = tab.icon;
              const isActive = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold rounded-lg transition-all whitespace-nowrap ${
                    isActive
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                      : isDark
                        ? 'text-slate-400 hover:text-white hover:bg-white/5'
                        : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                  }`}
                >
                  <Icon className="w-3.5 h-3.5" />
                  {tab.label}
                  {tab.id === 'alerts' && visitAlerts.filter(a => !a.acknowledged).length > 0 && (
                    <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* ── Tab Content ── */}
        <div className="animate-fade-up" style={{ animationDelay: '0.05s' }}>
          {activeTab === 'overview' && <OverviewTab visit={visit} patient={patient} onPatientUpdate={setPatient} latestVitals={latestVitals} latestTriage={latestTriage} notes={notes} diagnoses={diagnoses} investigations={investigations} medications={medications} alerts={visitAlerts} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'vitals' && <VitalsTab vitals={vitals} latestVitals={latestVitals} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'triage' && <TriageTab visit={visit} triageHistory={triageHistory} latestTriage={latestTriage} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} navigate={navigate} />}
          {activeTab === 'clinical-signs' && <ClinicalSignsTab visitId={visit.id} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'notes' && <NotesTab notes={notes} showForm={showNoteForm} setShowForm={setShowNoteForm} onSubmit={handleCreateNote} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'diagnoses' && <DiagnosesTab diagnoses={diagnoses} showForm={showDiagnosisForm} setShowForm={setShowDiagnosisForm} onSubmit={handleCreateDiagnosis} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'investigations' && <InvestigationsTab investigations={investigations} showForm={showInvestigationForm} setShowForm={setShowInvestigationForm} onSubmit={handleOrderInvestigation} onAction={handleInvestigationAction} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} userName={userName} />}
          {activeTab === 'medications' && <MedicationsTab medications={medications} patient={patient} latestTriage={latestTriage} showForm={showMedicationForm} setShowForm={setShowMedicationForm} onSubmit={handlePrescribeMedication} onAction={handleMedicationAction} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} visitId={visit.id} lastSafetyCheck={lastSafetyCheck} onDismissSafetyCheck={() => setLastSafetyCheck(null)} />}
          {activeTab === 'monitor' && <MonitorTab visit={visit} vitals={vitals} latestVitals={latestVitals} glassCard={glassCard} isDark={isDark} text={text} />}
          {activeTab === 'alerts' && <AlertsTab alerts={visitAlerts} onAcknowledge={handleAcknowledgeAlert} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'disposition' && <DispositionTab visit={visit} onDisposition={handleRecordDisposition} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
        </div>
      </div>
    </div>
  );
}

// ═══════ OVERVIEW TAB ═══════
//
// Renders the persistent patient context (profile, guardian, emergency contact)
// alongside the visit-scoped summary (latest vitals, triage, clinical stats).
// `patient` is the full PatientResponse fetched once when the chart opens —
// it carries allergies, chronic conditions, blood type, pregnancy status,
// and guardian info that the visit alone does not.
function OverviewTab({ visit, patient, onPatientUpdate, latestVitals, latestTriage, notes, diagnoses, investigations, medications, alerts, glassCard, glassInner, isDark, text }: any) {
  const unackAlerts = alerts.filter((a: ClinicalAlertResponse) => !a.acknowledged).length;

  // Tokens for "no record" vs "data unavailable" — never silently empty.
  const renderField = (value: string | null | undefined): string => {
    if (value === undefined && !patient) return 'Profile unavailable';
    if (!value || !value.trim()) return 'None on record';
    return value;
  };

  // Save handlers for the editable medical-history rows. Optimistic update:
  // we update the local patient state immediately for fast feedback, then
  // overwrite with the authoritative server response after the save succeeds.
  // On failure, we revert to the previous state and surface the error to
  // the caller. The medication safety engine reads from these fields on
  // every prescribe — getting the update wrong silently is a real safety
  // risk, hence the rollback.
  const handleSaveAllergies = async (next: string) => {
    if (!patient) throw new Error('Patient not loaded');
    const previous = patient.knownAllergies;
    onPatientUpdate?.({ ...patient, knownAllergies: next });
    try {
      const updated = await patientApi.updateAllergies(patient.id, next || null);
      onPatientUpdate?.(updated);
    } catch (err) {
      onPatientUpdate?.({ ...patient, knownAllergies: previous });
      throw err;
    }
  };

  const handleSaveChronic = async (next: string) => {
    if (!patient) throw new Error('Patient not loaded');
    const previous = patient.chronicConditions;
    onPatientUpdate?.({ ...patient, chronicConditions: next });
    try {
      const updated = await patientApi.updateChronicConditions(patient.id, next || null);
      onPatientUpdate?.(updated);
    } catch (err) {
      onPatientUpdate?.({ ...patient, chronicConditions: previous });
      throw err;
    }
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
      {/* Visit Info */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-lg bg-cyan-500/15 flex items-center justify-center">
            <User className="w-4 h-4 text-cyan-500" />
          </div>
          <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Visit Information</h3>
        </div>
        <div className="space-y-2.5">
          <InfoRow label="Visit #" value={visit.visitNumber} isDark={isDark} />
          <InfoRow label="Patient" value={visit.patientName} isDark={isDark} />
          <InfoRow label="Status" value={visit.status.replace(/_/g, ' ')} isDark={isDark} />
          <InfoRow label="Arrival" value={visit.arrivalTime ? format(new Date(visit.arrivalTime), 'dd MMM yyyy HH:mm') : '—'} isDark={isDark} />
          <InfoRow label="Arrival Mode" value={visit.arrivalMode?.replace(/_/g, ' ') || '—'} isDark={isDark} />
          <InfoRow label="Chief Complaint" value={visit.chiefComplaint || '—'} isDark={isDark} />
          <InfoRow label="Pediatric" value={visit.isPediatric ? 'Yes' : 'No'} isDark={isDark} />
          <InfoRow label="Re-triage Count" value={String(visit.retriageCount)} isDark={isDark} />
        </div>
      </div>

      {/* Latest Vitals Summary */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-lg bg-emerald-500/15 flex items-center justify-center">
            <Activity className="w-4 h-4 text-emerald-500" />
          </div>
          <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Latest Vitals</h3>
        </div>
        {latestVitals ? (
          <div className="grid grid-cols-2 gap-3">
            <VitalTile icon={Heart} label="Heart Rate" value={`${latestVitals.heartRate || '—'} bpm`} color="text-red-500" bg="bg-red-500/10" glassInner={glassInner} isDark={isDark} />
            <VitalTile icon={Droplets} label="SpO2" value={`${latestVitals.spo2 || '—'}%`} color="text-cyan-500" bg="bg-cyan-500/10" glassInner={glassInner} isDark={isDark} />
            <VitalTile icon={Wind} label="Resp Rate" value={`${latestVitals.respiratoryRate || '—'} /min`} color="text-blue-500" bg="bg-blue-500/10" glassInner={glassInner} isDark={isDark} />
            <VitalTile icon={Thermometer} label="Temp" value={`${latestVitals.temperature || '—'} °C`} color="text-amber-500" bg="bg-amber-500/10" glassInner={glassInner} isDark={isDark} />
            <VitalTile icon={Activity} label="Blood Pressure" value={`${latestVitals.systolicBp || '—'}/${latestVitals.diastolicBp || '—'} mmHg`} color="text-violet-500" bg="bg-violet-500/10" glassInner={glassInner} isDark={isDark} />
            <VitalTile icon={Brain} label="AVPU" value={latestVitals.avpu || '—'} color="text-emerald-500" bg="bg-emerald-500/10" glassInner={glassInner} isDark={isDark} />
          </div>
        ) : (
          <p className={`text-sm ${text.muted}`}>No vitals recorded yet</p>
        )}
      </div>

      {/* Triage Summary */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-lg bg-orange-500/15 flex items-center justify-center">
            <Stethoscope className="w-4 h-4 text-orange-500" />
          </div>
          <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Triage Summary</h3>
        </div>
        {latestTriage ? (
          <div className="space-y-2.5">
            <InfoRow label="Category" value={latestTriage.triageCategory} isDark={isDark} />
            <InfoRow label="TEWS Score" value={String(latestTriage.tewsScore)} isDark={isDark} />
            <InfoRow label="Decision Path" value={latestTriage.decisionPath || '—'} isDark={isDark} />
            <InfoRow label="Triaged By" value={latestTriage.triagedByName || '—'} isDark={isDark} />
            <InfoRow label="Weight" value={latestTriage.weightKg != null ? `${latestTriage.weightKg} kg` : '—'} isDark={isDark} />
            <InfoRow label="Time" value={latestTriage.triageTime ? format(new Date(latestTriage.triageTime), 'dd MMM yyyy HH:mm') : '—'} isDark={isDark} />
          </div>
        ) : (
          <p className={`text-sm ${text.muted}`}>Not yet triaged</p>
        )}
      </div>

      {/* Patient Profile — persistent demographics + medical history.
          Lives in the Overview alongside the visit-scoped cards because a
          doctor opening the chart needs both: who is this patient, and
          what's happening with them now. */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-lg bg-rose-500/15 flex items-center justify-center">
            <Heart className="w-4 h-4 text-rose-500" />
          </div>
          <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Patient Profile</h3>
        </div>
        {patient ? (
          <div className="space-y-2.5">
            <InfoRow label="MRN" value={patient.medicalRecordNumber || '—'} isDark={isDark} />
            <InfoRow label="Date of Birth" value={patient.dateOfBirth ? format(new Date(patient.dateOfBirth), 'dd MMM yyyy') : '—'} isDark={isDark} />
            <InfoRow label="Age" value={patient.ageInYears != null && patient.ageInYears >= 0 ? `${patient.ageInYears} yrs${patient.isPediatric ? ' (Pediatric)' : ''}` : '—'} isDark={isDark} />
            <InfoRow label="Gender" value={patient.gender || '—'} isDark={isDark} />
            <InfoRow label="National ID" value={patient.nationalId || '—'} isDark={isDark} />
            <InfoRow label="Blood Type" value={patient.bloodType || '—'} isDark={isDark} />
            {/* Allergies and chronic conditions are editable in-place. They
                drive the medication safety engine on every prescribe, so
                clinicians need to be able to correct or extend them mid-
                visit (e.g. patient reacts to a test dose, or family arrives
                and clarifies history). The renderField fallback applies
                when the field is null or empty. */}
            <EditableMedicalRow
              label="Allergies"
              value={patient.knownAllergies}
              onSave={handleSaveAllergies}
              isDark={isDark}
              glassInner={glassInner}
              text={text}
              accent="red"
              placeholder="e.g. Penicillin, Latex, Peanuts. Use 'NKDA' for no known drug allergies."
            />
            <EditableMedicalRow
              label="Chronic Conditions"
              value={patient.chronicConditions}
              onSave={handleSaveChronic}
              isDark={isDark}
              glassInner={glassInner}
              text={text}
              accent="amber"
              placeholder="e.g. Diabetes Type 2, Hypertension, HIV (controlled)."
            />
            {/* Pregnancy is NOT shown in this card for non-female patients —
                the safety banner above already shows it where applicable.
                Repeating "N/A" here would just be noise. */}
            {patient.pregnancyStatus && patient.pregnancyStatus !== 'NOT_APPLICABLE' && (
              <InfoRow label="Pregnancy Status" value={patient.pregnancyStatus.replace(/_/g, ' ')} isDark={isDark} />
            )}
          </div>
        ) : (
          <p className={`text-sm ${isDark ? 'text-amber-300' : 'text-amber-700'}`}>
            Patient profile unavailable. Verify allergies and weight with the patient before prescribing.
          </p>
        )}
      </div>

      {/* Guardian — only meaningful for pediatric patients. Hidden for
          adults to avoid empty cards crowding the overview. */}
      {patient?.isPediatric && (
        <div className="rounded-2xl p-5" style={glassCard}>
          <div className="flex items-center gap-2 mb-4">
            <div className="w-8 h-8 rounded-lg bg-blue-500/15 flex items-center justify-center">
              <User className="w-4 h-4 text-blue-500" />
            </div>
            <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Guardian</h3>
          </div>
          {(patient.guardianName || patient.guardianPhone) ? (
            <div className="space-y-2.5">
              <InfoRow label="Name" value={patient.guardianName || '—'} isDark={isDark} />
              <InfoRow label="Relationship" value={patient.guardianRelationship || '—'} isDark={isDark} />
              <InfoRow label="Phone" value={patient.guardianPhone || '—'} isDark={isDark} />
              <InfoRow label="National ID" value={patient.guardianNationalId || '—'} isDark={isDark} />
            </div>
          ) : (
            <p className={`text-sm ${isDark ? 'text-amber-300' : 'text-amber-700'}`}>
              No guardian on record. Pediatric consent / disposition decisions require guardian contact — verify before proceeding.
            </p>
          )}
        </div>
      )}

      {/* Emergency Contact — separate from guardian for adults; shown for
          everyone but only when there's actually a contact on record. */}
      {patient && (patient.emergencyContactName || patient.emergencyContactPhone) && (
        <div className="rounded-2xl p-5" style={glassCard}>
          <div className="flex items-center gap-2 mb-4">
            <div className="w-8 h-8 rounded-lg bg-emerald-500/15 flex items-center justify-center">
              <User className="w-4 h-4 text-emerald-500" />
            </div>
            <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Emergency Contact</h3>
          </div>
          <div className="space-y-2.5">
            <InfoRow label="Name" value={patient.emergencyContactName || '—'} isDark={isDark} />
            <InfoRow label="Phone" value={patient.emergencyContactPhone || '—'} isDark={isDark} />
          </div>
        </div>
      )}

      {/* Quick Stats */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 rounded-lg bg-violet-500/15 flex items-center justify-center">
            <ClipboardList className="w-4 h-4 text-violet-500" />
          </div>
          <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Clinical Summary</h3>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <StatCard label="Clinical Notes" count={notes.length} color="text-blue-500" bg="bg-blue-500/10" glassInner={glassInner} isDark={isDark} />
          <StatCard label="Diagnoses" count={diagnoses.length} color="text-purple-500" bg="bg-purple-500/10" glassInner={glassInner} isDark={isDark} />
          <StatCard label="Investigations" count={investigations.length} color="text-amber-500" bg="bg-amber-500/10" glassInner={glassInner} isDark={isDark} />
          <StatCard label="Medications" count={medications.length} color="text-emerald-500" bg="bg-emerald-500/10" glassInner={glassInner} isDark={isDark} />
        </div>
        {unackAlerts > 0 && (
          <div className="mt-3 p-3 rounded-xl flex items-center gap-2" style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.2)' }}>
            <AlertTriangle className="w-4 h-4 text-red-500" />
            <span className="text-red-400 text-xs font-bold">{unackAlerts} unacknowledged alert{unackAlerts > 1 ? 's' : ''}</span>
          </div>
        )}
      </div>
    </div>
  );
}

// ═══════ VITALS TAB ═══════
//
// READ-ONLY by design — vitals are recorded by the monitoring system
// (IoT devices, triage form, ambulance handover), NOT by the doctor on
// this screen. The previous "Record Vitals" button created a path for
// the doctor to manually log vitals, which both duplicated the monitoring
// system and broke the assumption that vitals always carry a source.
//
// Relationship to the Monitor tab:
//   - Monitor tab  = current reading + sparkline trend, the "what's
//                    happening right now" view
//   - Vitals tab   = chronological history table, the "show me every
//                    reading and where it came from" view for retrospective
//                    review (e.g. "what was their HR at 04:00?", "did
//                    triage record an SpO2?")
// They complement each other — same data, different question being asked.
//
function VitalsTab({ vitals, latestVitals, glassCard, glassInner, isDark, text }: any) {
  // Sort newest first for the table — clinicians usually want the latest
  // at the top, with the option to scroll back in time.
  const sorted = [...(vitals || [])].sort((a: VitalSignsResponse, b: VitalSignsResponse) => {
    const ta = a.recordedAt ? new Date(a.recordedAt).getTime() : 0;
    const tb = b.recordedAt ? new Date(b.recordedAt).getTime() : 0;
    return tb - ta;
  });

  const SOURCE_BADGE: Record<string, string> = {
    IOT_DEVICE: 'text-cyan-500 bg-cyan-500/10 border-cyan-500/20',
    MANUAL_ENTRY: 'text-slate-500 bg-slate-500/10 border-slate-500/20',
    AMBULANCE_MONITOR: 'text-violet-500 bg-violet-500/10 border-violet-500/20',
    IMPORTED: 'text-amber-500 bg-amber-500/10 border-amber-500/20',
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Vital Signs History</h3>
          <p className={`text-xs mt-0.5 ${text.muted}`}>
            Read-only chronological record. Vitals are captured by the monitoring system; see the Monitor tab for the live view.
          </p>
        </div>
        <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${isDark ? 'bg-slate-500/15 text-slate-400' : 'bg-slate-100 text-slate-500'}`}>
          {sorted.length} reading{sorted.length === 1 ? '' : 's'}
        </span>
      </div>

      {/* Latest summary band — quick-glance current state without
          having to scroll the full history. */}
      {latestVitals && (
        <div className="rounded-2xl p-4" style={glassCard}>
          <div className="flex items-center justify-between mb-3">
            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
              Most recent
            </span>
            <span className={`text-xs font-bold ${text.accent}`}>
              {latestVitals.recordedAt ? format(new Date(latestVitals.recordedAt), 'dd MMM yyyy HH:mm') : '—'}
            </span>
          </div>
          <div className="grid grid-cols-3 md:grid-cols-6 gap-2 text-xs">
            <MiniVital label="HR" value={latestVitals.heartRate ? `${latestVitals.heartRate}` : '—'} unit="bpm" isDark={isDark} />
            <MiniVital label="SpO2" value={latestVitals.spo2 ? `${latestVitals.spo2}` : '—'} unit="%" isDark={isDark} />
            <MiniVital label="RR" value={latestVitals.respiratoryRate ? `${latestVitals.respiratoryRate}` : '—'} unit="/min" isDark={isDark} />
            <MiniVital label="BP" value={latestVitals.systolicBp ? `${latestVitals.systolicBp}/${latestVitals.diastolicBp}` : '—'} unit="" isDark={isDark} />
            <MiniVital label="Temp" value={latestVitals.temperature ? `${latestVitals.temperature}` : '—'} unit="°C" isDark={isDark} />
            <MiniVital label="AVPU" value={latestVitals.avpu || '—'} unit="" isDark={isDark} />
          </div>
        </div>
      )}

      {/* Chronological table — newest first.
          Source badge tells the clinician WHERE each reading came from,
          which matters when reconciling readings of different quality
          (continuous IoT stream vs single manual entry vs imported handover). */}
      <div className="space-y-3">
        {sorted.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <Activity className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={`text-sm ${text.heading}`}>No vital signs on record yet.</p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              Vitals will appear here as they are captured at triage, by an IoT monitor, or during ambulance handover.
            </p>
          </div>
        ) : sorted.map((v: VitalSignsResponse) => (
          <div key={v.id} className="rounded-2xl p-4" style={glassCard}>
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <span className={`text-xs font-bold ${text.heading}`}>
                  {v.recordedAt ? format(new Date(v.recordedAt), 'dd MMM yyyy HH:mm') : '—'}
                </span>
                {v.recordedByName && (
                  <span className={`text-[10px] ${text.muted}`}>by {v.recordedByName}</span>
                )}
              </div>
              <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg border ${SOURCE_BADGE[v.source ?? ''] ?? SOURCE_BADGE.MANUAL_ENTRY}`}>
                {v.source?.replace(/_/g, ' ') || 'Source unknown'}
              </span>
            </div>
            <div className="grid grid-cols-3 md:grid-cols-6 gap-2 text-xs">
              <MiniVital label="HR" value={v.heartRate ? `${v.heartRate}` : '—'} unit="bpm" isDark={isDark} />
              <MiniVital label="SpO2" value={v.spo2 ? `${v.spo2}` : '—'} unit="%" isDark={isDark} />
              <MiniVital label="RR" value={v.respiratoryRate ? `${v.respiratoryRate}` : '—'} unit="/min" isDark={isDark} />
              <MiniVital label="BP" value={v.systolicBp ? `${v.systolicBp}/${v.diastolicBp}` : '—'} unit="" isDark={isDark} />
              <MiniVital label="Temp" value={v.temperature ? `${v.temperature}` : '—'} unit="°C" isDark={isDark} />
              <MiniVital label="AVPU" value={v.avpu || '—'} unit="" isDark={isDark} />
            </div>
            {(v.painScore != null || v.gcsScore != null || v.bloodGlucose != null) && (
              <div className="grid grid-cols-3 gap-2 text-xs mt-2 pt-2 border-t border-slate-200/10">
                {v.painScore != null && <MiniVital label="Pain" value={`${v.painScore}`} unit="/10" isDark={isDark} />}
                {v.gcsScore != null && <MiniVital label="GCS" value={`${v.gcsScore}`} unit="/15" isDark={isDark} />}
                {v.bloodGlucose != null && <MiniVital label="Glucose" value={`${v.bloodGlucose}`} unit="mmol/L" isDark={isDark} />}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════ TRIAGE TAB ═══════
//
// READ-ONLY by design.
//
// Triage is performed by the triage nurse at point of entry. By the time
// the patient appears on the doctor's chart, triage is already done. The
// doctor's role here is to *read* the triage record and act on it (assess,
// prescribe, dispose) — never to start a new triage from this view.
//
// Anything that would let the doctor initiate a triage flow from here is
// deliberately removed:
//   - No "Start Triage" button
//   - No "Re-Triage" button (re-triage belongs in the dedicated retriage
//     module, not on the chart-review surface)
//   - Empty-state copy explains who is responsible, not how to start one
//
// The display is purely informational: triage color band, scores, presenting
// complaint, the triage nurse who performed it, and the timestamp.
//
function TriageTab({ visit, triageHistory, latestTriage, glassCard, glassInner, isDark, text }: any) {
  const catColor = latestTriage ? CATEGORY_COLORS[latestTriage.triageCategory] : null;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Triage Record</h3>
        <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${isDark ? 'bg-slate-500/15 text-slate-400' : 'bg-slate-100 text-slate-500'}`}>
          Read-only
        </span>
      </div>

      {latestTriage ? (
        <>
          {/* ── Latest triage result — colored band that mirrors the triage category ── */}
          <div className={`rounded-2xl overflow-hidden ${catColor?.border || 'border-slate-300'} border`} style={glassCard}>
            {/* Top color band — single-glance acuity signal. Never silent. */}
            <div className={`h-2 ${catColor?.dot || 'bg-slate-400'}`} />
            <div className="p-5">
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-3">
                  <span className={`w-3 h-3 rounded-full ${catColor?.dot || 'bg-slate-400'}`} />
                  <span className={`text-base font-extrabold ${catColor?.text || text.heading}`}>{latestTriage.triageCategory}</span>
                  <span className={`text-2xl font-black ${text.accent}`}>TEWS {latestTriage.tewsScore}</span>
                </div>
                <div className="text-right">
                  <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Performed</p>
                  <p className={`text-xs font-bold ${text.heading}`}>
                    {latestTriage.triageTime ? format(new Date(latestTriage.triageTime), 'dd MMM yyyy HH:mm') : '—'}
                  </p>
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
                <InfoRow label="Triaged By" value={latestTriage.triagedByName || '—'} isDark={isDark} />
                <InfoRow label="Chief Complaint" value={visit.chiefComplaint || '—'} isDark={isDark} />
                <InfoRow label="Decision Path" value={latestTriage.decisionPath || '—'} isDark={isDark} />
                <InfoRow label="Form Used" value={latestTriage.isChildForm ? 'Pediatric' : 'Adult'} isDark={isDark} />
                {latestTriage.weightKg != null && (
                  <InfoRow label="Weight" value={`${latestTriage.weightKg} kg`} isDark={isDark} />
                )}
                {latestTriage.isRetriage && (
                  <InfoRow label="Re-triage of" value={latestTriage.previousCategory || 'previous'} isDark={isDark} />
                )}
              </div>
            </div>
          </div>

          {/* ── Earlier triage records, if any ── */}
          {triageHistory.length > 1 && (
            <div className="rounded-2xl p-5" style={glassCard}>
              <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Earlier Triage Entries</h4>
              <div className="space-y-3">
                {triageHistory.slice(1).map((t: TriageRecordResponse) => (
                  <div key={t.id} className="rounded-xl p-3" style={glassInner}>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className={`w-2.5 h-2.5 rounded-full ${CATEGORY_COLORS[t.triageCategory]?.dot || 'bg-slate-400'}`} />
                        <span className={`text-xs font-bold ${text.heading}`}>{t.triageCategory} — TEWS {t.tewsScore}</span>
                        {t.triagedByName && <span className={`text-[10px] ${text.muted}`}>by {t.triagedByName}</span>}
                      </div>
                      <span className={`text-[10px] ${text.muted}`}>{t.triageTime ? format(new Date(t.triageTime), 'dd MMM HH:mm') : ''}</span>
                    </div>
                    {t.decisionPath && <p className={`text-[11px] mt-1 ${text.body}`}>{t.decisionPath}</p>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      ) : (
        // Empty state — no record to display. The copy is deliberate:
        // it does NOT invite the doctor to start a triage. Triage is
        // performed at entry by the triage nurse.
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <Stethoscope className="w-8 h-8 mx-auto mb-2 text-slate-400" />
          <p className={`text-sm font-semibold ${text.heading}`}>No triage record on this visit yet.</p>
          <p className={`text-xs mt-1 ${text.muted}`}>
            Triage is performed by the triage nurse at point of entry. If a record is missing, please ask
            the triage nurse to complete it.
          </p>
        </div>
      )}
    </div>
  );
}

// Section suggestions per note type. Doctor doesn't have to type the
// section freehand; a quick-pick chip auto-fills it. Free text is still
// allowed for the rare case the doctor needs something custom.
//
// These are the structured frames clinicians use across their clinical
// notes — SOAP for progress notes, SBAR for handovers, the standard
// physical-exam systems for findings, etc. They map to how a doctor
// actually thinks while writing, so picking the right section is fast
// and the resulting record stays consistent across charts.
const NOTE_SECTION_SUGGESTIONS: Record<NoteType, string[]> = {
  PROGRESS_NOTE: ['Subjective', 'Objective', 'Assessment', 'Plan'],
  DOCTOR_NOTE: ['Initial Assessment', 'Reassessment', 'Procedure Note', 'Consult Request', 'Handover'],
  NURSING_NOTE: ['Care Plan', 'Intervention', 'Observation', 'Patient Response'],
  PHYSICAL_FINDINGS: ['General', 'HEENT', 'Cardiovascular', 'Respiratory', 'Abdomen', 'Neurological', 'Skin', 'Musculoskeletal', 'Genitourinary'],
  HISTORY_OF_PRESENTING_COMPLAINT: ['Onset', 'Duration', 'Severity', 'Aggravating Factors', 'Relieving Factors', 'Associated Symptoms'],
  PAST_MEDICAL_HISTORY: ['Medical', 'Surgical', 'Hospitalizations', 'Immunizations'],
  SOCIAL_HISTORY: ['Occupation', 'Smoking', 'Alcohol', 'Substance Use', 'Living Situation', 'Travel'],
  FAMILY_HISTORY: ['Cardiac', 'Cancer', 'Diabetes', 'Hypertension', 'Mental Health', 'Other'],
  REVIEW_OF_SYSTEMS: ['Constitutional', 'HEENT', 'Cardiac', 'Respiratory', 'GI', 'GU', 'MSK', 'Neurological', 'Psychiatric', 'Endocrine'],
  ALLERGIES: ['Drug', 'Food', 'Environmental', 'Latex', 'Reaction Description'],
  CURRENT_MEDICATIONS: ['Daily', 'PRN', 'Recently Started', 'Recently Stopped'],
  TREATMENT_PLAN: ['Pharmacological', 'Non-Pharmacological', 'Monitoring', 'Disposition', 'Patient Education'],
  TRIAGE_NOTE: ['Acuity', 'ABC', 'Disability'],
  DISCHARGE_SUMMARY: ['Reason for Admission', 'Hospital Course', 'Treatment Given', 'Discharge Diagnosis', 'Follow-up Plan'],
  HANDOVER: ['Situation', 'Background', 'Assessment', 'Recommendation'],
  OTHER: [],
};

// Note-type-specific placeholder so the doctor knows what kind of content
// belongs in the body field for each frame.
const NOTE_CONTENT_PLACEHOLDER: Record<NoteType, string> = {
  PROGRESS_NOTE: 'Brief clinical update — what changed, what was done, what is planned next.',
  DOCTOR_NOTE: 'Clinical observation, decision, or rationale.',
  NURSING_NOTE: 'Nursing observation, intervention, or response.',
  PHYSICAL_FINDINGS: 'Findings on physical exam for the selected system.',
  HISTORY_OF_PRESENTING_COMPLAINT: 'Description of the presenting complaint as told by the patient.',
  PAST_MEDICAL_HISTORY: 'Significant past medical or surgical history.',
  SOCIAL_HISTORY: 'Relevant social context.',
  FAMILY_HISTORY: 'Relevant family medical history.',
  REVIEW_OF_SYSTEMS: 'Findings on review of the selected system.',
  ALLERGIES: 'Allergen, reaction type, severity.',
  CURRENT_MEDICATIONS: 'Drug name, dose, frequency, indication.',
  TREATMENT_PLAN: 'Plan for the selected aspect of care.',
  TRIAGE_NOTE: 'Triage observation.',
  DISCHARGE_SUMMARY: 'Summary content for the selected section.',
  HANDOVER: 'Handover content using the SBAR frame.',
  OTHER: 'Clinical note content.',
};

// ═══════ NOTES TAB ═══════
//
// Comprehensive but fast: the doctor picks a Note Type, then picks one of
// the type-specific Section chips, then writes the body. No more typing
// "Assessment" or "Cardiovascular" by hand.
//
// We keep section as free text on the wire (matches the existing backend
// schema) — chips are just a fast-input affordance, the doctor can type
// a custom section if none of the suggestions fit.
//
function NotesTab({ notes, showForm, setShowForm, onSubmit, formLoading, glassCard, glassInner, isDark, text }: any) {
  const [form, setForm] = useState<Partial<CreateClinicalNoteRequest>>({
    noteType: 'PROGRESS_NOTE' as NoteType, content: '', section: '',
  });

  const currentNoteType = (form.noteType || 'PROGRESS_NOTE') as NoteType;
  const sectionChips = NOTE_SECTION_SUGGESTIONS[currentNoteType] ?? [];

  // When the user changes note type we clear the section: previous chip
  // is unlikely to be valid for the new frame (e.g. "Subjective" makes
  // no sense for a HANDOVER note). Free-text typing always overrides
  // the chip selection, but the auto-clear protects against a stale
  // selection silently sneaking through.
  const handleTypeChange = (nt: NoteType) => {
    setForm((f) => ({ ...f, noteType: nt, section: '' }));
  };

  const placeholder = NOTE_CONTENT_PLACEHOLDER[currentNoteType] ?? 'Clinical note content.';

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Clinical Notes ({notes.length})</h3>
        <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
          <Plus className="w-3.5 h-3.5" /> Add Note
        </button>
      </div>

      {showForm && (
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>New Clinical Note</h4>
          <div className="space-y-3">
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Note Type</label>
              <select
                value={currentNoteType}
                onChange={(e) => handleTypeChange(e.target.value as NoteType)}
                className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
                style={glassInner}
              >
                {Object.entries(NOTE_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </div>

            {/* Section — chips when the note type has suggestions, free
                text otherwise. Chips toggle: click again to clear. */}
            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className={`block text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Section</label>
                {sectionChips.length > 0 && (
                  <span className={`text-[10px] ${text.muted}`}>Pick one or type custom below</span>
                )}
              </div>
              {sectionChips.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mb-2">
                  {sectionChips.map((s) => {
                    const active = (form.section || '').trim() === s;
                    return (
                      <button
                        key={s}
                        type="button"
                        onClick={() => setForm({ ...form, section: active ? '' : s })}
                        className={`px-2.5 py-1 text-[11px] font-bold rounded-lg transition-colors ${
                          active
                            ? 'bg-cyan-500 text-white'
                            : 'bg-cyan-500/10 text-cyan-600 hover:bg-cyan-500/20'
                        }`}
                      >
                        {s}
                      </button>
                    );
                  })}
                </div>
              )}
              <input
                value={form.section || ''}
                onChange={(e) => setForm({ ...form, section: e.target.value })}
                placeholder={sectionChips.length > 0 ? 'Or type a custom section…' : 'Optional section heading'}
                className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>

            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Content</label>
              <textarea
                value={form.content || ''}
                onChange={(e) => setForm({ ...form, content: e.target.value })}
                placeholder={placeholder}
                rows={5}
                className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => onSubmit(form)} disabled={formLoading || !form.content?.trim()} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
              {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Save Note
            </button>
            <button onClick={() => setShowForm(false)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
          </div>
        </div>
      )}

      <div className="space-y-3">
        {notes.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <FileText className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={text.muted}>No clinical notes yet</p>
          </div>
        ) : notes.map((n: ClinicalNoteResponse) => (
          <div key={n.id} className="rounded-2xl p-4" style={glassCard}>
            <div className="flex items-center justify-between mb-2">
              <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-cyan-500 bg-cyan-500/10">
                {NOTE_TYPE_LABELS[n.noteType] || n.noteType}
              </span>
              <span className={`text-[10px] ${text.muted}`}>{n.createdAt ? format(new Date(n.createdAt), 'dd MMM yyyy HH:mm') : ''}</span>
            </div>
            {n.section && <p className={`text-[11px] font-bold mb-1 ${text.label}`}>{n.section}</p>}
            <p className={`text-sm ${text.body} whitespace-pre-wrap`}>{n.content}</p>
            <p className={`text-[10px] mt-2 ${text.muted}`}>By: {n.recordedByName}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════ DIAGNOSES TAB ═══════
//
// Form is delegated to <DiagnosisPanel>, which owns the ICD-10 catalog
// search and common-in-Rwanda quick-pick. This tab is the list + read view.
//
function DiagnosesTab({ diagnoses, showForm, setShowForm, onSubmit, formLoading, glassCard, glassInner, isDark, text }: any) {
  const typeColors: Record<string, string> = {
    PROVISIONAL: 'text-amber-500 bg-amber-500/10',
    CONFIRMED: 'text-emerald-500 bg-emerald-500/10',
    DIFFERENTIAL: 'text-blue-500 bg-blue-500/10',
    WORKING: 'text-violet-500 bg-violet-500/10',
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Diagnoses ({diagnoses.length})</h3>
        <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
          <Plus className="w-3.5 h-3.5" /> Add Diagnosis
        </button>
      </div>

      {showForm && (
        <DiagnosisPanel
          onSubmit={async (req) => { await onSubmit(req); setShowForm(false); }}
          onClose={() => setShowForm(false)}
          formLoading={formLoading}
          glassCard={glassCard}
          glassInner={glassInner}
          isDark={isDark}
          text={text}
        />
      )}

      <div className="space-y-3">
        {diagnoses.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <ClipboardList className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={text.muted}>No diagnoses recorded yet</p>
          </div>
        ) : diagnoses.map((d: DiagnosisResponse) => (
          <div key={d.id} className="rounded-2xl p-4" style={glassCard}>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${typeColors[d.diagnosisType] || 'text-slate-500 bg-slate-500/10'}`}>
                  {d.diagnosisType}
                </span>
                {d.isPrimary && <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-violet-500 bg-violet-500/10">PRIMARY</span>}
                {d.icdCode && <span className={`text-[10px] font-mono ${text.muted}`}>ICD: {d.icdCode}</span>}
              </div>
              <span className={`text-[10px] ${text.muted}`}>{d.createdAt ? format(new Date(d.createdAt), 'dd MMM yyyy HH:mm') : ''}</span>
            </div>
            <p className={`text-sm font-medium ${text.heading}`}>{d.description}</p>
            {d.notes && <p className={`text-xs mt-1 ${text.body}`}>{d.notes}</p>}
            <p className={`text-[10px] mt-2 ${text.muted}`}>By: {d.diagnosedByName}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════ INVESTIGATIONS TAB ═══════
function InvestigationsTab({ investigations, showForm, setShowForm, onSubmit, onAction, formLoading, glassCard, glassInner, isDark, text, userName: _userName }: any) {
  const [resultForm, setResultForm] = useState<{ id: string; result: string; isAbnormal: boolean; isCritical: boolean; notes: string } | null>(null);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Investigations ({investigations.length})</h3>
        <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
          <Plus className="w-3.5 h-3.5" /> Order Investigation
        </button>
      </div>

      {showForm && (
        <InvestigationPanel
          onSubmit={async (req) => { await onSubmit(req); setShowForm(false); }}
          onClose={() => setShowForm(false)}
          formLoading={formLoading}
          glassCard={glassCard}
          glassInner={glassInner}
          isDark={isDark}
          text={text}
        />
      )}

      {/* Result form — structured entry with quick-pick outcomes for
          common qualitative tests (RDTs, cultures, microscopy). The
          investigation context (test name, clinical use from the catalog)
          is shown above the input so the doctor remembers what was
          ordered and why. Abnormal / Critical flags are auto-suggested
          when the doctor picks an obviously-positive outcome chip; the
          doctor can always override the suggestion. */}
      {resultForm && (() => {
        const inv = investigations.find((i: InvestigationResponse) => i.id === resultForm.id);
        const isQualitative = inv && (
          inv.investigationType === 'RAPID_TEST'
          || /culture|smear|rdt|microscopy|elisa|pcr|antigen|antibody|widal/i.test(inv.testName || '')
        );
        // Quick-pick outcomes — each carries a suggested abnormal/critical
        // hint. The doctor's checkboxes are populated when they click a
        // chip but stay editable.
        const qualitativeOutcomes: Array<{ label: string; result: string; abnormal: boolean; critical: boolean; tone: 'green' | 'red' | 'amber' | 'slate' }> = [
          { label: 'Negative',           result: 'Negative',                abnormal: false, critical: false, tone: 'green' },
          { label: 'Positive',           result: 'Positive',                abnormal: true,  critical: false, tone: 'red' },
          { label: 'Detected',           result: 'Detected',                abnormal: true,  critical: false, tone: 'red' },
          { label: 'Not Detected',       result: 'Not detected',            abnormal: false, critical: false, tone: 'green' },
          { label: 'Within Normal Limits', result: 'Within normal limits',  abnormal: false, critical: false, tone: 'green' },
          { label: 'Sample Rejected',    result: 'Sample rejected',         abnormal: false, critical: false, tone: 'amber' },
        ];
        const toneStyle: Record<string, string> = {
          green: 'bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20',
          red: 'bg-red-500/10 text-red-600 hover:bg-red-500/20',
          amber: 'bg-amber-500/10 text-amber-600 hover:bg-amber-500/20',
          slate: 'bg-slate-500/10 text-slate-600 hover:bg-slate-500/20',
        };
        const applyQuickPick = (q: typeof qualitativeOutcomes[0]) => {
          setResultForm({ ...resultForm, result: q.result, isAbnormal: q.abnormal, isCritical: q.critical });
        };
        return (
          <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
            <div className="flex items-start justify-between mb-4">
              <div>
                <h4 className={`text-sm font-bold ${text.heading}`}>Record Result</h4>
                {inv && (
                  <div className="flex items-center gap-2 mt-1 flex-wrap">
                    <span className={`text-xs font-bold ${text.heading}`}>{inv.testName}</span>
                    <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-slate-500/15 ${text.muted}`}>
                      {inv.investigationType?.replace(/_/g, ' ')}
                    </span>
                    {inv.priority && inv.priority !== 'ROUTINE' && (
                      <span className="text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-500/15 text-red-600">
                        {inv.priority}
                      </span>
                    )}
                  </div>
                )}
                {inv?.notes && (
                  <p className={`text-[11px] mt-1 ${text.muted}`}>Order context: {inv.notes}</p>
                )}
              </div>
            </div>

            <div className="space-y-3">
              {/* Quick-pick chips for qualitative tests (RDTs, cultures,
                  microscopy). Hidden for tests that need a numeric value
                  to avoid pre-filling something the doctor needs to type. */}
              {isQualitative && (
                <div>
                  <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
                    Quick outcome
                  </label>
                  <div className="flex flex-wrap gap-1.5">
                    {qualitativeOutcomes.map((q) => (
                      <button
                        key={q.label}
                        type="button"
                        onClick={() => applyQuickPick(q)}
                        className={`px-2.5 py-1 text-[11px] font-bold rounded-lg transition-colors ${toneStyle[q.tone]} ${
                          resultForm.result === q.result ? 'ring-2 ring-offset-1 ring-current' : ''
                        }`}
                      >
                        {q.label}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Result value — what's actually being reported. Renamed
                  from "Result" to make the difference vs "Notes" clearer.
                  Single-line input for short qualitative answers; multi-line
                  for narrative findings. */}
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
                  Result Value
                </label>
                {isQualitative ? (
                  <input
                    value={resultForm.result}
                    onChange={(e) => setResultForm({ ...resultForm, result: e.target.value })}
                    placeholder="e.g. Negative, 4+ parasitaemia, growth of E. coli"
                    className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                    style={glassInner}
                  />
                ) : (
                  <textarea
                    value={resultForm.result}
                    onChange={(e) => setResultForm({ ...resultForm, result: e.target.value })}
                    rows={3}
                    placeholder="Numeric values, findings, or narrative description."
                    className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                    style={glassInner}
                  />
                )}
              </div>

              {/* Notes — interpretation, follow-up, comments separate from
                  the canonical Result Value. Future searches/exports can
                  treat the two fields differently. */}
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
                  Notes <span className={`font-normal normal-case ${text.muted}`}>(optional)</span>
                </label>
                <textarea
                  value={resultForm.notes}
                  onChange={(e) => setResultForm({ ...resultForm, notes: e.target.value })}
                  rows={2}
                  placeholder="Interpretation, sensitivity, follow-up recommendations…"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>

              {/* Abnormal / Critical flags — auto-suggested by the
                  quick-pick chips, doctor can always override. Critical
                  is highlighted because it triggers a clinical alert. */}
              <div className="flex items-center gap-4 pt-1">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={resultForm.isAbnormal}
                    onChange={(e) => setResultForm({ ...resultForm, isAbnormal: e.target.checked })}
                    className="rounded"
                  />
                  <span className={`text-xs font-medium ${resultForm.isAbnormal ? 'text-amber-600' : text.body}`}>
                    Abnormal
                  </span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={resultForm.isCritical}
                    onChange={(e) => setResultForm({ ...resultForm, isCritical: e.target.checked, isAbnormal: e.target.checked || resultForm.isAbnormal })}
                    className="rounded"
                  />
                  <span className={`text-xs font-bold ${resultForm.isCritical ? 'text-red-600' : text.body}`}>
                    Critical
                  </span>
                </label>
                {resultForm.isCritical && (
                  <span className="text-[10px] font-bold uppercase tracking-wider text-red-600 ml-auto">
                    Will trigger clinical alert
                  </span>
                )}
              </div>
            </div>

            <div className="flex items-center gap-3 mt-4">
              <button
                onClick={() => {
                  onAction(resultForm.id, 'result', {
                    investigationId: resultForm.id,
                    result: resultForm.result,
                    isAbnormal: resultForm.isAbnormal,
                    isCritical: resultForm.isCritical,
                    notes: resultForm.notes,
                  });
                  setResultForm(null);
                }}
                disabled={!resultForm.result.trim()}
                className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <CheckCircle2 className="w-3.5 h-3.5" /> Save Result
              </button>
              <button onClick={() => setResultForm(null)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>
                Cancel
              </button>
            </div>
          </div>
        );
      })()}

      <div className="space-y-3">
        {investigations.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <FlaskConical className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={text.muted}>No investigations ordered yet</p>
          </div>
        ) : investigations.map((inv: InvestigationResponse) => (
          <div key={inv.id} className="rounded-2xl p-4" style={glassCard}>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${INVESTIGATION_STATUS_COLORS[inv.status] || ''}`}>{inv.status.replace(/_/g, ' ')}</span>
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-slate-500 bg-slate-500/10`}>{inv.investigationType.replace(/_/g, ' ')}</span>
                {inv.priority === 'URGENT' && <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-red-500 bg-red-500/10">URGENT</span>}
              </div>
              <span className={`text-[10px] ${text.muted}`}>{inv.orderedAt ? format(new Date(inv.orderedAt), 'dd MMM HH:mm') : ''}</span>
            </div>
            <p className={`text-sm font-medium ${text.heading}`}>{inv.testName}</p>
            {inv.result && (
              <div className="mt-2 p-2.5 rounded-lg" style={glassInner}>
                <p className={`text-xs font-bold ${inv.isCritical ? 'text-red-500' : inv.isAbnormal ? 'text-amber-500' : text.body}`}>{inv.result}</p>
                {inv.isCritical && <span className="text-[10px] font-bold text-red-500">CRITICAL</span>}
                {inv.isAbnormal && !inv.isCritical && <span className="text-[10px] font-bold text-amber-500">ABNORMAL</span>}
              </div>
            )}
            {/* Actions */}
            {inv.status !== 'RESULTED' && inv.status !== 'CANCELLED' && (
              <div className="flex items-center gap-2 mt-3">
                {inv.status === 'ORDERED' && <button onClick={() => onAction(inv.id, 'specimen')} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-amber-500/10 text-amber-500 hover:bg-amber-500/20 transition-colors">Specimen Collected</button>}
                {inv.status === 'SPECIMEN_COLLECTED' && <button onClick={() => onAction(inv.id, 'progress')} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors">Mark In Progress</button>}
                {inv.status === 'IN_PROGRESS' && <button onClick={() => setResultForm({ id: inv.id, result: '', isAbnormal: false, isCritical: false, notes: '' })} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors">Record Result</button>}
              </div>
            )}
            <p className={`text-[10px] mt-2 ${text.muted}`}>Ordered by: {inv.orderedByName}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════ MEDICATIONS TAB ═══════
//
// Prescribing UI is delegated to <PrescribePanel> — that component owns the
// fast-prescribing experience (REML autocomplete, allergy precheck, pediatric
// weight-based dose, one-tap reorder). This tab is the list + actions view
// around it.
//
function MedicationsTab({ medications, patient, latestTriage, visitId, showForm, setShowForm, onSubmit, onAction, formLoading, glassCard, glassInner, isDark, text, lastSafetyCheck, onDismissSafetyCheck }: any) {
  // Surface server-side safety-engine findings the client may not have
  // caught — drug-drug interaction, dose exceeded, duplicate therapy.
  // The check exists for every prescription (audit trail); we only surface
  // it as a banner when something failed. Auto-cleared on dismiss or when
  // the form re-opens for the next prescription.
  const safetyWarnings: string[] = lastSafetyCheck
    ? [
        !lastSafetyCheck.allergyCheckPassed && lastSafetyCheck.allergyWarning,
        !lastSafetyCheck.doseCheckPassed && lastSafetyCheck.doseWarning,
        !lastSafetyCheck.interactionCheckPassed && lastSafetyCheck.interactionWarning,
        !lastSafetyCheck.duplicateTherapyCheckPassed && lastSafetyCheck.duplicateWarning,
      ].filter((s): s is string => typeof s === 'string' && s.trim().length > 0)
    : [];
  const showSafetyBanner = lastSafetyCheck != null && !lastSafetyCheck.overallSafe && safetyWarnings.length > 0;
  const overrideRecorded = !!lastSafetyCheck?.overriddenBy;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Medications ({medications.length})</h3>
        <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
          <Plus className="w-3.5 h-3.5" /> Prescribe Medication
        </button>
      </div>

      {/* ── Server-side safety-check banner ──
          Appears after a prescription whose validation flagged anything.
          Lists every failed check with its warning text so the doctor sees
          exactly what the safety engine found. If the doctor pre-supplied
          an override reason in the prescribe panel, we mark the override as
          recorded — otherwise the doctor should consider Hold or Cancel
          on the medication. */}
      {showSafetyBanner && (
        <div className="rounded-2xl border-2 border-red-500 bg-red-500/10 p-4 space-y-2">
          <div className="flex items-start justify-between">
            <div className="flex items-start gap-2 flex-1">
              <AlertTriangle className="w-5 h-5 text-red-600 mt-0.5 flex-shrink-0" />
              <div className="flex-1">
                <p className="text-xs font-extrabold uppercase tracking-wide text-red-700">
                  Safety check flagged this prescription
                </p>
                <p className={`text-[11px] mt-0.5 ${isDark ? 'text-red-200/80' : 'text-red-700'}`}>
                  Medication: <strong>{lastSafetyCheck.drugName}</strong>
                  {overrideRecorded && (
                    <span className="ml-2 text-emerald-600 font-bold">
                      · Override recorded by {lastSafetyCheck.overriddenBy}
                    </span>
                  )}
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={onDismissSafetyCheck}
              className={`p-1 rounded ${text.muted} hover:bg-white/5`}
              aria-label="Dismiss"
            >
              <XCircle className="w-4 h-4" />
            </button>
          </div>
          <ul className="space-y-1 mt-2">
            {safetyWarnings.map((w, i) => (
              <li key={i} className={`text-xs leading-snug pl-7 ${isDark ? 'text-red-200' : 'text-red-800'}`}>
                • {w}
              </li>
            ))}
          </ul>
          {!overrideRecorded && (
            <p className={`text-[11px] pl-7 mt-2 ${isDark ? 'text-red-200/80' : 'text-red-700'}`}>
              Review the medication below: use <strong>Hold</strong> or <strong>Cancel</strong> if you wish
              to act on this warning, or open a new prescription with a recorded override reason.
            </p>
          )}
        </div>
      )}

      {showForm && (
        <PrescribePanel
          visitId={visitId}
          patient={patient}
          latestTriage={latestTriage}
          onSubmit={async (req, safety) => {
            await onSubmit(req, safety);
            // The handler already clears the form on success; we keep this
            // here for older parents that haven't migrated to the safety
            // signature yet.
            setShowForm(false);
          }}
          onClose={() => setShowForm(false)}
          formLoading={formLoading}
          glassCard={glassCard}
          glassInner={glassInner}
          isDark={isDark}
          text={text}
        />
      )}

      <div className="space-y-3">
        {medications.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <Pill className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={text.muted}>No medications prescribed yet</p>
          </div>
        ) : medications.map((med: MedicationResponse) => (
          <div key={med.id} className="rounded-2xl p-4" style={glassCard}>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${MEDICATION_STATUS_COLORS[med.status] || ''}`}>{med.status}</span>
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-slate-500 bg-slate-500/10`}>{med.route}</span>
              </div>
              <span className={`text-[10px] ${text.muted}`}>{med.prescribedAt ? format(new Date(med.prescribedAt), 'dd MMM HH:mm') : ''}</span>
            </div>
            <p className={`text-sm font-medium ${text.heading}`}>{med.drugName} {med.dose && `— ${med.dose}`}</p>
            {med.frequency && <p className={`text-xs ${text.body}`}>{med.frequency}</p>}
            {med.administeredByName && <p className={`text-[10px] mt-1 text-emerald-500`}>Administered by: {med.administeredByName}</p>}
            {med.countersignedByName && <p className={`text-[10px] text-violet-500`}>Countersigned by: {med.countersignedByName}</p>}
            {/* Actions */}
            <div className="flex items-center gap-2 mt-3">
              {med.status === 'PRESCRIBED' && (
                <button onClick={() => onAction(med.id, 'administer')} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors">
                  <CheckCircle2 className="w-3 h-3 inline mr-1" /> Administer
                </button>
              )}
              {med.status === 'ADMINISTERED' && !med.countersignedByName && (
                <button onClick={() => onAction(med.id, 'countersign')} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-violet-500/10 text-violet-500 hover:bg-violet-500/20 transition-colors">
                  <CheckCircle2 className="w-3 h-3 inline mr-1" /> Countersign
                </button>
              )}
            </div>
            <p className={`text-[10px] mt-2 ${text.muted}`}>Prescribed by: {med.prescribedByName}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════ MONITOR TAB ═══════
//
// Clinical-safety contract for this tab:
//   1. NEVER show "no monitoring data" when vital_signs has rows for this
//      visit. The previous version only read the IoT stream — if no IoT
//      device was paired (the common case), it showed an empty card even
//      though triage had recorded vitals. A doctor seeing that empty card
//      would believe vitals were never taken. This is a silent-failure
//      class and is unacceptable in this system.
//   2. Source priority for "current" reading, freshest first:
//        a) live IoT stream (paired device pushing samples)
//        b) most recent row in vital_signs (`latestVitals` from parent)
//      Whichever is fresher is shown as the live value, with the source
//      labelled so a clinician can tell "live monitor" from "last manual
//      reading at 14:32".
//   3. The history sparkline is built from `vitals` (the visit's full
//      vital_signs page) so a trend is visible without leaving the tab.
//   4. Paired-device summary is surfaced so a clinician can tell at a
//      glance whether they're looking at live monitoring or a stale
//      manual reading.
//
function MonitorTab({
  visit,
  vitals,
  latestVitals,
  glassCard,
  isDark,
  text,
}: {
  visit: VisitResponse;
  vitals: VitalSignsResponse[];
  latestVitals: VitalSignsResponse | null;
  glassCard: React.CSSProperties;
  isDark: boolean;
  text: any;
}) {
  const visitId = visit.id;
  const patientId = visit.patientId;

  // ── IoT live stream (best-effort; absence is fine, falls back to vital_signs) ──
  const [streamData, setStreamData] = useState<any[]>([]);
  const [streamLoading, setStreamLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setStreamLoading(true);
    iotApi
      .getRecentStream(visitId, 30)
      .then((recent) => {
        if (!cancelled) setStreamData(Array.isArray(recent) ? recent : []);
      })
      .catch(() => {
        if (!cancelled) setStreamData([]);
      })
      .finally(() => {
        if (!cancelled) setStreamLoading(false);
      });
    return () => { cancelled = true; };
  }, [visitId]);

  // ── Paired devices (for the connection-status header) ──
  const pairedDevices = useDeviceStore((s) => s.getDevicesForPatient(patientId));
  const deviceSummary = useDeviceStore((s) => s.getPatientDeviceSummary(patientId));
  const activeDeviceCount = pairedDevices.filter(
    (d) => d.connectionStatus === 'CONNECTED' && d.isStreaming,
  ).length;
  const hasActiveDevice = activeDeviceCount > 0;

  // ── Resolve the "current reading" using the source-priority rules above ──
  const liveStreamLatest = streamData.length > 0 ? streamData[streamData.length - 1] : null;
  const liveStreamTime = liveStreamLatest?.timestamp ? new Date(liveStreamLatest.timestamp).getTime() : 0;
  const recordedTime = latestVitals?.recordedAt ? new Date(latestVitals.recordedAt).getTime() : 0;

  // Stream wins if it has a sample within the last 2 minutes — anything
  // older is functionally a stale frame, the recorded value is more honest.
  const TWO_MIN_MS = 2 * 60 * 1000;
  const streamIsFresh = liveStreamLatest != null && (Date.now() - liveStreamTime) < TWO_MIN_MS;
  const useStream = streamIsFresh && liveStreamTime >= recordedTime;

  type DisplayVitals = {
    heartRate: number | null;
    spo2: number | null;
    respiratoryRate: number | null;
    temperature: number | null;
    systolicBp: number | null;
    diastolicBp: number | null;
    timestamp: string | null;
    sourceLabel: string;
    sourceColor: string;
  };

  const display: DisplayVitals | null = useMemo(() => {
    if (useStream && liveStreamLatest) {
      return {
        heartRate: liveStreamLatest.heartRate ?? null,
        spo2: liveStreamLatest.spo2 ?? null,
        respiratoryRate: liveStreamLatest.respiratoryRate ?? null,
        temperature: liveStreamLatest.temperature ?? null,
        systolicBp: liveStreamLatest.systolicBp ?? liveStreamLatest.systolicBP ?? null,
        diastolicBp: liveStreamLatest.diastolicBp ?? liveStreamLatest.diastolicBP ?? null,
        timestamp: liveStreamLatest.timestamp ?? null,
        sourceLabel: 'LIVE — IoT device',
        sourceColor: 'text-emerald-500',
      };
    }
    if (latestVitals) {
      return {
        heartRate: latestVitals.heartRate ?? null,
        spo2: latestVitals.spo2 ?? null,
        respiratoryRate: latestVitals.respiratoryRate ?? null,
        temperature: latestVitals.temperature ?? null,
        systolicBp: latestVitals.systolicBp ?? null,
        diastolicBp: latestVitals.diastolicBp ?? null,
        timestamp: latestVitals.recordedAt ?? null,
        // Truthful labels — clinicians should never confuse a recorded
        // value with a live monitor reading.
        sourceLabel: streamIsFresh
          ? 'Recorded (newer than live stream)'
          : 'Last recorded vitals',
        sourceColor: 'text-cyan-500',
      };
    }
    return null;
  }, [useStream, liveStreamLatest, latestVitals, streamIsFresh]);

  // ── History for sparkline (oldest-first, with timestamps for tooltip) ──
  const history = useMemo(() => {
    return [...vitals]
      .filter((v) => v.recordedAt)
      .sort((a, b) => new Date(a.recordedAt).getTime() - new Date(b.recordedAt).getTime())
      .slice(-30)
      .map((v) => ({
        t: format(new Date(v.recordedAt), 'HH:mm'),
        heartRate: v.heartRate,
        spo2: v.spo2,
        respiratoryRate: v.respiratoryRate,
        temperature: v.temperature,
      }));
  }, [vitals]);

  if (streamLoading && !latestVitals) {
    return <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-cyan-500" /></div>;
  }

  // True empty state — only when neither IoT nor vital_signs has anything.
  // Displayed differently from "we have recorded but no live device" so a
  // clinician can tell what's actually missing.
  if (!display) {
    return (
      <div className="space-y-4">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Real-Time Monitor</h3>
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <Monitor className="w-8 h-8 mx-auto mb-2 text-slate-400" />
          <p className={text.muted}>No vitals on record for this visit yet</p>
          <p className={`text-xs mt-1 ${text.muted}`}>
            Vitals appear here as soon as triage is recorded or an IoT device pairs.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* ── Header with truthful connection-status indicator ── */}
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Real-Time Monitor</h3>
        <div className="flex items-center gap-2">
          <span className={`flex items-center gap-1.5 text-[11px] font-semibold px-2.5 py-1 rounded-lg border ${
            hasActiveDevice
              ? 'text-emerald-600 bg-emerald-500/10 border-emerald-500/30'
              : pairedDevices.length > 0
                ? 'text-amber-600 bg-amber-500/10 border-amber-500/30'
                : 'text-slate-500 bg-slate-500/10 border-slate-500/20'
          }`}>
            {hasActiveDevice ? <Wifi className="w-3 h-3" /> : <WifiOff className="w-3 h-3" />}
            {hasActiveDevice
              ? `Live · ${activeDeviceCount} device${activeDeviceCount > 1 ? 's' : ''}`
              : pairedDevices.length > 0 ? 'Device paired · not streaming' : 'No device paired'}
          </span>
          {pairedDevices.length > 0 && deviceSummary.lowestBattery < 30 && (
            <span className="flex items-center gap-1 text-[11px] font-semibold px-2 py-1 rounded-lg border text-amber-600 bg-amber-500/10 border-amber-500/30">
              <BatteryWarning className="w-3 h-3" />
              {Math.round(deviceSummary.lowestBattery)}%
            </span>
          )}
        </div>
      </div>

      {/* ── Source label — clinicians must always know whether this is live or recorded ── */}
      <div className="flex items-center gap-2 text-xs">
        <span className={`font-bold uppercase tracking-wider ${display.sourceColor}`}>{display.sourceLabel}</span>
        {display.timestamp && (
          <span className={text.muted}>· {format(new Date(display.timestamp), 'dd MMM HH:mm')}</span>
        )}
      </div>

      {/* ── Current reading — same tile layout as the IoT-only version,
              but driven by the source-priority rules above ── */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        <div className="rounded-2xl p-4 text-center" style={glassCard}>
          <Heart className="w-5 h-5 mx-auto mb-1 text-red-500" />
          <p className="text-2xl font-black text-red-500">{display.heartRate ?? '—'}</p>
          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Heart Rate</p>
        </div>
        <div className="rounded-2xl p-4 text-center" style={glassCard}>
          <Droplets className="w-5 h-5 mx-auto mb-1 text-cyan-500" />
          <p className="text-2xl font-black text-cyan-500">{display.spo2 != null ? `${display.spo2}%` : '—'}</p>
          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>SpO2</p>
        </div>
        <div className="rounded-2xl p-4 text-center" style={glassCard}>
          <Wind className="w-5 h-5 mx-auto mb-1 text-blue-500" />
          <p className="text-2xl font-black text-blue-500">{display.respiratoryRate ?? '—'}</p>
          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Resp Rate</p>
        </div>
        <div className="rounded-2xl p-4 text-center" style={glassCard}>
          <Activity className="w-5 h-5 mx-auto mb-1 text-violet-500" />
          <p className="text-2xl font-black text-violet-500">
            {display.systolicBp != null && display.diastolicBp != null ? `${display.systolicBp}/${display.diastolicBp}` : '—'}
          </p>
          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Blood Pressure</p>
        </div>
        <div className="rounded-2xl p-4 text-center" style={glassCard}>
          <Thermometer className="w-5 h-5 mx-auto mb-1 text-amber-500" />
          <p className="text-2xl font-black text-amber-500">{display.temperature != null ? `${display.temperature}°C` : '—'}</p>
          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Temp</p>
        </div>
      </div>

      {/* ── Trend sparklines from vital_signs history ── */}
      {history.length >= 2 && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <SparkBlock label="Heart Rate" data={history} dataKey="heartRate" stroke="#ef4444" glassCard={glassCard} text={text} />
          <SparkBlock label="SpO2 (%)" data={history} dataKey="spo2" stroke="#06b6d4" glassCard={glassCard} text={text} />
          <SparkBlock label="Resp Rate" data={history} dataKey="respiratoryRate" stroke="#3b82f6" glassCard={glassCard} text={text} />
          <SparkBlock label="Temperature (°C)" data={history} dataKey="temperature" stroke="#f59e0b" glassCard={glassCard} text={text} />
        </div>
      )}

      {/* ── Compact paired-device summary ── */}
      {pairedDevices.length > 0 && (
        <div className="rounded-2xl p-4 border border-slate-200/30" style={glassCard}>
          <div className="flex items-center gap-2 mb-3">
            <MonitorSmartphone className="w-4 h-4 text-cyan-500" />
            <h4 className={`text-sm font-bold ${text.heading}`}>Paired Devices ({pairedDevices.length})</h4>
            <span className={`ml-auto text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-md border ${
              deviceSummary.overallHealth === 'HEALTHY' ? 'text-emerald-600 bg-emerald-500/10 border-emerald-500/30' :
              deviceSummary.overallHealth === 'WARNING' ? 'text-amber-600 bg-amber-500/10 border-amber-500/30' :
              'text-red-600 bg-red-500/10 border-red-500/30'
            }`}>
              {deviceSummary.overallHealth === 'HEALTHY' ? 'All healthy' :
               deviceSummary.overallHealth === 'WARNING' ? 'Needs attention' : 'Critical'}
            </span>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            {pairedDevices.map((d) => (
              <div key={d.id} className="flex items-center gap-2 text-xs px-3 py-2 rounded-lg border border-slate-200/20">
                {d.connectionStatus === 'CONNECTED' ? (
                  <Wifi className="w-3.5 h-3.5 text-emerald-500" />
                ) : (
                  <WifiOff className="w-3.5 h-3.5 text-slate-400" />
                )}
                <span className={`font-semibold ${text.heading} truncate flex-1`}>{d.name}</span>
                <span className={text.muted}>{Math.round(d.health.batteryPercent)}%</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// Tiny sparkline block — single-vital trend at-a-glance, reused for HR/SpO2/RR/Temp.
function SparkBlock({
  label, data, dataKey, stroke, glassCard, text,
}: {
  label: string;
  data: any[];
  dataKey: string;
  stroke: string;
  glassCard: React.CSSProperties;
  text: any;
}) {
  return (
    <div className="rounded-2xl p-3" style={glassCard}>
      <p className={`text-[10px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>{label}</p>
      <ResponsiveContainer width="100%" height={60}>
        <AreaChart data={data} margin={{ top: 0, right: 0, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id={`grad-${dataKey}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={stroke} stopOpacity={0.4} />
              <stop offset="100%" stopColor={stroke} stopOpacity={0.05} />
            </linearGradient>
          </defs>
          <XAxis dataKey="t" hide />
          <YAxis hide domain={['auto', 'auto']} />
          <Tooltip
            contentStyle={{ fontSize: 11, padding: '4px 8px', borderRadius: 8 }}
            labelStyle={{ fontWeight: 'bold' }}
          />
          <Area type="monotone" dataKey={dataKey} stroke={stroke} strokeWidth={2} fill={`url(#grad-${dataKey})`} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

// ═══════ ALERTS TAB ═══════
function AlertsTab({ alerts, onAcknowledge, glassCard, glassInner, isDark, text }: any) {
  const severityColors: Record<string, string> = {
    CRITICAL: 'text-red-500 bg-red-500/10 border-red-500/20',
    HIGH: 'text-orange-500 bg-orange-500/10 border-orange-500/20',
    MEDIUM: 'text-amber-500 bg-amber-500/10 border-amber-500/20',
    LOW: 'text-blue-500 bg-blue-500/10 border-blue-500/20',
    INFO: 'text-slate-500 bg-slate-500/10 border-slate-500/20',
  };

  return (
    <div className="space-y-4">
      <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Clinical Alerts ({alerts.length})</h3>
      <div className="space-y-3">
        {alerts.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <BellRing className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={text.muted}>No alerts for this visit</p>
          </div>
        ) : alerts.map((a: ClinicalAlertResponse) => (
          <div key={a.id} className={`rounded-2xl p-4 border ${a.acknowledged ? 'opacity-60' : ''}`} style={glassCard}>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg border ${severityColors[a.severity] || ''}`}>
                  {a.severity}
                </span>
                <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>{a.alertType?.replace(/_/g, ' ')}</span>
              </div>
              <span className={`text-[10px] ${text.muted}`}>{a.createdAt ? format(new Date(a.createdAt), 'dd MMM HH:mm') : ''}</span>
            </div>
            <p className={`text-sm ${text.heading}`}>{a.message}</p>
            {a.title && <p className={`text-xs mt-1 ${text.body}`}>{a.title}</p>}
            {!a.acknowledged && (
              <button onClick={() => onAcknowledge(a.id)} className="mt-3 px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors">
                <CheckCircle2 className="w-3 h-3 inline mr-1" /> Acknowledge
              </button>
            )}
            {a.acknowledged && (
              <p className={`text-[10px] mt-2 text-emerald-500`}>Acknowledged by {a.acknowledgedByName} at {a.acknowledgedAt ? format(new Date(a.acknowledgedAt), 'dd MMM HH:mm') : ''}</p>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════ HELPER COMPONENTS ═══════

// EditableMedicalRow — inline editor for free-text safety-critical fields
// (allergies, chronic conditions). Click pencil to edit → textarea + save/cancel.
//
// Save is async and surfaces errors inline rather than dismissing silently.
// While saving the row shows a spinner; the textarea is disabled to prevent
// double-submission. On failure, the editor stays open with the unsaved text
// so the clinician can retry — losing typed-but-failed updates would be
// exactly the kind of silent data loss this whole editor exists to fix.
function EditableMedicalRow({
  label, value, onSave, isDark, glassInner, text, accent, placeholder,
}: {
  label: string;
  value: string | null | undefined;
  onSave: (next: string) => Promise<void>;
  isDark: boolean;
  glassInner: React.CSSProperties;
  text: any;
  accent: 'red' | 'amber';
  placeholder?: string;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const display = !value || !value.trim() ? 'None on record' : value;
  const isEmpty = !value || !value.trim();

  const handleStartEdit = () => {
    setDraft(value ?? '');
    setError(null);
    setEditing(true);
  };

  const handleCancel = () => {
    setEditing(false);
    setError(null);
    setDraft(value ?? '');
  };

  const handleSubmit = async () => {
    setSaving(true);
    setError(null);
    try {
      await onSave(draft.trim());
      setEditing(false);
    } catch (err) {
      setError((err as Error)?.message || 'Failed to save. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  if (editing) {
    return (
      <div className="py-2.5 border-b border-slate-200/10 last:border-0">
        <div className="flex items-center justify-between mb-1.5">
          <span className="text-sm text-slate-500 font-medium">{label}</span>
          <span className={`text-[10px] font-bold uppercase tracking-wider ${
            accent === 'red' ? 'text-red-600' : 'text-amber-600'
          }`}>
            Editing
          </span>
        </div>
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={placeholder}
          rows={2}
          disabled={saving}
          autoFocus
          className={`w-full px-3 py-2 rounded-lg text-sm outline-none resize-none ${
            isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
          } disabled:opacity-50`}
          style={glassInner}
        />
        {error && <p className="text-[11px] text-red-500 font-medium mt-1.5">{error}</p>}
        <div className="flex items-center gap-2 mt-2">
          <button
            type="button"
            onClick={handleSubmit}
            disabled={saving || draft === (value ?? '')}
            className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg text-white transition-all disabled:opacity-50 disabled:cursor-not-allowed ${
              accent === 'red' ? 'bg-red-600 hover:bg-red-700' : 'bg-amber-600 hover:bg-amber-700'
            }`}
          >
            {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Save className="w-3 h-3" />}
            Save
          </button>
          <button
            type="button"
            onClick={handleCancel}
            disabled={saving}
            className={`px-3 py-1.5 text-[11px] font-bold rounded-lg ${text.muted} hover:bg-white/5 disabled:opacity-50`}
          >
            Cancel
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-start justify-between py-2.5 group" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.08)' : '1px solid rgba(203,213,225,0.2)' }}>
      <span className="text-sm text-slate-500 font-medium">{label}</span>
      <div className="flex items-start gap-2 max-w-[60%]">
        <span className={`text-sm font-semibold text-right ${
          isEmpty ? 'text-slate-400 italic' : isDark ? 'text-white' : 'text-slate-800'
        }`}>
          {display}
        </span>
        <button
          type="button"
          onClick={handleStartEdit}
          className={`flex-shrink-0 p-1 rounded ${text.muted} opacity-50 group-hover:opacity-100 hover:bg-white/5 transition-opacity`}
          aria-label={`Edit ${label}`}
          title={`Edit ${label}`}
        >
          <Pencil className="w-3 h-3" />
        </button>
      </div>
    </div>
  );
}

function InfoRow({ label, value, isDark }: { label: string; value: string; isDark: boolean }) {
  return (
    <div className="flex items-center justify-between py-1.5" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.08)' : '1px solid rgba(203,213,225,0.2)' }}>
      <span className={`text-xs font-medium ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{label}</span>
      <span className={`text-xs font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{value}</span>
    </div>
  );
}

function VitalTile({ icon: Icon, label, value, color, bg, glassInner, isDark }: any) {
  return (
    <div className="rounded-xl p-3" style={glassInner}>
      <div className={`w-7 h-7 rounded-lg ${bg} flex items-center justify-center mb-1.5`}>
        <Icon className={`w-3.5 h-3.5 ${color}`} />
      </div>
      <p className={`text-xs font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{value}</p>
      <p className={`text-[10px] ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{label}</p>
    </div>
  );
}

function StatCard({ label, count, color, bg, glassInner, isDark }: any) {
  return (
    <div className="rounded-xl p-3 text-center" style={glassInner}>
      <p className={`text-xl font-black ${color}`}>{count}</p>
      <p className={`text-[10px] font-bold uppercase tracking-wider ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{label}</p>
    </div>
  );
}

/* ─────────────────────────────────────────────────────────────
   Disposition Tab — Record patient's final ED outcome
   ───────────────────────────────────────────────────────────── */
const DISPOSITION_OPTIONS: { value: DispositionType; label: string; description: string; color: string }[] = [
  { value: 'DISCHARGED_HOME', label: 'Discharge Home', description: 'Patient is stable and safe for home discharge', color: 'text-emerald-500' },
  { value: 'ADMITTED_TO_WARD', label: 'Admit to Ward', description: 'Patient requires inpatient admission', color: 'text-blue-500' },
  { value: 'ICU_ADMISSION', label: 'ICU Admission', description: 'Patient needs intensive care', color: 'text-red-500' },
  { value: 'TRANSFERRED', label: 'Transfer', description: 'Transfer to another facility', color: 'text-purple-500' },
  { value: 'LEFT_AGAINST_MEDICAL_ADVICE', label: 'Left AMA', description: 'Patient left against medical advice', color: 'text-amber-500' },
  { value: 'LEFT_WITHOUT_BEING_SEEN', label: 'LWBS', description: 'Patient left without being seen', color: 'text-slate-500' },
  { value: 'DECEASED', label: 'Deceased', description: 'Patient declared deceased in the ED', color: 'text-slate-700' },
];

function DispositionTab({ visit, onDisposition, formLoading, glassCard, glassInner, isDark, text }: {
  visit: VisitResponse;
  onDisposition: (data: DispositionRequest) => Promise<void>;
  formLoading: boolean;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}) {
  const [selectedType, setSelectedType] = useState<DispositionType | null>(visit.dispositionType);
  const [notes, setNotes] = useState(visit.dispositionNotes || '');
  const [ward, setWard] = useState('');
  const [facility, setFacility] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);

  const isAlreadyDisposed = !!visit.dispositionType;

  const handleSubmit = async () => {
    if (!selectedType) return;
    await onDisposition({
      dispositionType: selectedType,
      notes: notes || undefined,
      destinationWard: ward || undefined,
      receivingFacility: facility || undefined,
    });
    setConfirmOpen(false);
  };

  return (
    <div className="space-y-4">
      {/* Already disposed banner */}
      {isAlreadyDisposed && (
        <div className="rounded-2xl p-4 border border-emerald-500/30 bg-emerald-500/10">
          <div className="flex items-center gap-2 mb-1">
            <CheckCircle2 className="w-5 h-5 text-emerald-500" />
            <h3 className={`font-bold ${isDark ? 'text-emerald-400' : 'text-emerald-600'}`}>
              Disposition Recorded
            </h3>
          </div>
          <p className={`text-sm ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>
            {DISPOSITION_OPTIONS.find(o => o.value === visit.dispositionType)?.label || visit.dispositionType}
            {visit.dispositionTime && ` — ${format(new Date(visit.dispositionTime), 'dd MMM yyyy HH:mm')}`}
          </p>
          {visit.dispositionNotes && (
            <p className={`text-xs mt-1 ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{visit.dispositionNotes}</p>
          )}
        </div>
      )}

      {/* Disposition type selection */}
      <div className="rounded-2xl p-5" style={glassCard}>
        <h3 className={`font-bold mb-3 ${text.primary}`}>
          {isAlreadyDisposed ? 'Disposition Details' : 'Select Disposition'}
        </h3>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          {DISPOSITION_OPTIONS.map(opt => (
            <button
              key={opt.value}
              onClick={() => !isAlreadyDisposed && setSelectedType(opt.value)}
              disabled={isAlreadyDisposed}
              className={`p-3 rounded-xl border text-left transition-all ${
                selectedType === opt.value
                  ? isDark ? 'border-cyan-500/50 bg-cyan-500/10' : 'border-blue-500/50 bg-blue-50'
                  : isDark ? 'border-slate-700/50 hover:border-slate-600/50' : 'border-slate-200 hover:border-slate-300'
              } ${isAlreadyDisposed ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer'}`}
              style={glassInner}
            >
              <p className={`text-sm font-bold ${opt.color}`}>{opt.label}</p>
              <p className={`text-[10px] ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{opt.description}</p>
            </button>
          ))}
        </div>
      </div>

      {/* Conditional fields */}
      {!isAlreadyDisposed && (
        <div className="rounded-2xl p-5 space-y-3" style={glassCard}>
          {selectedType === 'ADMITTED_TO_WARD' && (
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Destination Ward</label>
              <input value={ward} onChange={e => setWard(e.target.value)} placeholder="e.g. Medical Ward 3"
                className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner} />
            </div>
          )}
          {selectedType === 'TRANSFERRED' && (
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Receiving Facility</label>
              <input value={facility} onChange={e => setFacility(e.target.value)} placeholder="e.g. Kenyatta National Hospital"
                className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner} />
            </div>
          )}
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Disposition Notes</label>
            <textarea value={notes} onChange={e => setNotes(e.target.value)} rows={3} placeholder="Clinical notes for disposition decision..."
              className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
              style={glassInner} />
          </div>

          {/* Confirm button */}
          {!confirmOpen ? (
            <button
              onClick={() => setConfirmOpen(true)}
              disabled={!selectedType || formLoading}
              className="w-full py-3 rounded-xl font-bold text-sm text-white bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-600 hover:to-blue-700 disabled:opacity-40 transition-all"
            >
              Record Disposition
            </button>
          ) : (
            <div className="border border-amber-500/30 bg-amber-500/10 rounded-xl p-4 space-y-3">
              <div className="flex items-center gap-2">
                <AlertTriangle className="w-5 h-5 text-amber-500" />
                <p className={`text-sm font-bold ${isDark ? 'text-amber-400' : 'text-amber-600'}`}>Confirm Disposition</p>
              </div>
              <p className={`text-xs ${isDark ? 'text-slate-300' : 'text-slate-600'}`}>
                This will set the final outcome for this visit, stop any active monitoring, and cannot be easily reversed.
              </p>
              <div className="flex gap-2">
                <button onClick={handleSubmit} disabled={formLoading}
                  className="flex-1 py-2.5 rounded-xl font-bold text-sm text-white bg-gradient-to-r from-red-500 to-rose-600 hover:from-red-600 hover:to-rose-700 disabled:opacity-40 transition-all flex items-center justify-center gap-2"
                >
                  {formLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
                  Confirm
                </button>
                <button onClick={() => setConfirmOpen(false)}
                  className={`flex-1 py-2.5 rounded-xl font-bold text-sm transition-all ${isDark ? 'text-slate-300 hover:bg-slate-700/50' : 'text-slate-600 hover:bg-slate-100'}`}
                  style={glassInner}
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function MiniVital({ label, value, unit, isDark }: { label: string; value: string; unit: string; isDark: boolean }) {
  return (
    <div>
      <p className={`text-[10px] font-bold uppercase tracking-wider ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>{label}</p>
      <p className={`text-xs font-bold ${isDark ? 'text-white' : 'text-slate-800'}`}>{value} <span className={`font-normal ${isDark ? 'text-slate-500' : 'text-slate-400'}`}>{unit}</span></p>
    </div>
  );
}

function FormInput({ label, value, onChange, glassInner, isDark, text }: any) {
  return (
    <div>
      <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>{label}</label>
      <input
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
        style={glassInner}
      />
    </div>
  );
}

// ═══════ PATIENT SAFETY BANNER ═══════
//
// Compact, always-on chip strip carrying the patient facts a doctor must
// not lose sight of:
//   - Allergies (red, prominent — drives the prescribing safety check)
//   - Weight in kg (drives pediatric mg/kg dosing; null is honestly stated)
//   - Pregnancy status (drives teratogen safety; "needs confirmation"
//     surfaces when the value is a synthetic default)
//   - Age + pediatric flag, blood type, chronic conditions
//
// Contract:
//   - When patient is null (profile fetch failed), this banner does not
//     fall back to "no allergies" — it shows an explicit warning that the
//     profile is unavailable and the doctor must verify before prescribing.
//     Silent fallback would defeat the whole purpose of the banner.
//   - NOT_APPLICABLE pregnancy is hidden (it's noise for non-female patients
//     and would crowd out the warnings that actually matter).
//
function PatientSafetyBanner({
  patient,
  latestTriage,
  isDark,
}: {
  patient: PatientResponse | null;
  latestTriage: TriageRecordResponse | null;
  isDark: boolean;
}) {
  // Degraded / unsafe fallback — the doctor must see this is not a "clean" record.
  if (!patient) {
    return (
      <div className={`px-4 py-2.5 flex items-center gap-2 border-t ${isDark ? 'bg-amber-500/10 border-amber-500/20' : 'bg-amber-50 border-amber-200'}`}>
        <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0" />
        <span className={`text-xs font-semibold ${isDark ? 'text-amber-300' : 'text-amber-800'}`}>
          Patient profile unavailable — verify allergies and weight with the patient before prescribing.
        </span>
      </div>
    );
  }

  const allergies = (patient.knownAllergies || '').trim();
  const hasAllergies = allergies.length > 0;
  const chronicConditions = (patient.chronicConditions || '').trim();
  const weightKg = latestTriage?.weightKg ?? null;
  const showPregnancy = patient.pregnancyStatus && patient.pregnancyStatus !== 'NOT_APPLICABLE';
  const pregnancyNeedsConfirm = showPregnancy && patient.pregnancyStatusRecordedAt == null;
  const PREG_LABEL: Record<PregnancyStatus, string> = {
    PREGNANT: 'Pregnant',
    BREASTFEEDING: 'Breastfeeding',
    POSSIBLY_PREGNANT: 'Possibly pregnant',
    NOT_PREGNANT: 'Not pregnant',
    NOT_APPLICABLE: 'N/A',
    UNKNOWN: 'Pregnancy: Unknown',
  };

  return (
    <div
      className={`px-4 py-2 flex flex-wrap items-center gap-2 border-t ${
        hasAllergies
          ? isDark ? 'bg-red-500/10 border-red-500/20' : 'bg-red-50 border-red-200'
          : isDark ? 'bg-slate-500/5 border-white/5' : 'bg-slate-50 border-slate-200'
      }`}
    >
      {/* Allergies — always shown; red if any, neutral otherwise. NEVER hidden. */}
      <span className={`flex items-center gap-1.5 text-[11px] font-bold px-2.5 py-1 rounded-lg border ${
        hasAllergies
          ? 'text-red-600 bg-red-100 border-red-300'
          : isDark ? 'text-slate-400 bg-slate-500/10 border-white/10' : 'text-slate-500 bg-white border-slate-200'
      }`}>
        <AlertTriangle className="w-3 h-3" />
        {hasAllergies ? `Allergies: ${allergies}` : 'No allergies on record'}
      </span>

      {/* Pregnancy — drives teratogen safety check. Hidden for NOT_APPLICABLE
          to avoid noise; never silently dropped for FEMALE/OTHER/UNKNOWN. */}
      {showPregnancy && (
        <span className={`flex items-center gap-1.5 text-[11px] font-bold px-2.5 py-1 rounded-lg border ${
          patient.pregnancyStatus === 'PREGNANT' || patient.pregnancyStatus === 'BREASTFEEDING' || patient.pregnancyStatus === 'POSSIBLY_PREGNANT'
            ? 'text-violet-600 bg-violet-100 border-violet-300'
            : 'text-slate-500 bg-white border-slate-200'
        }`}>
          {PREG_LABEL[patient.pregnancyStatus as PregnancyStatus]}
          {pregnancyNeedsConfirm && <span className="text-amber-600 font-bold">· confirm</span>}
        </span>
      )}

      {/* Weight (latest triage) — required for pediatric mg/kg dosing. */}
      <span className={`text-[11px] font-semibold px-2.5 py-1 rounded-lg border ${isDark ? 'text-slate-300 bg-slate-500/10 border-white/10' : 'text-slate-700 bg-white border-slate-200'}`}>
        Weight: {weightKg != null ? `${weightKg} kg` : '—'}
      </span>

      {/* Age + pediatric flag */}
      <span className={`text-[11px] font-semibold px-2.5 py-1 rounded-lg border ${
        patient.isPediatric
          ? 'text-blue-600 bg-blue-100 border-blue-300'
          : isDark ? 'text-slate-300 bg-slate-500/10 border-white/10' : 'text-slate-700 bg-white border-slate-200'
      }`}>
        {patient.ageInYears != null && patient.ageInYears >= 0 ? `${patient.ageInYears}y` : 'Age —'}
        {patient.isPediatric && ' · Pediatric'}
        {patient.gender ? ` · ${patient.gender[0]}` : ''}
      </span>

      {/* Blood type */}
      {patient.bloodType && (
        <span className={`text-[11px] font-bold px-2.5 py-1 rounded-lg border text-rose-600 bg-rose-50 border-rose-200`}>
          {patient.bloodType}
        </span>
      )}

      {/* Chronic conditions — compact, amber if any */}
      {chronicConditions.length > 0 && (
        <span className={`text-[11px] font-semibold px-2.5 py-1 rounded-lg border text-amber-700 bg-amber-50 border-amber-200 truncate max-w-md`} title={chronicConditions}>
          Chronic: {chronicConditions}
        </span>
      )}

      {/* MRN — last, low priority */}
      {patient.medicalRecordNumber && (
        <span className={`ml-auto text-[10px] font-mono ${isDark ? 'text-slate-500' : 'text-slate-400'}`}>
          MRN {patient.medicalRecordNumber}
        </span>
      )}
    </div>
  );
}
