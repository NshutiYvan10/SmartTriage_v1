/* ═══════════════════════════════════════════════════════════════
   Visit Detail Page — Full Clinical Workspace
   Tabs: Overview, Vitals, Triage, Notes, Diagnoses,
         Investigations, Medications, Monitor, Alerts
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Activity, FileText, Stethoscope, ClipboardList,
  FlaskConical, Pill, Monitor, BellRing, Heart, Thermometer,
  Wind, Droplets, Brain, Clock, User, AlertTriangle, ChevronRight,
  Plus, Send, CheckCircle2, XCircle, Eye, Loader2, RefreshCw, LogOut,
} from 'lucide-react';
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
import type {
  VisitResponse, VitalSignsResponse, TriageRecordResponse,
  ClinicalNoteResponse, DiagnosisResponse, InvestigationResponse,
  MedicationResponse, ClinicalAlertResponse,
  RecordVitalsRequest, CreateClinicalNoteRequest, CreateDiagnosisRequest,
  OrderInvestigationRequest, PrescribeMedicationRequest,
  NoteType, DiagnosisType, InvestigationType, MedicationRoute,
  AvpuScore, TriageCategory, DispositionType,
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
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);

  const [activeTab, setActiveTab] = useState<TabId>('overview');
  const [loading, setLoading] = useState(true);

  // Data
  const [visit, setVisit] = useState<VisitResponse | null>(null);
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

  const handlePrescribeMedication = async (data: Partial<PrescribeMedicationRequest>) => {
    setFormLoading(true);
    try {
      await medicationApi.prescribe({ visitId: visit.id, prescribedByName: userName, ...data } as PrescribeMedicationRequest);
      setShowMedicationForm(false);
      loadData();
    } catch (err) { console.error(err); } finally { setFormLoading(false); }
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
          {activeTab === 'overview' && <OverviewTab visit={visit} latestVitals={latestVitals} latestTriage={latestTriage} notes={notes} diagnoses={diagnoses} investigations={investigations} medications={medications} alerts={visitAlerts} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'vitals' && <VitalsTab vitals={vitals} latestVitals={latestVitals} showForm={showVitalsForm} setShowForm={setShowVitalsForm} onSubmit={handleRecordVitals} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'triage' && <TriageTab visit={visit} triageHistory={triageHistory} latestTriage={latestTriage} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} navigate={navigate} />}
          {activeTab === 'notes' && <NotesTab notes={notes} showForm={showNoteForm} setShowForm={setShowNoteForm} onSubmit={handleCreateNote} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'diagnoses' && <DiagnosesTab diagnoses={diagnoses} showForm={showDiagnosisForm} setShowForm={setShowDiagnosisForm} onSubmit={handleCreateDiagnosis} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'investigations' && <InvestigationsTab investigations={investigations} showForm={showInvestigationForm} setShowForm={setShowInvestigationForm} onSubmit={handleOrderInvestigation} onAction={handleInvestigationAction} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} userName={userName} />}
          {activeTab === 'medications' && <MedicationsTab medications={medications} showForm={showMedicationForm} setShowForm={setShowMedicationForm} onSubmit={handlePrescribeMedication} onAction={handleMedicationAction} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'monitor' && <MonitorTab visitId={visit.id} glassCard={glassCard} isDark={isDark} text={text} />}
          {activeTab === 'alerts' && <AlertsTab alerts={visitAlerts} onAcknowledge={handleAcknowledgeAlert} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'disposition' && <DispositionTab visit={visit} onDisposition={handleRecordDisposition} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
        </div>
      </div>
    </div>
  );
}

// ═══════ OVERVIEW TAB ═══════
function OverviewTab({ visit, latestVitals, latestTriage, notes, diagnoses, investigations, medications, alerts, glassCard, glassInner, isDark, text }: any) {
  const unackAlerts = alerts.filter((a: ClinicalAlertResponse) => !a.acknowledged).length;

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
            <InfoRow label="Time" value={latestTriage.triageTime ? format(new Date(latestTriage.triageTime), 'dd MMM yyyy HH:mm') : '—'} isDark={isDark} />
          </div>
        ) : (
          <p className={`text-sm ${text.muted}`}>Not yet triaged</p>
        )}
      </div>

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
function VitalsTab({ vitals, latestVitals, showForm, setShowForm, onSubmit, formLoading, glassCard, glassInner, isDark, text }: any) {
  const [form, setForm] = useState<Partial<RecordVitalsRequest>>({
    heartRate: undefined, respiratoryRate: undefined, systolicBp: undefined,
    diastolicBp: undefined, temperature: undefined, spo2: undefined,
    avpu: 'ALERT' as AvpuScore, painScore: undefined, gcsScore: 15,
    bloodGlucose: undefined, source: 'MANUAL_ENTRY', notes: '',
  });

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Vital Signs History</h3>
        <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
          <Plus className="w-3.5 h-3.5" /> Record Vitals
        </button>
      </div>

      {showForm && (
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Record New Vital Signs</h4>
          <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
            <FormInput label="Heart Rate (bpm)" value={form.heartRate} onChange={(v: string) => setForm({ ...form, heartRate: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="Resp Rate (/min)" value={form.respiratoryRate} onChange={(v: string) => setForm({ ...form, respiratoryRate: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="SpO2 (%)" value={form.spo2} onChange={(v: string) => setForm({ ...form, spo2: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="Systolic BP" value={form.systolicBp} onChange={(v: string) => setForm({ ...form, systolicBp: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="Diastolic BP" value={form.diastolicBp} onChange={(v: string) => setForm({ ...form, diastolicBp: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="Temperature (°C)" value={form.temperature} onChange={(v: string) => setForm({ ...form, temperature: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="Pain Score (0-10)" value={form.painScore} onChange={(v: string) => setForm({ ...form, painScore: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="GCS (3-15)" value={form.gcsScore} onChange={(v: string) => setForm({ ...form, gcsScore: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="Blood Glucose" value={form.bloodGlucose} onChange={(v: string) => setForm({ ...form, bloodGlucose: v ? Number(v) : undefined })} glassInner={glassInner} isDark={isDark} text={text} />
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>AVPU</label>
              <select value={form.avpu || 'ALERT'} onChange={(e) => setForm({ ...form, avpu: e.target.value as AvpuScore })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                {['ALERT', 'CONFUSED', 'VERBAL', 'PAIN', 'UNRESPONSIVE'].map(v => <option key={v} value={v}>{v}</option>)}
              </select>
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => onSubmit(form)} disabled={formLoading} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
              {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Save Vitals
            </button>
            <button onClick={() => setShowForm(false)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
          </div>
        </div>
      )}

      {/* History */}
      <div className="space-y-3">
        {vitals.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <Activity className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={text.muted}>No vital signs recorded yet</p>
          </div>
        ) : vitals.map((v: VitalSignsResponse) => (
          <div key={v.id} className="rounded-2xl p-4" style={glassCard}>
            <div className="flex items-center justify-between mb-3">
              <span className={`text-xs font-bold ${text.accent}`}>{v.recordedAt ? format(new Date(v.recordedAt), 'dd MMM yyyy HH:mm') : '—'}</span>
              <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${v.source === 'IOT_DEVICE' ? 'text-cyan-500 bg-cyan-500/10' : 'text-slate-500 bg-slate-500/10'}`}>
                {v.source?.replace(/_/g, ' ')}
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
          </div>
        ))}
      </div>
    </div>
  );
}

// ═══════ TRIAGE TAB ═══════
function TriageTab({ visit, triageHistory, latestTriage, glassCard, glassInner, isDark, text, navigate }: any) {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Triage Records</h3>
        <button
          onClick={() => navigate(visit.isPediatric ? `/pediatric-triage/${visit.patientId}` : `/adult-triage/${visit.patientId}`)}
          className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all"
        >
          <Stethoscope className="w-3.5 h-3.5" /> {triageHistory.length > 0 ? 'Re-Triage' : 'Start Triage'}
        </button>
      </div>

      {latestTriage && (
        <div className="rounded-2xl p-5" style={glassCard}>
          <div className="flex items-center justify-between mb-4">
            <h4 className={`text-sm font-bold ${text.heading}`}>Latest Triage Result</h4>
            <div className="flex items-center gap-2">
              <span className={`w-3 h-3 rounded-full ${CATEGORY_COLORS[latestTriage.triageCategory]?.dot || 'bg-slate-400'}`} />
              <span className={`text-sm font-extrabold ${CATEGORY_COLORS[latestTriage.triageCategory]?.text || text.heading}`}>{latestTriage.triageCategory}</span>
              <span className={`text-lg font-black ${text.accent}`}>TEWS {latestTriage.tewsScore}</span>
            </div>
          </div>
          <div className="space-y-2">
            <InfoRow label="Decision Path" value={latestTriage.decisionPath || '—'} isDark={isDark} />
            <InfoRow label="Triaged By" value={latestTriage.triagedByName || '—'} isDark={isDark} />
            <InfoRow label="Child Form" value={latestTriage.isChildForm ? 'Yes' : 'No'} isDark={isDark} />
            <InfoRow label="Re-triage" value={latestTriage.isRetriage ? 'Yes' : 'No'} isDark={isDark} />
            {latestTriage.previousCategory && (
              <InfoRow label="Previous Category" value={latestTriage.previousCategory} isDark={isDark} />
            )}
            <InfoRow label="Time" value={latestTriage.triageTime ? format(new Date(latestTriage.triageTime), 'dd MMM yyyy HH:mm') : '—'} isDark={isDark} />
          </div>
        </div>
      )}

      {/* History */}
      {triageHistory.length > 1 && (
        <div className="rounded-2xl p-5" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Triage History</h4>
          <div className="space-y-3">
            {triageHistory.slice(1).map((t: TriageRecordResponse) => (
              <div key={t.id} className="rounded-xl p-3" style={glassInner}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className={`w-2.5 h-2.5 rounded-full ${CATEGORY_COLORS[t.triageCategory]?.dot || 'bg-slate-400'}`} />
                    <span className={`text-xs font-bold ${text.heading}`}>{t.triageCategory} — TEWS {t.tewsScore}</span>
                  </div>
                  <span className={`text-[10px] ${text.muted}`}>{t.triageTime ? format(new Date(t.triageTime), 'dd MMM HH:mm') : ''}</span>
                </div>
                <p className={`text-[11px] mt-1 ${text.body}`}>{t.decisionPath}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {triageHistory.length === 0 && (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <Stethoscope className="w-8 h-8 mx-auto mb-2 text-slate-400" />
          <p className={text.muted}>No triage records yet. Click "Start Triage" to begin.</p>
        </div>
      )}
    </div>
  );
}

// ═══════ NOTES TAB ═══════
function NotesTab({ notes, showForm, setShowForm, onSubmit, formLoading, glassCard, glassInner, isDark, text }: any) {
  const [form, setForm] = useState<Partial<CreateClinicalNoteRequest>>({ noteType: 'PROGRESS_NOTE' as NoteType, content: '', section: '' });

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
              <select value={form.noteType || 'PROGRESS_NOTE'} onChange={(e) => setForm({ ...form, noteType: e.target.value as NoteType })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                {Object.entries(NOTE_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </div>
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Section (optional)</label>
              <input value={form.section || ''} onChange={(e) => setForm({ ...form, section: e.target.value })} placeholder="e.g., Assessment, Plan" className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
            </div>
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Content</label>
              <textarea value={form.content || ''} onChange={(e) => setForm({ ...form, content: e.target.value })} placeholder="Enter clinical note..." rows={4} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => onSubmit(form)} disabled={formLoading || !form.content} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
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
function DiagnosesTab({ diagnoses, showForm, setShowForm, onSubmit, formLoading, glassCard, glassInner, isDark, text }: any) {
  const [form, setForm] = useState<Partial<CreateDiagnosisRequest>>({ diagnosisType: 'PROVISIONAL' as DiagnosisType, description: '', icdCode: '', isPrimary: false, notes: '' });

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
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>New Diagnosis</h4>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Type</label>
              <select value={form.diagnosisType || 'PROVISIONAL'} onChange={(e) => setForm({ ...form, diagnosisType: e.target.value as DiagnosisType })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                {['PROVISIONAL', 'CONFIRMED', 'DIFFERENTIAL', 'WORKING'].map(v => <option key={v} value={v}>{v}</option>)}
              </select>
            </div>
            <FormInput label="ICD-10 Code" value={form.icdCode} onChange={(v: string) => setForm({ ...form, icdCode: v })} glassInner={glassInner} isDark={isDark} text={text} />
            <div className="md:col-span-2">
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Description</label>
              <input value={form.description || ''} onChange={(e) => setForm({ ...form, description: e.target.value })} placeholder="Diagnosis description" className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
            </div>
            <div className="md:col-span-2">
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Notes</label>
              <textarea value={form.notes || ''} onChange={(e) => setForm({ ...form, notes: e.target.value })} placeholder="Additional notes..." rows={2} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
            </div>
            <div className="flex items-center gap-2">
              <input type="checkbox" checked={form.isPrimary || false} onChange={(e) => setForm({ ...form, isPrimary: e.target.checked })} className="rounded" />
              <span className={`text-xs ${text.body}`}>Primary diagnosis</span>
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => onSubmit(form)} disabled={formLoading || !form.description} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
              {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Save Diagnosis
            </button>
            <button onClick={() => setShowForm(false)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
          </div>
        </div>
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
  const [form, setForm] = useState<Partial<OrderInvestigationRequest>>({ investigationType: 'LABORATORY' as InvestigationType, testName: '', priority: 'ROUTINE', notes: '' });
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
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Order Investigation</h4>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Type</label>
              <select value={form.investigationType || 'LABORATORY'} onChange={(e) => setForm({ ...form, investigationType: e.target.value as InvestigationType })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                {['LABORATORY', 'RADIOLOGY', 'ECG', 'ULTRASOUND', 'CT_SCAN', 'MRI', 'XRAY', 'BLOOD_GAS', 'URINALYSIS', 'RAPID_TEST', 'POINT_OF_CARE', 'OTHER'].map(v => <option key={v} value={v}>{v.replace(/_/g, ' ')}</option>)}
              </select>
            </div>
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Priority</label>
              <select value={form.priority || 'ROUTINE'} onChange={(e) => setForm({ ...form, priority: e.target.value })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                <option value="ROUTINE">Routine</option>
                <option value="URGENT">Urgent</option>
                <option value="STAT">STAT</option>
              </select>
            </div>
            <div className="md:col-span-2">
              <FormInput label="Test Name" value={form.testName} onChange={(v: string) => setForm({ ...form, testName: v })} glassInner={glassInner} isDark={isDark} text={text} />
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => onSubmit(form)} disabled={formLoading || !form.testName} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
              {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Order
            </button>
            <button onClick={() => setShowForm(false)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
          </div>
        </div>
      )}

      {/* Result form */}
      {resultForm && (
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Record Result</h4>
          <div className="space-y-3">
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Result</label>
              <textarea value={resultForm.result} onChange={(e) => setResultForm({ ...resultForm, result: e.target.value })} rows={3} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
            </div>
            <div className="flex items-center gap-4">
              <label className="flex items-center gap-2"><input type="checkbox" checked={resultForm.isAbnormal} onChange={(e) => setResultForm({ ...resultForm, isAbnormal: e.target.checked })} className="rounded" /><span className={`text-xs ${text.body}`}>Abnormal</span></label>
              <label className="flex items-center gap-2"><input type="checkbox" checked={resultForm.isCritical} onChange={(e) => setResultForm({ ...resultForm, isCritical: e.target.checked })} className="rounded" /><span className={`text-xs ${text.body}`}>Critical</span></label>
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => { onAction(resultForm.id, 'result', { investigationId: resultForm.id, result: resultForm.result, isAbnormal: resultForm.isAbnormal, isCritical: resultForm.isCritical, notes: resultForm.notes }); setResultForm(null); }} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
              <CheckCircle2 className="w-3.5 h-3.5" /> Save Result
            </button>
            <button onClick={() => setResultForm(null)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
          </div>
        </div>
      )}

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
function MedicationsTab({ medications, showForm, setShowForm, onSubmit, onAction, formLoading, glassCard, glassInner, isDark, text }: any) {
  const [form, setForm] = useState<Partial<PrescribeMedicationRequest>>({ drugName: '', dose: '', route: 'PO' as MedicationRoute, frequency: '', notes: '' });

  const ROUTE_LABELS: Record<string, string> = { PO: 'Oral', IV: 'IV', IM: 'IM', SC: 'SC', SL: 'Sublingual', PR: 'PR', INH: 'Inhaled', NEB: 'Nebuliser', TOP: 'Topical', NASAL: 'Nasal', OPHTHALMIC: 'Ophthalmic', OTIC: 'Ear', ETT: 'ETT', IO: 'IO', OTHER: 'Other' };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Medications ({medications.length})</h3>
        <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
          <Plus className="w-3.5 h-3.5" /> Prescribe Medication
        </button>
      </div>

      {showForm && (
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Prescribe Medication</h4>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <FormInput label="Drug Name" value={form.drugName} onChange={(v: string) => setForm({ ...form, drugName: v })} glassInner={glassInner} isDark={isDark} text={text} />
            <FormInput label="Dose" value={form.dose} onChange={(v: string) => setForm({ ...form, dose: v })} glassInner={glassInner} isDark={isDark} text={text} />
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Route</label>
              <select value={form.route || 'PO'} onChange={(e) => setForm({ ...form, route: e.target.value as MedicationRoute })} className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                {Object.entries(ROUTE_LABELS).map(([k, v]) => <option key={k} value={k}>{v} ({k})</option>)}
              </select>
            </div>
            <FormInput label="Frequency" value={form.frequency} onChange={(v: string) => setForm({ ...form, frequency: v })} glassInner={glassInner} isDark={isDark} text={text} />
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => onSubmit(form)} disabled={formLoading || !form.drugName} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50">
              {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />} Prescribe
            </button>
            <button onClick={() => setShowForm(false)} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
          </div>
        </div>
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
function MonitorTab({ visitId, glassCard, isDark, text }: { visitId: string; glassCard: React.CSSProperties; isDark: boolean; text: any }) {
  const [streamData, setStreamData] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const loadStream = async () => {
      try {
        const { iotApi } = await import('@/api/iot');
        const recent = await iotApi.getRecentStream(visitId, 30);
        setStreamData(Array.isArray(recent) ? recent : []);
      } catch {
        setStreamData([]);
      } finally {
        setLoading(false);
      }
    };
    loadStream();
  }, [visitId]);

  if (loading) {
    return <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-cyan-500" /></div>;
  }

  const latest = streamData[streamData.length - 1];

  return (
    <div className="space-y-4">
      <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Real-Time Monitor</h3>
      {latest ? (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <div className="rounded-2xl p-4 text-center" style={glassCard}>
            <Heart className="w-5 h-5 mx-auto mb-1 text-red-500" />
            <p className="text-2xl font-black text-red-500">{latest.heartRate || '—'}</p>
            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Heart Rate</p>
          </div>
          <div className="rounded-2xl p-4 text-center" style={glassCard}>
            <Droplets className="w-5 h-5 mx-auto mb-1 text-cyan-500" />
            <p className="text-2xl font-black text-cyan-500">{latest.spo2 || '—'}%</p>
            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>SpO2</p>
          </div>
          <div className="rounded-2xl p-4 text-center" style={glassCard}>
            <Wind className="w-5 h-5 mx-auto mb-1 text-blue-500" />
            <p className="text-2xl font-black text-blue-500">{latest.respiratoryRate || '—'}</p>
            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Resp Rate</p>
          </div>
          <div className="rounded-2xl p-4 text-center" style={glassCard}>
            <Thermometer className="w-5 h-5 mx-auto mb-1 text-amber-500" />
            <p className="text-2xl font-black text-amber-500">{latest.temperature || '—'}°C</p>
            <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Temp</p>
          </div>
        </div>
      ) : (
        <div className="rounded-2xl p-8 text-center" style={glassCard}>
          <Monitor className="w-8 h-8 mx-auto mb-2 text-slate-400" />
          <p className={text.muted}>No real-time monitoring data available</p>
          <p className={`text-xs mt-1 ${text.muted}`}>Connect an IoT device to start monitoring</p>
        </div>
      )}
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
