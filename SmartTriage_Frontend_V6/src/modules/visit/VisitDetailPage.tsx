/* ═══════════════════════════════════════════════════════════════
   Visit Detail Page — Full Clinical Workspace
   Tabs: Overview, Pre-hospital, Vitals, Triage, Clinical Signs, Notes,
         Diagnoses, Investigations, Sepsis, Medications, Alerts, Disposition
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, Activity, FileText, Stethoscope, ClipboardList,
  FlaskConical, Pill, BellRing, Heart, Thermometer,
  Wind, Droplets, Brain, Clock, User, AlertTriangle, ChevronRight,
  Plus, Send, CheckCircle2, XCircle, Eye, Loader2, RefreshCw, LogOut,
  TrendingUp, Sparkles, Siren, UserCheck, ShieldAlert,
} from 'lucide-react';
import { ClinicalSignsTab } from './ClinicalSignsTab';
import { SepsisPanel } from './SepsisPanel';
import { PrehospitalTab } from '@/modules/ems/PrehospitalTab';
import { DiagnosisPanel } from './DiagnosisPanel';
import { InvestigationPanel } from './InvestigationPanel';
import { MedicationPanel } from './MedicationPanel';
import { useTheme } from '@/hooks/useTheme';
import { useCanPerformTriage } from '@/hooks/useCanPerformTriage';
import { useAuthStore } from '@/store/authStore';
import { UnidentifiedBadge } from '@/modules/admission/UnidentifiedBadge';
import { IdentityResolutionModal } from '@/modules/admission/IdentityResolutionModal';
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
import { PatientHistoryPanel } from '@/modules/entry/PatientHistoryPanel';
import { PatientProfilePanel } from '@/modules/entry/PatientProfilePanel';
import { PrescribeSafetyDialog } from '@/modules/visit/PrescribeSafetyDialog';
import {
  checkDrugAgainstAllergies,
  checkDrugAgainstStructuredAllergies,
  formatAllergyMatches,
  highestAllergySeverity,
  type AllergyMatch,
} from '@/utils/allergyCheck';
import { patientAllergyApi } from '@/api/patientAllergies';
import { patientChronicConditionApi } from '@/api/patientChronicConditions';
import type { PatientAllergyResponse, PatientChronicConditionResponse } from '@/api/types';
import {
  checkInteractions, formatInteractionMatches,
  checkDuplicateTherapy, formatDuplicateMatches,
  type InteractionMatch, type DuplicateMatch,
} from '@/utils/interactionCheck';
import {
  checkPediatricDose, formatDoseMatches, type DoseMatch,
} from '@/utils/pediatricDoseCheck';
import {
  checkAdultDose, formatAdultDoseMatches, type AdultDoseMatch,
} from '@/utils/adultDoseCheck';
import {
  checkRenalRisk, formatRenalMatches, type RenalMatch,
} from '@/utils/renalRiskCheck';
import {
  checkTeratogenRisk, formatTeratogenMatches, type TeratogenMatch,
} from '@/utils/teratogenCheck';
import {
  checkGeriatricRisk, formatGeriatricMatches, type GeriatricMatch,
} from '@/utils/geriatricCheck';
import {
  cockcroftGaultEgfr, normaliseCreatinineToMgPerDl,
  checkRenalEgfrDosing, formatRenalEgfrMatches,
  type RenalEgfrMatch,
} from '@/utils/eGfrCalc';
import type {
  VisitResponse, VitalSignsResponse, TriageRecordResponse,
  ClinicalNoteResponse, DiagnosisResponse, InvestigationResponse,
  MedicationResponse, ClinicalAlertResponse, PatientResponse,
  RecordVitalsRequest, CreateClinicalNoteRequest, CreateDiagnosisRequest,
  OrderInvestigationRequest, PrescribeMedicationRequest,
  NoteType, DiagnosisType, InvestigationType, MedicationRoute,
  AvpuScore, TriageCategory, DispositionType,
  MedicationOrderAudit, MedicationDoseResponse,
} from '@/api/types';
import { format } from 'date-fns';
import { RecentActivityBanner } from './RecentActivityBanner';

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
  { id: 'pre-hospital', label: 'Pre-hospital', icon: Siren },
  { id: 'vitals', label: 'Vitals', icon: Activity },
  { id: 'triage', label: 'Triage', icon: Stethoscope },
  // Clinical Signs sits adjacent to Triage because it tracks the
  // evolution of the very signs that triage captured at entry —
  // emergency signs and mSAT discriminators over time.
  { id: 'clinical-signs', label: 'Clinical Signs', icon: TrendingUp },
  { id: 'notes', label: 'Notes', icon: FileText },
  { id: 'diagnoses', label: 'Diagnoses', icon: ClipboardList },
  { id: 'investigations', label: 'Investigations', icon: FlaskConical },
  // Sepsis sits between Investigations and Medications: it consumes the
  // vitals + labs gathered upstream and, on a positive screen, drives the
  // antibiotic/fluid bundle that flows into Medications.
  { id: 'sepsis', label: 'Sepsis', icon: ShieldAlert },
  { id: 'medications', label: 'Medications', icon: Pill },
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

// Section suggestions per note type. Each entry is the structured frame
// clinicians use for that kind of note — SOAP for progress notes, SBAR
// for handovers, body systems for physical findings, etc. The doctor
// picks a chip and the section auto-fills; free-text is still allowed for
// the rare case the standard frame doesn't fit.
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

// Note-type-specific placeholder for the body field. Tells the doctor what
// kind of content fits each frame so the form is self-describing.
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

const INVESTIGATION_STATUS_COLORS: Record<string, string> = {
  ORDERED: 'text-blue-500 bg-blue-500/10',
  SPECIMEN_COLLECTED: 'text-amber-500 bg-amber-500/10',
  IN_PROGRESS: 'text-cyan-500 bg-cyan-500/10',
  RESULTED: 'text-emerald-500 bg-emerald-500/10',
  CANCELLED: 'text-red-500 bg-red-500/10',
};

const MEDICATION_STATUS_COLORS: Record<string, string> = {
  PENDING_APPROVAL: 'text-red-600 bg-red-500/10',
  PRESCRIBED: 'text-blue-500 bg-blue-500/10',
  ADMINISTERED: 'text-emerald-500 bg-emerald-500/10',
  HELD: 'text-amber-500 bg-amber-500/10',
  REFUSED: 'text-red-500 bg-red-500/10',
  CANCELLED: 'text-slate-500 bg-slate-500/10',
  COMPLETED: 'text-emerald-600 bg-emerald-500/10',
  DISCONTINUED: 'text-slate-500 bg-slate-500/10',
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
  // Patient is fetched separately (visit only carries patientId).
  // Used for allergy cross-checking at prescribe time.
  const [patient, setPatient] = useState<PatientResponse | null>(null);
  // Workflow 2 — structured allergy records. The prescribe-time
  // safety check prefers these over the legacy free-text column on
  // patient.knownAllergies because they carry per-row severity +
  // reaction, which drive the dialog flavour and the override
  // alert calibration. Empty array when none on file; we then fall
  // back to the legacy free-text match.
  const [structuredAllergies, setStructuredAllergies] = useState<PatientAllergyResponse[]>([]);
  // Workflow 2 refinement — structured chronic conditions (V61).
  // The safety engine prefers these (catalog-driven, code-keyed) over
  // the legacy free-text column. Empty array when none on file; in
  // that case the renal/teratogen checks fall back to the free-text
  // path so un-migrated patients still get safety dialogs.
  const [structuredChronicConditions, setStructuredChronicConditions] =
    useState<PatientChronicConditionResponse[]>([]);
  // Phase 2 zone routing — current pending transfer (if any) for this
  // visit. Drives the inter-zone handover banner. Re-fetched on every
  // loadData so accept/decline reflects immediately.
  const [pendingTransfer, setPendingTransfer] = useState<import('@/api/zoneTransfers').ZoneTransferResponse | null>(null);

  // Forms
  const [showIdentityModal, setShowIdentityModal] = useState(false);
  const [showVitalsForm, setShowVitalsForm] = useState(false);
  const [showNoteForm, setShowNoteForm] = useState(false);
  const [showDiagnosisForm, setShowDiagnosisForm] = useState(false);
  const [showInvestigationForm, setShowInvestigationForm] = useState(false);
  const [showMedicationForm, setShowMedicationForm] = useState(false);
  const [formLoading, setFormLoading] = useState(false);

  // Safety hard-stop: when a prescribe attempt conflicts with the
  // patient's known allergies, with a drug–drug interaction against
  // another active medication, with a duplicate-therapy hit (same
  // class, different drug), or with a paediatric weight-based dose
  // out of range, we hold the request here until the clinician
  // explicitly cancels or overrides.
  const [pendingPrescribe, setPendingPrescribe] = useState<{
    data: Partial<PrescribeMedicationRequest>;
    allergyMatches: AllergyMatch[];
    interactionMatches: InteractionMatch[];
    duplicateMatches: DuplicateMatch[];
    doseMatches: DoseMatch[];
    adultDoseMatches: AdultDoseMatch[];
    renalMatches: RenalMatch[];
    teratogenMatches: TeratogenMatch[];
    geriatricMatches: GeriatricMatch[];
    renalEgfrMatches: RenalEgfrMatch[];
  } | null>(null);

  const userName = user?.fullName || 'Unknown';

  const loadData = useCallback(async () => {
    if (!visitId) return;
    setLoading(true);
    try {
      const { zoneTransferApi } = await import('@/api/zoneTransfers');
      const [v, vit, lt, th, triLatest, n, d, inv, med, al, pt] = await Promise.allSettled([
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
        zoneTransferApi.pendingForVisit(visitId),
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
      if (pt.status === 'fulfilled') setPendingTransfer(pt.value);
    } catch (err) {
      console.error('Failed to load visit data:', err);
    } finally {
      setLoading(false);
    }
  }, [visitId]);

  useEffect(() => { loadData(); }, [loadData]);

  // Fetch the patient profile once the visit is loaded — needed for
  // the allergy check at prescribe time. Best-effort: failure here
  // doesn't block the rest of the page.
  useEffect(() => {
    if (!visit?.patientId) return;
    let cancelled = false;
    patientApi
      .getById(visit.patientId)
      .then((p) => { if (!cancelled) setPatient(p); })
      .catch(() => { /* swallow — non-critical */ });
    // Structured allergies (V58) — populated in parallel. Empty array
    // is the safe default (legacy free-text fallback will apply).
    patientAllergyApi
      .list(visit.patientId)
      .then((rows) => { if (!cancelled) setStructuredAllergies(Array.isArray(rows) ? rows : []); })
      .catch(() => { /* swallow — non-critical, legacy fallback covers */ });
    // Workflow 2 refinement — structured chronic conditions (V61).
    patientChronicConditionApi
      .list(visit.patientId)
      .then((rows) => { if (!cancelled) setStructuredChronicConditions(Array.isArray(rows) ? rows : []); })
      .catch(() => { /* swallow — non-critical, legacy fallback covers */ });
    return () => { cancelled = true; };
  }, [visit?.patientId]);

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

  // Actually issue the prescribe. Split out so the override path
  // (after the safety dialog is acknowledged) and the clean path can
  // share it. The four override arrays are non-empty only when this
  // prescribe is going through after the dialog was acknowledged —
  // that's what tells the backend to set the override flags (V23
  // allergy / V24 interaction). All four can be true on a single
  // order if one drug hit every check.
  //
  // Only allergy gets its own DB column (V23). Interactions, duplicate
  // therapy, and paediatric dose all share the V24 column. Each
  // formatter prefixes its lines with a tag — `[major]`/`[contraind]`
  // for true interactions (no prefix for legacy reasons),
  // `[duplicate]` for same-class hits, `[overdose]`/`[underdose]` for
  // dose. A SQL `LIKE '%[overdose]%'` filter cleanly separates them
  // for QA reports without needing a migration.
  const submitPrescribe = async (
    data: Partial<PrescribeMedicationRequest>,
    allergyOverride: AllergyMatch[] = [],
    interactionOverride: InteractionMatch[] = [],
    duplicateOverride: DuplicateMatch[] = [],
    doseOverride: DoseMatch[] = [],
    adultDoseOverride: AdultDoseMatch[] = [],
    renalOverride: RenalMatch[] = [],
    teratogenOverride: TeratogenMatch[] = [],
    geriatricOverride: GeriatricMatch[] = [],
    renalEgfrOverride: RenalEgfrMatch[] = [],
    /** Workflow 2 — free-text reason captured by the dialog when a
     *  SEVERE / ANAPHYLAXIS allergy required deliberate override.
     *  Appended to the audit snapshot. Undefined for the routine
     *  single-click override flow. */
    overrideReason?: string,
  ) => {
    setFormLoading(true);
    try {
      // Combine real interactions, duplicate-therapy hits, dose
      // out-of-range hits, renal-risk hits, and teratogen hits into
      // a single audit snapshot for the V24 column. The `[…]`
      // prefixes in each formatter preserve filterability for
      // downstream QA.
      const interactionParts: string[] = [];
      if (interactionOverride.length > 0) interactionParts.push(formatInteractionMatches(interactionOverride));
      if (duplicateOverride.length > 0) interactionParts.push(formatDuplicateMatches(duplicateOverride));
      if (doseOverride.length > 0) interactionParts.push(formatDoseMatches(doseOverride));
      if (adultDoseOverride.length > 0) interactionParts.push(formatAdultDoseMatches(adultDoseOverride));
      if (renalOverride.length > 0) interactionParts.push(formatRenalMatches(renalOverride));
      if (teratogenOverride.length > 0) interactionParts.push(formatTeratogenMatches(teratogenOverride));
      if (geriatricOverride.length > 0) interactionParts.push(formatGeriatricMatches(geriatricOverride));
      if (renalEgfrOverride.length > 0) interactionParts.push(formatRenalEgfrMatches(renalEgfrOverride));
      const interactionFlag =
        interactionOverride.length > 0 ||
        duplicateOverride.length > 0 ||
        doseOverride.length > 0 ||
        adultDoseOverride.length > 0 ||
        renalOverride.length > 0 ||
        teratogenOverride.length > 0 ||
        geriatricOverride.length > 0 ||
        renalEgfrOverride.length > 0;

      // Workflow 2 — severity + override reason flow into the audit
      // snapshot and the alert-severity calibration. When no structured
      // severity was matched (legacy free-text fallback fired), we
      // omit allergyOverrideSeverity and the backend anchors at
      // CRITICAL (safest).
      const allergySev = allergyOverride.length > 0
        ? highestAllergySeverity(allergyOverride)
        : null;
      const allergyMatchesText = allergyOverride.length > 0
        ? formatAllergyMatches(allergyOverride)
        : null;
      const allergySnapshot = allergyMatchesText && overrideReason
        ? `${allergyMatchesText} | Override reason: ${overrideReason}`
        : allergyMatchesText;

      const payload: PrescribeMedicationRequest = {
        visitId: visit.id,
        prescribedByName: userName,
        ...data,
        ...(allergyOverride.length > 0
          ? {
              prescribedDespiteAllergy: true,
              allergyOverrideMatches: allergySnapshot ?? undefined,
              ...(allergySev ? { allergyOverrideSeverity: allergySev } : {}),
            }
          : {}),
        ...(interactionFlag
          ? {
              prescribedDespiteInteraction: true,
              interactionOverrideMatches: interactionParts.join('; '),
            }
          : {}),
      } as PrescribeMedicationRequest;
      await medicationApi.prescribe(payload);
      setShowMedicationForm(false);
      setPendingPrescribe(null);
      loadData();
    } catch (err) {
      // Surface a blocked/failed prescription to the prescriber — most
      // importantly the server-side allergy safety BLOCK (S1), which can
      // fire even when the client-side dialog didn't (e.g. a direct path or
      // a divergent client check). Without this the form/dialog just stopped
      // loading with no explanation. Matches handleMedicationAction's pattern
      // (window.alert for a backend ClinicalBusinessException); the form and
      // safety dialog stay open so the prescriber can adjust or cancel.
      const message = err instanceof Error ? err.message : 'Failed to prescribe medication';
      // eslint-disable-next-line no-alert
      window.alert(message);
      console.error(err);
    } finally { setFormLoading(false); }
  };

  const handlePrescribeMedication = async (data: Partial<PrescribeMedicationRequest>) => {
    // Run all five safety checks. Any one firing opens the dialog;
    // the dialog renders only the sections that have content. Patient
    // and medications may still be loading — in that case we skip the
    // relevant check rather than block on the lookup (the alternative
    // is a flicker dialog that never appears for a fast prescriber).
    // The backend remains the safety net of last resort.
    // Workflow 2 — prefer structured allergies (severity + reaction
    // available) over the legacy free-text column. Fall back only
    // when no structured rows exist for the patient yet, so
    // un-migrated records still get a safety dialog (with the legacy
    // amber flavour because severity is unknowable).
    const allergyMatches: AllergyMatch[] =
      structuredAllergies.length > 0
        ? checkDrugAgainstStructuredAllergies(data.drugName, structuredAllergies)
        : patient
          ? checkDrugAgainstAllergies(data.drugName, patient.knownAllergies)
          : [];
    const activeForChecks = medications.map((m) => ({ drugName: m.drugName, status: m.status }));
    const interactionMatches = checkInteractions(data.drugName, activeForChecks);
    const duplicateMatches  = checkDuplicateTherapy(data.drugName, activeForChecks);
    // Paediatric dose check fires only for paediatric visits with a
    // recorded weight. Weight is captured during triage on the child
    // form (`childWeightKg` on TriageRecordResponse). We don't fall
    // back to adult-stub weights because an out-of-range warning that
    // hinges on a stale or assumed weight is worse than no warning.
    const doseMatches = visit.isPediatric && latestTriage?.childWeightKg
      ? checkPediatricDose(data.drugName, data.dose, latestTriage.childWeightKg)
      : [];
    // Adult single-dose envelope check (Phase 11b). Fires only on
    // non-paediatric visits — paediatric uses the mg/kg path above.
    // No weight required for adults: ranges are absolute mg.
    const adultDoseMatches = visit.isPediatric
      ? []
      : checkAdultDose(data.drugName, data.dose);
    // Renal-risk check (Phase 12a) — screening level only because we
    // don't yet have structured creatinine or adult weight to compute
    // eGFR. Two trigger paths: chronicConditions text mentions CKD,
    // OR latest vitals fit a hemodynamic-AKI pattern. Patient still
    // loading → skip CKD trigger; AKI trigger still works on vitals.
    const renalMatches = checkRenalRisk(
      data.drugName,
      patient?.chronicConditions,
      latestVitals,
      // Workflow 2 refinement — structured chronic conditions take
      // precedence. The check consults the CKD/ESRD catalog codes
      // first, falls back to free-text scanning when none match.
      structuredChronicConditions,
    );
    // Teratogen check (Phase 13) — fires only when chronicConditions
    // explicitly records pregnancy or breastfeeding. We deliberately
    // do NOT trigger on demographics (female + childbearing age),
    // because that would alert on a huge fraction of orders and
    // train prescribers to dismiss the dialog. The cost of a missed
    // teratogen warning is borne by the fetus, not the clinician —
    // alert fatigue is the worse failure mode.
    const teratogenMatches = checkTeratogenRisk(
      data.drugName,
      patient?.chronicConditions,
      patient?.pregnancyStatus,
    );
    // Geriatric (Beers Criteria) check (Phase 16) — fires when the
    // patient is ≥ 65 and the drug appears in the curated Beers table.
    // Deterministic age gate, no chronicConditions parsing. Patient
    // still loading → skip (the backend formulary check will catch
    // gross errors anyway).
    const geriatricMatches = checkGeriatricRisk(
      data.drugName,
      patient?.ageInYears,
    );
    // Phase 12b — Cockcroft-Gault eGFR-driven dose check. Distinct
    // from the Phase 12a screening trigger (which fires on text
    // CKD or AKI-pattern vitals): this one needs a structured serum
    // creatinine AND adult weight. When either is missing, we silently
    // skip — the screening check still covers the chart-text path.
    //
    // Walk the investigations list newest-first for a creatinine row
    // with a numeric result + unit. testName matching is permissive
    // ("creatinine", "Cr", "Serum Cr") because lab labelling varies.
    const creatinineInv = investigations.find((inv) => {
      const tn = (inv.testName || '').toLowerCase();
      return (
        inv.resultNumeric != null &&
        inv.resultUnit != null &&
        (tn.includes('creatinine') || tn.includes(' cr ') || tn.startsWith('cr '))
      );
    });
    const creatinineMgPerDl = creatinineInv
      ? normaliseCreatinineToMgPerDl(creatinineInv.resultNumeric!, creatinineInv.resultUnit)
      : null;
    // Walk vitals newest-first for the most recent recorded weight.
    // Single-call here rather than every render — vitals[] is already
    // sorted newest-first by the API (see loadData / vitalApi.getByVisit).
    const latestWeightKg = vitals.find((v) => v.weightKg != null)?.weightKg ?? null;
    const sex: 'female' | 'male' | 'unknown' = patient?.gender === 'FEMALE'
      ? 'female'
      : patient?.gender === 'MALE'
        ? 'male'
        : 'unknown';
    const egfr = cockcroftGaultEgfr({
      ageYears: patient?.ageInYears ?? null,
      weightKg: latestWeightKg,
      creatinineMgPerDl,
      sex,
    });
    const renalEgfrMatches = visit.isPediatric
      ? []
      : checkRenalEgfrDosing(data.drugName, egfr);

    if (
      allergyMatches.length > 0 ||
      interactionMatches.length > 0 ||
      duplicateMatches.length > 0 ||
      doseMatches.length > 0 ||
      adultDoseMatches.length > 0 ||
      renalMatches.length > 0 ||
      teratogenMatches.length > 0 ||
      geriatricMatches.length > 0 ||
      renalEgfrMatches.length > 0
    ) {
      setPendingPrescribe({
        data,
        allergyMatches,
        interactionMatches,
        duplicateMatches,
        doseMatches,
        adultDoseMatches,
        renalMatches,
        teratogenMatches,
        geriatricMatches,
        renalEgfrMatches,
      });
      return;
    }
    await submitPrescribe(data);
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

  const handleMedicationAction = async (id: string, action: string, reason?: string) => {
    try {
      switch (action) {
        case 'administer':
          await medicationApi.administer(id, { medicationId: id, administeredByName: userName });
          break;
        case 'countersign':
          await medicationApi.countersign(id, { medicationId: id, countersignedByName: userName });
          break;
        case 'hold':
          if (!reason) return;
          await medicationApi.hold(id, reason);
          break;
        case 'refuse':
          if (!reason) return;
          await medicationApi.refuse(id, reason);
          break;
      }
      loadData();
    } catch (err) {
      // Surface backend ClinicalBusinessException to the user — e.g.
      // separation-of-duties violation when the prescriber tries to
      // administer their own order.
      const message = err instanceof Error ? err.message : 'Action failed';
      // eslint-disable-next-line no-alert
      window.alert(message);
      console.error(err);
    }
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
                  {patient?.isUnidentified && <UnidentifiedBadge patient={patient} showLabel showAge />}
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
              {patient?.isUnidentified && (
                <button
                  onClick={() => setShowIdentityModal(true)}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-xl bg-cyan-500 text-white text-xs font-bold hover:bg-cyan-400 transition-colors"
                  title="Set this patient's real identity"
                >
                  <UserCheck className="w-4 h-4" /> Set identity
                </button>
              )}
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

        {/* ── Recent activity (shift-handover affordance) ──
            Shows what's new on this patient since the doctor came on
            shift (or last N hours). Lets an inheriting clinician spot
            new vitals / lab results / meds / alerts at a glance instead
            of scrolling the full timeline. */}
        <RecentActivityBanner
          vitals={vitals}
          triageHistory={triageHistory}
          notes={notes}
          diagnoses={diagnoses}
          investigations={investigations}
          medications={medications}
          alerts={visitAlerts}
        />

        {/* ── Tab Content ── */}
        <div className="animate-fade-up" style={{ animationDelay: '0.05s' }}>
          {activeTab === 'overview' && <OverviewTab visit={visit} latestVitals={latestVitals} latestTriage={latestTriage} notes={notes} diagnoses={diagnoses} investigations={investigations} medications={medications} alerts={visitAlerts} pendingTransfer={pendingTransfer} reload={loadData} navigate={navigate} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'pre-hospital' && <PrehospitalTab visitId={visit.id} edTriageCategory={latestTriage?.triageCategory ?? null} />}
          {activeTab === 'vitals' && <VitalsTab vitals={vitals} latestVitals={latestVitals} glassCard={glassCard} isDark={isDark} text={text} />}
          {activeTab === 'triage' && <TriageTab visit={visit} triageHistory={triageHistory} latestTriage={latestTriage} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'clinical-signs' && <ClinicalSignsTab visitId={visit.id} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} onVisitMayHaveChanged={loadData} />}
          {activeTab === 'notes' && <NotesTab notes={notes} showForm={showNoteForm} setShowForm={setShowNoteForm} onSubmit={handleCreateNote} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'diagnoses' && <DiagnosesTab diagnoses={diagnoses} showForm={showDiagnosisForm} setShowForm={setShowDiagnosisForm} onSubmit={handleCreateDiagnosis} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'investigations' && <InvestigationsTab investigations={investigations} showForm={showInvestigationForm} setShowForm={setShowInvestigationForm} onSubmit={handleOrderInvestigation} onAction={handleInvestigationAction} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} userName={userName} />}
          {activeTab === 'sepsis' && <SepsisPanel visitId={visit.id} latestVitals={latestVitals} onScreened={loadData} />}
          {activeTab === 'medications' && <MedicationsTab medications={medications} showForm={showMedicationForm} setShowForm={setShowMedicationForm} onSubmit={handlePrescribeMedication} onAction={handleMedicationAction} formLoading={formLoading} patient={patient} visit={visit} latestTriage={latestTriage} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'alerts' && <AlertsTab alerts={visitAlerts} onAcknowledge={handleAcknowledgeAlert} visit={visit} navigate={navigate} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
          {activeTab === 'disposition' && <DispositionTab visit={visit} onDisposition={handleRecordDisposition} formLoading={formLoading} glassCard={glassCard} glassInner={glassInner} isDark={isDark} text={text} />}
        </div>
      </div>

      {/* ── Combined prescribe-time safety hard-stop modal ── */}
      {pendingPrescribe && (
        <PrescribeSafetyDialog
          drugName={pendingPrescribe.data.drugName ?? ''}
          allergyMatches={pendingPrescribe.allergyMatches}
          interactionMatches={pendingPrescribe.interactionMatches}
          duplicateMatches={pendingPrescribe.duplicateMatches}
          doseMatches={pendingPrescribe.doseMatches}
          adultDoseMatches={pendingPrescribe.adultDoseMatches}
          renalMatches={pendingPrescribe.renalMatches}
          teratogenMatches={pendingPrescribe.teratogenMatches}
          geriatricMatches={pendingPrescribe.geriatricMatches}
          renalEgfrMatches={pendingPrescribe.renalEgfrMatches}
          rawAllergyString={patient?.knownAllergies ?? ''}
          loading={formLoading}
          onCancel={() => setPendingPrescribe(null)}
          onOverride={(reason) => submitPrescribe(
            pendingPrescribe.data,
            pendingPrescribe.allergyMatches,
            pendingPrescribe.interactionMatches,
            pendingPrescribe.duplicateMatches,
            pendingPrescribe.doseMatches,
            pendingPrescribe.adultDoseMatches,
            pendingPrescribe.renalMatches,
            pendingPrescribe.teratogenMatches,
            pendingPrescribe.geriatricMatches,
            pendingPrescribe.renalEgfrMatches,
            reason,
          )}
        />
      )}

      {/* ── Set Patient Identity (unidentified Direct Resus / ambulance arrival) ── */}
      {showIdentityModal && patient && (
        <IdentityResolutionModal
          patient={patient}
          hospitalId={patient.hospitalId ?? user?.hospitalId ?? ''}
          onClose={() => setShowIdentityModal(false)}
          onResolved={(resolved) => {
            setPatient(resolved);
            setShowIdentityModal(false);
            loadData();
          }}
        />
      )}
    </div>
  );
}

// ═══════ OVERVIEW TAB ═══════
function OverviewTab({ visit, latestVitals, latestTriage, notes, diagnoses, investigations, medications, alerts, pendingTransfer, reload, navigate, glassCard, glassInner, isDark, text }: any) {
  const unackAlerts = alerts.filter((a: ClinicalAlertResponse) => !a.acknowledged).length;
  // RBAC — only users whose TODAY'S shift gives them triage authority can
  // click the "Re-triage now" button. Zone Nurses (including those with
  // permanent Charge Nurse designation working a non-CN shift) see the
  // banner but the action is grayed.
  const canTriage = useCanPerformTriage();

  // Round 3 — show a prominent banner when the most recent triage was
  // created automatically by a worsening clinical sign and the matching
  // RETRIAGE_REQUIRED alert hasn't been acknowledged yet. Once the
  // doctor acks the alert from the Alerts tab, the banner disappears.
  const latestSystemTriggered = latestTriage?.isSystemTriggered === true;
  const matchingUnackedRetriageAlert = alerts.find(
    (a: ClinicalAlertResponse) => !a.acknowledged && a.alertType === 'RETRIAGE_REQUIRED',
  );
  const showRetriageBanner = latestSystemTriggered && !!matchingUnackedRetriageAlert;
  const bannerCategory = latestTriage?.triageCategory ?? 'RED';
  const bannerCatColor = CATEGORY_COLORS[bannerCategory] ?? CATEGORY_COLORS.RED;

  // Round 4a — same click-through path as AlertsTab. Built here too so
  // the doctor on Overview gets a one-tap "Re-triage now" without
  // bouncing to the Alerts tab first.
  const goToRetriage = (a: ClinicalAlertResponse | undefined) => {
    if (!a || !visit?.patientId || !navigate) return;
    const path = visit.isPediatric ? '/pediatric-triage' : '/adult-triage';
    const params = new URLSearchParams();
    params.set('fromAlert', a.id);
    params.set('visitId', visit.id);
    if (a.triggeringSignCode) params.set('triggerSign', a.triggeringSignCode);
    navigate(`${path}/${visit.patientId}?${params.toString()}`);
  };

  return (
    <div className="space-y-4">
    {showRetriageBanner && (
      <div
        className={`rounded-2xl p-4 border ${bannerCatColor.border} flex items-start gap-3 animate-fade-up`}
        style={{ background: 'rgba(239,68,68,0.08)' }}
      >
        <AlertTriangle className={`w-5 h-5 ${bannerCatColor.text} flex-shrink-0 mt-0.5`} />
        <div className="flex-1 min-w-0">
          <p className={`text-sm font-bold ${bannerCatColor.text}`}>
            Re-triaged to {bannerCategory} automatically
          </p>
          <p className={`text-xs mt-0.5 ${text.body}`}>
            {latestTriage.triggeringSignLabel
              ? <>System detected <span className="font-bold">{latestTriage.triggeringSignLabel}</span>{latestTriage.triggeringSignStatus && <> reported as <span className="font-bold">{latestTriage.triggeringSignStatus}</span></>}.</>
              : 'A worsening clinical sign moved this patient up.'}
            {' '}Confirm the patient is being seen.
          </p>
          <button
            onClick={() => goToRetriage(matchingUnackedRetriageAlert)}
            disabled={!canTriage}
            title={canTriage
              ? 'Open the re-triage form for this patient'
              : 'Your current shift does not authorise triage. The Triage Nurse or Charge Nurse on duty will pick this up.'}
            className={`mt-2 inline-flex items-center gap-1.5 px-3 py-1.5 text-[10px] font-bold rounded-lg shadow-md transition-all ${
              canTriage
                ? 'bg-gradient-to-r from-red-500 to-red-600 text-white hover:-translate-y-0.5'
                : 'bg-slate-200 text-slate-400 cursor-not-allowed'
            }`}
          >
            <Stethoscope className="w-3 h-3" />
            {canTriage ? 'Re-triage now' : 'Triage authority required'}
          </button>
        </div>
      </div>
    )}

    {/* Phase 2 zone routing — pending inter-zone transfer banner. The
        patient is logically in `pendingTransfer.toZone` but physically
        still in `pendingTransfer.fromZone` until a receiving doctor
        accepts. Both teams see this; either can act. Until accepted,
        the original primary clinician retains responsibility — that's
        the safety invariant. */}
    {pendingTransfer && (
      <PendingTransferBanner
        transfer={pendingTransfer}
        reload={reload}
        text={text}
      />
    )}
    {/* Patient profile — persistent facts (allergies, chronic conditions,
        blood type, guardian). Safety-critical: rendered first so a doctor
        can't reach the form / orders without seeing the allergy list.
        editable=true enables inline pencil-icon edit on allergies and
        chronic conditions — the doctor can correct a wrongly-recorded
        allergy mid-visit (penicillin "allergy" that turned out to be a
        side effect) without leaving this surface. */}
    <PatientProfilePanel patientId={visit.patientId} editable />

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

    {/* Prior visits — federated patient history. Excludes the current
        visit so the page doesn't list itself. Each row navigates to
        the corresponding /visit/:id detail page. */}
    <PatientHistoryPanel
      patientId={visit.patientId}
      excludeVisitId={visit.id}
      emptyMessage="This is the patient's only visit on record at this hospital."
    />
    </div>
  );
}

// ═══════ VITALS TAB ═══════
// VitalsTab — READ-ONLY by design.
//
// Vitals are captured by the monitoring system (IoT devices, triage form,
// ambulance handover), NOT by the doctor on this screen. The previous
// "Record Vitals" button created a path for the doctor to manually log
// vitals, which both duplicated the monitoring system and broke the
// assumption that vitals always carry a source.
//
// The dedicated Monitoring page (separate route) shows the live IoT
// stream + trend; this tab is the chronological history table for
// retrospective review (e.g. "what was their HR at 04:00?", "did
// triage record an SpO2?"). The previously co-resident Monitor tab
// inside this page was removed as a duplicate of that surface.
function VitalsTab({ vitals, latestVitals, glassCard, isDark, text }: any) {
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
            Read-only chronological record. Vitals are captured by the monitoring system; the dedicated Monitoring page shows the live view.
          </p>
        </div>
        <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${isDark ? 'bg-slate-500/15 text-slate-400' : 'bg-slate-100 text-slate-500'}`}>
          {sorted.length} reading{sorted.length === 1 ? '' : 's'}
        </span>
      </div>

      {/* Latest summary band */}
      {latestVitals && (
        <div className="rounded-2xl p-4" style={glassCard}>
          <div className="flex items-center justify-between mb-3">
            <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Most recent</span>
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

      {/* Chronological history — newest first. Source badge tells the
          clinician where each reading came from, important when
          reconciling continuous IoT samples vs. one-off manual entries. */}
      <div className="space-y-3">
        {sorted.length === 0 ? (
          <div className="rounded-2xl p-8 text-center" style={glassCard}>
            <Activity className="w-8 h-8 mx-auto mb-2 text-slate-400" />
            <p className={`text-sm ${text.heading}`}>No vital signs on record yet.</p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              Vitals appear here as they are captured at triage, by an IoT monitor, or during ambulance handover.
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
              {v.weightKg != null && (
                <MiniVital label="Weight" value={`${v.weightKg}`} unit="kg" isDark={isDark} />
              )}
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
// Re-triage when a patient deteriorates lives in the dedicated retriage
// module (Dynamic Re-triage Engine), not on the chart-review surface.
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
          {/* Latest triage — colored band mirrors the triage category for
              single-glance acuity. */}
          <div className={`rounded-2xl overflow-hidden border ${catColor?.border || 'border-slate-300'}`} style={glassCard}>
            <div className={`h-2 ${catColor?.dot || 'bg-slate-400'}`} />
            <div className="p-5">
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-3 flex-wrap">
                  <span className={`w-3 h-3 rounded-full ${catColor?.dot || 'bg-slate-400'}`} />
                  <span className={`text-base font-extrabold ${catColor?.text || text.heading}`}>{latestTriage.triageCategory}</span>
                  <span className={`text-2xl font-black ${text.accent}`}>TEWS {latestTriage.tewsScore}</span>
                  {latestTriage.isSystemTriggered && (
                    <span className="text-[10px] font-extrabold uppercase tracking-wider px-2 py-1 rounded-md bg-amber-500/15 text-amber-700 border border-amber-500/40 inline-flex items-center gap-1">
                      <Sparkles className="w-3 h-3" />
                      System triggered
                    </span>
                  )}
                </div>
                <div className="text-right">
                  <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Performed</p>
                  <p className={`text-xs font-bold ${text.heading}`}>
                    {latestTriage.triageTime ? format(new Date(latestTriage.triageTime), 'dd MMM yyyy HH:mm') : '—'}
                  </p>
                </div>
              </div>
              {/* Round 3 — when the triage was created automatically by a
                  worsening clinical sign, surface the trigger right under
                  the category band so the chart explains itself. */}
              {latestTriage.isSystemTriggered && latestTriage.triggeringSignLabel && (
                <div className={`mb-3 p-2.5 rounded-xl text-[11px] flex items-start gap-2 ${isDark ? 'bg-amber-500/10' : 'bg-amber-50'} border border-amber-500/30`}>
                  <AlertTriangle className="w-3.5 h-3.5 text-amber-600 mt-0.5 flex-shrink-0" />
                  <span className={`${isDark ? 'text-amber-200' : 'text-amber-800'}`}>
                    Triggered by{' '}
                    <span className="font-bold">{latestTriage.triggeringSignLabel}</span>
                    {latestTriage.triggeringSignStatus && <> → <span className="font-bold">{latestTriage.triggeringSignStatus}</span></>}
                    {latestTriage.triggeringSignRecordedAt && <> at {format(new Date(latestTriage.triggeringSignRecordedAt), 'dd MMM HH:mm')}</>}
                  </span>
                </div>
              )}
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

          {/* Earlier triage entries, if any */}
          {triageHistory.length > 1 && (
            <div className="rounded-2xl p-5" style={glassCard}>
              <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Earlier Triage Entries</h4>
              <div className="space-y-3">
                {triageHistory.slice(1).map((t: TriageRecordResponse) => (
                  <div key={t.id} className="rounded-xl p-3" style={glassInner}>
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className={`w-2.5 h-2.5 rounded-full ${CATEGORY_COLORS[t.triageCategory]?.dot || 'bg-slate-400'}`} />
                        <span className={`text-xs font-bold ${text.heading}`}>{t.triageCategory} — TEWS {t.tewsScore}</span>
                        {t.triagedByName && <span className={`text-[10px] ${text.muted}`}>by {t.triagedByName}</span>}
                        {t.isSystemTriggered && (
                          <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-700 inline-flex items-center gap-1">
                            <Sparkles className="w-2.5 h-2.5" />
                            System
                          </span>
                        )}
                      </div>
                      <span className={`text-[10px] ${text.muted}`}>{t.triageTime ? format(new Date(t.triageTime), 'dd MMM HH:mm') : ''}</span>
                    </div>
                    {t.isSystemTriggered && t.triggeringSignLabel && (
                      <p className={`text-[10px] mt-1 ${text.muted}`}>
                        Triggered by <span className="font-semibold">{t.triggeringSignLabel}</span>
                        {t.triggeringSignStatus && <> → {t.triggeringSignStatus}</>}
                      </p>
                    )}
                    {t.decisionPath && <p className={`text-[11px] mt-1 ${text.body}`}>{t.decisionPath}</p>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      ) : (
        // Empty-state copy is deliberate: it explains who is responsible,
        // not how the doctor can start one. Triage is performed at entry
        // by the triage nurse.
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

// ═══════ NOTES TAB ═══════
//
// Comprehensive but fast: the doctor picks a Note Type, then picks one of
// the type-specific Section chips, then writes the body. No more typing
// "Assessment" or "Cardiovascular" by hand.
//
// We keep section as free text on the wire (matches the existing backend
// schema) — chips are just a fast-input affordance, the doctor can type
// a custom section if none of the suggestions fit.
function NotesTab({ notes, showForm, setShowForm, onSubmit, formLoading, glassCard, glassInner, isDark, text }: any) {
  const [form, setForm] = useState<Partial<CreateClinicalNoteRequest>>({ noteType: 'PROGRESS_NOTE' as NoteType, content: '', section: '' });

  const currentNoteType = (form.noteType || 'PROGRESS_NOTE') as NoteType;
  const sectionChips = NOTE_SECTION_SUGGESTIONS[currentNoteType] ?? [];
  const placeholder = NOTE_CONTENT_PLACEHOLDER[currentNoteType] ?? 'Clinical note content.';

  // When the user changes note type we clear the section: a chip selected
  // for one frame is rarely valid for another (e.g. "Subjective" makes no
  // sense for a HANDOVER note). Auto-clear protects against stale carry-over.
  const handleTypeChange = (nt: NoteType) => {
    setForm((f) => ({ ...f, noteType: nt, section: '' }));
  };

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
  // resultNumeric + resultUnit are the structured pair the eGFR check
  // (Phase 12b) reads. Free-text `result` is preserved alongside — it's
  // still the right field for "trace haemolysis, repeat sent" nuance.
  // Clinicians enter the principal scalar (creatinine, K+, Hb) into
  // the numeric pair so downstream calculators can use it without
  // parsing free text.
  const [resultForm, setResultForm] = useState<{
    id: string;
    result: string;
    resultNumeric?: number;
    resultUnit?: string;
    isAbnormal: boolean;
    isCritical: boolean;
    notes: string;
  } | null>(null);

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

      {/* Result form */}
      {resultForm && (
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <h4 className={`text-sm font-bold mb-4 ${text.heading}`}>Record Result</h4>
          <div className="space-y-3">
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Result (free text)</label>
              <textarea value={resultForm.result} onChange={(e) => setResultForm({ ...resultForm, result: e.target.value })} rows={3} placeholder="e.g. Cr 1.8 — moderately elevated, repeat sent" className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`} style={glassInner} />
            </div>
            {/* Structured numeric pair — feeds the Phase 12b eGFR
                calculator and any other downstream consumer. Optional;
                leaving them blank keeps the free-text result valid. */}
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Numeric value (optional)</label>
                <input
                  type="number"
                  step="any"
                  value={resultForm.resultNumeric ?? ''}
                  onChange={(e) => setResultForm({ ...resultForm, resultNumeric: e.target.value ? Number(e.target.value) : undefined })}
                  placeholder="e.g. 1.8"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
              <div>
                <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Unit (optional)</label>
                <input
                  type="text"
                  value={resultForm.resultUnit ?? ''}
                  onChange={(e) => setResultForm({ ...resultForm, resultUnit: e.target.value })}
                  placeholder="e.g. mg/dL or µmol/L"
                  className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                  style={glassInner}
                />
              </div>
            </div>
            <p className={`text-[10px] ${text.muted}`}>
              Numeric value + unit are optional, but required for the Cockcroft-Gault eGFR calculator
              to fire on creatinine. Use the unit string the lab reported (mg/dL or µmol/L).
            </p>
            <div className="flex items-center gap-4">
              <label className="flex items-center gap-2"><input type="checkbox" checked={resultForm.isAbnormal} onChange={(e) => setResultForm({ ...resultForm, isAbnormal: e.target.checked })} className="rounded" /><span className={`text-xs ${text.body}`}>Abnormal</span></label>
              <label className="flex items-center gap-2"><input type="checkbox" checked={resultForm.isCritical} onChange={(e) => setResultForm({ ...resultForm, isCritical: e.target.checked })} className="rounded" /><span className={`text-xs ${text.body}`}>Critical</span></label>
            </div>
          </div>
          <div className="flex items-center gap-3 mt-4">
            <button onClick={() => { onAction(resultForm.id, 'result', { investigationId: resultForm.id, result: resultForm.result, resultNumeric: resultForm.resultNumeric, resultUnit: resultForm.resultUnit, isAbnormal: resultForm.isAbnormal, isCritical: resultForm.isCritical, notes: resultForm.notes }); setResultForm(null); }} className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
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
            {(inv.result || inv.resultNumeric != null) && (
              <div className="mt-2 p-2.5 rounded-lg" style={glassInner}>
                {inv.resultNumeric != null && (
                  <p className={`text-sm font-extrabold ${inv.isCritical ? 'text-red-500' : inv.isAbnormal ? 'text-amber-500' : text.heading}`}>
                    {inv.resultNumeric}
                    {inv.resultUnit && <span className={`ml-1 text-xs font-medium ${text.muted}`}>{inv.resultUnit}</span>}
                  </p>
                )}
                {inv.result && (
                  <p className={`text-xs ${inv.isCritical ? 'text-red-500' : inv.isAbnormal ? 'text-amber-500' : text.body} ${inv.resultNumeric != null ? 'mt-1' : 'font-bold'}`}>{inv.result}</p>
                )}
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
function MedicationsTab({ medications, showForm, setShowForm, onSubmit, onAction, formLoading, patient, visit, latestTriage, glassCard, glassInner, isDark, text }: any) {
  // ── V67: dose-level audit per order (typed orders only) ──
  // Loaded tab-locally so the parent's data flow stays untouched; the
  // audit endpoint returns every order with its complete dose timeline.
  const [audit, setAudit] = useState<Record<string, MedicationOrderAudit>>({});
  const loadAudit = useCallback(async () => {
    if (!visit?.id) return;
    try {
      const entries = await medicationApi.getVisitAudit(visit.id);
      const map: Record<string, MedicationOrderAudit> = {};
      for (const e of entries) map[e.order.id] = e;
      setAudit(map);
    } catch (err) { console.error('Failed to load medication audit:', err); }
  }, [visit?.id]);
  useEffect(() => { void loadAudit(); }, [loadAudit, medications]);

  /** Run a V67 dose/order action; surface backend gate messages; then
   *  refresh both the audit and the parent's medication list ('refresh'
   *  hits no endpoint in handleMedicationAction — it just reloads). */
  const runDoseAction = useCallback(async (medId: string, fn: () => Promise<unknown>) => {
    try {
      await fn();
    } catch (err) {
      // eslint-disable-next-line no-alert
      window.alert(err instanceof Error ? err.message : 'Action failed');
    }
    await loadAudit();
    await onAction(medId, 'refresh');
  }, [loadAudit, onAction]);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className={`text-base font-extrabold tracking-tight ${text.heading}`}>Medications ({medications.length})</h3>
        <button onClick={() => setShowForm(!showForm)} className="inline-flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-cyan-500 to-cyan-600 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all">
          <Plus className="w-3.5 h-3.5" /> Prescribe Medication
        </button>
      </div>

      {showForm && (
        <MedicationPanel
          onSubmit={async (req) => { await onSubmit(req); setShowForm(false); }}
          onClose={() => setShowForm(false)}
          formLoading={formLoading}
          patient={patient}
          visit={visit}
          latestTriage={latestTriage}
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
        ) : medications.map((med: MedicationResponse) => {
          // The V24 column packs interactions, duplicate-therapy, and
          // paediatric dose hits together. Tag-prefixes in the snapshot
          // let the card surface a distinct badge for the most severe
          // override class without a schema change.
          const snap = med.interactionOverrideMatches ?? '';
          const hasOverdoseTag = snap.includes('[overdose]');
          const hasUnderdoseTag = snap.includes('[underdose]');
          const hasDuplicateTag = snap.includes('[duplicate]');
          const hasRenalTag = snap.includes('[renal]');
          const hasTeratogenTag = snap.includes('[teratogen]');
          const hasCategoryXTag = snap.includes('[teratogen][X]');
          // A "real" interaction line has none of our tags (legacy
          // format). We approximate "real interaction present" as:
          // flag set AND there's at least one untagged segment.
          const hasInteractionLine =
            med.prescribedDespiteInteraction &&
            snap
              .split(';')
              .some(
                (seg) =>
                  seg.trim().length > 0 &&
                  !seg.includes('[overdose]') &&
                  !seg.includes('[underdose]') &&
                  !seg.includes('[duplicate]') &&
                  !seg.includes('[renal]') &&
                  !seg.includes('[teratogen]'),
              );

          // Ring escalation, top-down:
          //   allergy / overdose / category-X teratogen (red) >
          //   non-X teratogen (rose / pink-700) >
          //   interaction (orange) > renal (violet) >
          //   duplicate / underdose (yellow).
          const ringClass = med.prescribedDespiteAllergy || hasOverdoseTag || hasCategoryXTag
            ? 'ring-2 ring-red-400/60'
            : hasTeratogenTag
              ? 'ring-2 ring-pink-400/60'
              : hasInteractionLine
                ? 'ring-2 ring-orange-400/60'
                : hasRenalTag
                  ? 'ring-2 ring-violet-400/60'
                  : (hasDuplicateTag || hasUnderdoseTag)
                    ? 'ring-2 ring-yellow-400/60'
                    : '';

          return (
          <div
            key={med.id}
            className={`rounded-2xl p-4 ${ringClass}`}
            style={glassCard}
          >
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg ${MEDICATION_STATUS_COLORS[med.status] || ''}`}>{med.status}</span>
                <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg text-slate-500 bg-slate-500/10`}>{med.route}</span>
                {med.prescribedDespiteAllergy && (
                  <span
                    className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-red-500/15 text-red-600 border border-red-500/40 inline-flex items-center gap-1"
                    title={med.allergyOverrideMatches ?? 'Prescribed against a known allergy'}
                  >
                    <AlertTriangle className="w-3 h-3" />
                    Allergy override
                  </span>
                )}
                {hasOverdoseTag && (
                  <span
                    className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-red-500/15 text-red-600 border border-red-500/40 inline-flex items-center gap-1"
                    title={snap}
                  >
                    <AlertTriangle className="w-3 h-3" />
                    Overdose override
                  </span>
                )}
                {hasInteractionLine && (
                  <span
                    className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-orange-500/15 text-orange-600 border border-orange-500/40 inline-flex items-center gap-1"
                    title={snap}
                  >
                    <AlertTriangle className="w-3 h-3" />
                    Interaction override
                  </span>
                )}
                {hasUnderdoseTag && !hasOverdoseTag && (
                  <span
                    className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-blue-500/15 text-blue-600 border border-blue-500/40 inline-flex items-center gap-1"
                    title={snap}
                  >
                    <AlertTriangle className="w-3 h-3" />
                    Underdose override
                  </span>
                )}
                {hasRenalTag && (
                  <span
                    className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-violet-500/15 text-violet-600 border border-violet-500/40 inline-flex items-center gap-1"
                    title={snap}
                  >
                    <AlertTriangle className="w-3 h-3" />
                    Renal override
                  </span>
                )}
                {hasTeratogenTag && (
                  <span
                    className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg inline-flex items-center gap-1 ${
                      hasCategoryXTag
                        ? 'bg-red-500/15 text-red-600 border border-red-500/40'
                        : 'bg-pink-500/15 text-pink-600 border border-pink-500/40'
                    }`}
                    title={snap}
                  >
                    <AlertTriangle className="w-3 h-3" />
                    {hasCategoryXTag ? 'Category X override' : 'Pregnancy override'}
                  </span>
                )}
              </div>
              <span className={`text-[10px] ${text.muted}`}>{med.prescribedAt ? format(new Date(med.prescribedAt), 'dd MMM HH:mm') : ''}</span>
            </div>
            <p className={`text-sm font-medium ${text.heading} inline-flex items-center gap-2 flex-wrap`}>
              {med.drugName} {med.dose && `— ${med.dose}`}
              {/* Workflow 3 — priority badge. STAT is the loudest
                  visual so a distracted clinician spots time-critical
                  orders at a glance. ROUTINE is muted (default state)
                  so the page isn't a sea of green chips. */}
              {med.priority === 'STAT' && (
                <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-100 text-red-800 border border-red-300">
                  STAT
                </span>
              )}
              {med.priority === 'URGENT' && (
                <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-orange-100 text-orange-800 border border-orange-300">
                  Urgent
                </span>
              )}
            </p>
            {med.frequency && <p className={`text-xs ${text.body}`}>{med.frequency}</p>}
            {/* ── V67: typed-order summary + dose timeline ── */}
            {med.prescriptionType && (
              <TypedOrderDetails med={med} entry={audit[med.id]} text={text} />
            )}
            {med.prescribedDespiteAllergy && med.allergyOverrideMatches && (
              <p className="text-[10px] text-red-600 mt-1.5 font-medium break-words">
                Allergy: {med.allergyOverrideMatches}
              </p>
            )}
            {med.prescribedDespiteInteraction && med.interactionOverrideMatches && (
              <p
                className={`text-[10px] mt-1 font-medium break-words ${
                  hasCategoryXTag || hasOverdoseTag
                    ? 'text-red-600'
                    : hasTeratogenTag
                      ? 'text-pink-700'
                      : hasInteractionLine
                        ? 'text-orange-600'
                        : hasRenalTag
                          ? 'text-violet-700'
                          : 'text-yellow-700'
                }`}
              >
                {/* Pick the most-severe class present and label
                    accordingly. Multiple tags is rare but possible
                    (e.g. NSAID prescribed in shock + same patient
                    already on another NSAID → renal + duplicate). */}
                {hasCategoryXTag
                  ? 'Category X'
                  : hasTeratogenTag
                    ? 'Pregnancy'
                    : hasOverdoseTag
                      ? 'Dose / interaction'
                      : hasInteractionLine
                        ? 'Interaction'
                        : hasRenalTag && hasDuplicateTag
                          ? 'Renal / duplicate'
                          : hasRenalTag
                            ? 'Renal'
                            : hasDuplicateTag && hasUnderdoseTag
                              ? 'Duplicate / dose'
                              : hasDuplicateTag
                                ? 'Duplicate'
                                : 'Dose'}
                : {med.interactionOverrideMatches}
              </p>
            )}
            {med.administeredByName && <p className={`text-[10px] mt-1 text-emerald-500`}>Administered by: {med.administeredByName}</p>}
            {med.countersignedByName && <p className={`text-[10px] text-violet-500`}>Countersigned by: {med.countersignedByName}</p>}
            {/* Actions */}
            <div className="flex items-center gap-2 mt-3 flex-wrap">
              {/* V67 — high-alert approval gate */}
              {med.status === 'PENDING_APPROVAL' && (
                <button
                  onClick={() => runDoseAction(med.id, () => medicationApi.approve(med.id, {}))}
                  className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-red-500/10 text-red-600 hover:bg-red-500/20 transition-colors"
                  title="Charge nurse / doctor approval — the prescriber cannot approve their own order"
                >
                  <CheckCircle2 className="w-3 h-3 inline mr-1" /> Approve (charge)
                </button>
              )}
              {/* V67 — typed dose workflow (scheduled/PRN/continuous, or
                  witness-required one-time). Plain one-time orders keep
                  the legacy buttons below. */}
              {med.status === 'PRESCRIBED' && med.prescriptionType
                && (med.prescriptionType !== 'ONE_TIME' || med.requiresWitness) && (
                <TypedOrderActions
                  med={med}
                  entry={audit[med.id]}
                  runDoseAction={runDoseAction}
                />
              )}
              {med.status === 'HELD' && med.prescriptionType && (
                <button
                  onClick={() => runDoseAction(med.id, () => medicationApi.resume(med.id))}
                  className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-600 hover:bg-emerald-500/20 transition-colors"
                >
                  Resume
                </button>
              )}
              {(med.status === 'PRESCRIBED' || med.status === 'PENDING_APPROVAL' || med.status === 'HELD')
                && med.prescriptionType && (
                <button
                  onClick={() => {
                    // eslint-disable-next-line no-alert
                    const reason = window.prompt('Discontinue reason (required)');
                    if (reason && reason.trim().length >= 3) {
                      void runDoseAction(med.id, () =>
                        medicationApi.discontinue(med.id, { reason: reason.trim() }));
                    }
                  }}
                  className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-slate-500/10 text-slate-500 hover:bg-slate-500/20 transition-colors"
                  title="Doctor stops this order — reason is recorded in the audit trail"
                >
                  Discontinue
                </button>
              )}
              {med.status === 'PRESCRIBED'
                && !(med.prescriptionType && (med.prescriptionType !== 'ONE_TIME' || med.requiresWitness)) && (
                <>
                  <button onClick={() => onAction(med.id, 'administer')} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors">
                    <CheckCircle2 className="w-3 h-3 inline mr-1" /> Administer
                  </button>
                  {/* Workflow 3 — Hold / Refuse buttons. The backend
                      endpoints already exist; this surfaces them so a
                      nurse can document a hold (e.g. NPO before
                      procedure) or a refusal (patient declined)
                      without having to bypass the system. */}
                  <button
                    onClick={() => {
                      // eslint-disable-next-line no-alert
                      const reason = window.prompt('Hold reason (e.g. NPO before procedure, awaiting labs)');
                      if (reason && reason.trim().length >= 3) {
                        onAction(med.id, 'hold', reason.trim());
                      }
                    }}
                    className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-amber-500/10 text-amber-600 hover:bg-amber-500/20 transition-colors"
                    title="Hold this medication with a documented reason"
                  >
                    Hold
                  </button>
                  <button
                    onClick={() => {
                      // eslint-disable-next-line no-alert
                      const reason = window.prompt('Refusal reason (patient declined / unable to take)');
                      if (reason && reason.trim().length >= 3) {
                        onAction(med.id, 'refuse', reason.trim());
                      }
                    }}
                    className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-red-500/10 text-red-600 hover:bg-red-500/20 transition-colors"
                    title="Record that the patient refused this medication"
                  >
                    Refuse
                  </button>
                </>
              )}
              {med.status === 'ADMINISTERED' && !med.countersignedByName && (
                <button onClick={() => onAction(med.id, 'countersign')} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-violet-500/10 text-violet-500 hover:bg-violet-500/20 transition-colors">
                  <CheckCircle2 className="w-3 h-3 inline mr-1" /> Countersign
                </button>
              )}
            </div>
            <p className={`text-[10px] mt-2 ${text.muted}`}>Prescribed by: {med.prescribedByName}</p>
          </div>
          );
        })}
      </div>
    </div>
  );
}

// ═══════ V67: TYPED-ORDER DETAILS (schedule summary + dose timeline) ═══════
function TypedOrderDetails({ med, entry, text }: {
  med: MedicationResponse; entry?: MedicationOrderAudit; text: any;
}) {
  const doses: MedicationDoseResponse[] = entry?.doses ?? [];
  const given = doses.filter((d) => d.status === 'GIVEN' && !d.kind.startsWith('INFUSION')).length;
  const nextDue = doses.find((d) => d.status === 'DUE');
  const typeLabel = med.prescriptionType === 'SCHEDULED'
    ? `Scheduled — every ${med.intervalHours} h${med.maxDoses ? ` × ${med.maxDoses} doses` : ''}`
    : med.prescriptionType === 'PRN'
      ? `PRN — ${med.prnIndication}${med.prnMinIntervalHours ? ` · min ${med.prnMinIntervalHours} h apart` : ''}${med.prnMaxDosesPerDay ? ` · max ${med.prnMaxDosesPerDay}/24h` : ''}`
      : med.prescriptionType === 'CONTINUOUS'
        ? `Continuous — ${med.rateValue} ${med.rateUnit}`
        : 'One-time';

  return (
    <div className="mt-2 space-y-1.5">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-cyan-500/10 text-cyan-600 border border-cyan-500/30">
          {typeLabel}
        </span>
        {med.productType && med.productType !== 'DRUG' && (
          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-rose-500/10 text-rose-600 border border-rose-500/30">
            {med.productType.replace('_', ' ')}{med.productDetail ? ` — ${med.productDetail}` : ''}
          </span>
        )}
        {med.requiresWitness && (
          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-amber-500/10 text-amber-700 border border-amber-500/30">
            Witness required
          </span>
        )}
        {med.gateParameter && (
          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-sky-500/10 text-sky-600 border border-sky-500/30">
            Only if {med.gateParameter.replace('_', ' ')} {med.gateComparator === 'GTE' ? '≥' : '≤'} {med.gateThreshold}
          </span>
        )}
        {med.emergencyOverride && (
          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-red-500/10 text-red-600 border border-red-500/40"
            title={med.emergencyJustification ?? ''}>
            Emergency override
          </span>
        )}
        {med.approvedByName && (
          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg bg-emerald-500/10 text-emerald-600 border border-emerald-500/30">
            Approved by {med.approvedByName}
          </span>
        )}
      </div>
      <p className={`text-[11px] ${text.body}`}>
        {med.prescriptionType !== 'CONTINUOUS' && <>{given} dose{given === 1 ? '' : 's'} given</>}
        {med.maxDoses != null && <> of {med.maxDoses}</>}
        {nextDue?.dueAt && <> · next due {format(new Date(nextDue.dueAt), 'dd MMM HH:mm')}</>}
        {med.status === 'DISCONTINUED' && med.discontinueReason && (
          <> · discontinued: {med.discontinueReason} ({med.discontinuedByName})</>
        )}
        {med.supersededById && <> · modified (superseded by a replacement order)</>}
        {med.supersedesId && <> · replaces an earlier order</>}
      </p>
      {doses.length > 0 && (
        <details className="text-[11px]">
          <summary className={`cursor-pointer font-semibold ${text.muted}`}>
            Dose log ({doses.length})
          </summary>
          <ul className={`mt-1 space-y-0.5 pl-3 border-l border-slate-300/30 ${text.body}`}>
            {doses.map((d) => (
              <li key={d.id}>
                <span className="font-semibold">
                  {d.sequenceNumber != null ? `#${d.sequenceNumber} ` : ''}
                  {d.kind.replace(/_/g, ' ').toLowerCase()}
                </span>
                {' — '}
                <span className={
                  d.status === 'GIVEN' ? 'text-emerald-600'
                    : d.status === 'MISSED' ? 'text-red-600 font-bold'
                    : d.status === 'REFUSED' ? 'text-rose-600'
                    : d.status === 'DUE' ? 'text-amber-600'
                    : ''
                }>{d.status}</span>
                {d.dueAt && d.status === 'DUE' && <> · due {format(new Date(d.dueAt), 'dd MMM HH:mm')}{d.delayCount > 0 && ` (delayed ×${d.delayCount})`}</>}
                {d.givenAt && <> · {format(new Date(d.givenAt), 'dd MMM HH:mm')} by {d.givenByName}</>}
                {d.witnessName && <> · witness {d.witnessName}</>}
                {d.rateValue != null && <> · {d.rateValue} {d.rateUnit}</>}
                {d.prnReason && <> · for {d.prnReason}</>}
                {d.gateEvaluation && <> · {d.gateEvaluation}</>}
                {d.isOverride && <span className="text-red-600 font-semibold"> · OVERRIDE: {d.overrideJustification}</span>}
                {d.statusReason && <> · {d.statusReason}</>}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}

// ═══════ V67: TYPED-ORDER ACTIONS (dose-level workflow) ═══════
function TypedOrderActions({ med, entry, runDoseAction }: {
  med: MedicationResponse;
  entry?: MedicationOrderAudit;
  runDoseAction: (medId: string, fn: () => Promise<unknown>) => Promise<void>;
}) {
  const doses = entry?.doses ?? [];
  const nextDue = doses.find((d) => d.status === 'DUE');
  const infusionEvents = doses.filter((d) => d.kind.startsWith('INFUSION'));
  const lastInfusion = infusionEvents[infusionEvents.length - 1];
  const infusionRunning = !!lastInfusion && lastInfusion.kind !== 'INFUSION_STOP';

  /** Witness prompt shared by every administering action. */
  const promptWitness = (): string | null | undefined => {
    if (!med.requiresWitness) return undefined;
    // eslint-disable-next-line no-alert
    const w = window.prompt(
      med.productType === 'BLOOD_PRODUCT'
        ? 'Blood product — witness (second clinician) full name, REQUIRED:'
        : 'Witness (second clinician) full name, REQUIRED:');
    return w && w.trim() ? w.trim() : null; // null = abort
  };

  return (
    <>
      {(med.prescriptionType === 'SCHEDULED' || med.prescriptionType === 'ONE_TIME') && nextDue && (
        <>
          <button
            onClick={() => {
              const witness = promptWitness();
              if (witness === null) return;
              void runDoseAction(med.id, () => medicationApi.administerDose(nextDue.id, {
                witnessName: witness,
              }));
            }}
            className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors"
            title={`Give dose #${nextDue.sequenceNumber ?? ''} now (verification + gates run server-side)`}
          >
            <CheckCircle2 className="w-3 h-3 inline mr-1" /> Give dose{nextDue.sequenceNumber != null ? ` #${nextDue.sequenceNumber}` : ''}
          </button>
          <button
            onClick={() => {
              // eslint-disable-next-line no-alert
              const mins = window.prompt('Delay by how many minutes? (15–720)', '60');
              if (!mins) return;
              // eslint-disable-next-line no-alert
              const reason = window.prompt('Delay reason (required)');
              if (!reason || reason.trim().length < 3) return;
              void runDoseAction(med.id, () => medicationApi.delayDose(nextDue.id, {
                delayMinutes: Number(mins), reason: reason.trim(),
              }));
            }}
            className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-amber-500/10 text-amber-600 hover:bg-amber-500/20 transition-colors"
          >
            Delay dose
          </button>
          <button
            onClick={() => {
              // eslint-disable-next-line no-alert
              const reason = window.prompt('Refusal reason — the order stays active for the next dose:');
              if (!reason || reason.trim().length < 3) return;
              void runDoseAction(med.id, () => medicationApi.refuseDose(nextDue.id, {
                reason: reason.trim(),
              }));
            }}
            className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-red-500/10 text-red-600 hover:bg-red-500/20 transition-colors"
          >
            Dose refused
          </button>
        </>
      )}

      {med.prescriptionType === 'PRN' && (
        <button
          onClick={() => {
            // eslint-disable-next-line no-alert
            const indication = window.prompt(
              `PRN indication (what triggered this dose)? Order is for: ${med.prnIndication ?? ''}`);
            if (!indication || !indication.trim()) return;
            const witness = promptWitness();
            if (witness === null) return;
            void runDoseAction(med.id, () => medicationApi.recordPrnDose(med.id, {
              prnReason: indication.trim(), witnessName: witness,
            }));
          }}
          className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-violet-500/10 text-violet-600 hover:bg-violet-500/20 transition-colors"
          title="Interval, daily cap and vitals gate are enforced server-side"
        >
          Give PRN dose
        </button>
      )}

      {med.prescriptionType === 'CONTINUOUS' && !infusionRunning && (
        <button
          onClick={() => {
            const witness = promptWitness();
            if (witness === null) return;
            void runDoseAction(med.id, () => medicationApi.startInfusion(med.id, {
              witnessName: witness,
            }));
          }}
          className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-cyan-500/10 text-cyan-600 hover:bg-cyan-500/20 transition-colors"
        >
          {lastInfusion ? 'Restart infusion' : 'Start infusion'}
        </button>
      )}
      {med.prescriptionType === 'CONTINUOUS' && infusionRunning && (
        <>
          <button
            onClick={() => {
              // eslint-disable-next-line no-alert
              const rate = window.prompt(`New rate (${med.rateUnit ?? 'mL/hr'})?`,
                String(lastInfusion?.rateValue ?? med.rateValue ?? ''));
              if (!rate || !Number(rate)) return;
              void runDoseAction(med.id, () => medicationApi.changeInfusionRate(med.id, {
                rateValue: Number(rate),
              }));
            }}
            className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-sky-500/10 text-sky-600 hover:bg-sky-500/20 transition-colors"
          >
            Change rate
          </button>
          <button
            onClick={() => {
              // eslint-disable-next-line no-alert
              const reason = window.prompt('Stop infusion — reason (required):');
              if (!reason || reason.trim().length < 3) return;
              void runDoseAction(med.id, () => medicationApi.stopInfusion(med.id, {
                reason: reason.trim(),
              }));
            }}
            className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-rose-500/10 text-rose-600 hover:bg-rose-500/20 transition-colors"
          >
            Stop infusion
          </button>
        </>
      )}

      {/* Hold works for every live typed order (open doses are
          cancelled; Resume re-creates a due dose). */}
      <button
        onClick={() => {
          // eslint-disable-next-line no-alert
          const reason = window.prompt('Hold reason (e.g. NPO before procedure)');
          if (reason && reason.trim().length >= 3) {
            void runDoseAction(med.id, () => medicationApi.hold(med.id, reason.trim()));
          }
        }}
        className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-amber-500/10 text-amber-600 hover:bg-amber-500/20 transition-colors"
      >
        Hold
      </button>
    </>
  );
}

// ═══════ MONITOR TAB ═══════
// ═══════ ALERTS TAB ═══════
function AlertsTab({ alerts, onAcknowledge, visit, navigate, glassCard, glassInner: _glassInner, isDark: _isDark, text }: any) {
  // RBAC — same gate as the Overview-tab banner: only users whose
  // TODAY'S shift gives them triage authority can click "Re-triage now".
  const canTriage = useCanPerformTriage();
  const severityColors: Record<string, string> = {
    CRITICAL: 'text-red-500 bg-red-500/10 border-red-500/20',
    HIGH: 'text-orange-500 bg-orange-500/10 border-orange-500/20',
    MEDIUM: 'text-amber-500 bg-amber-500/10 border-amber-500/20',
    LOW: 'text-blue-500 bg-blue-500/10 border-blue-500/20',
    INFO: 'text-slate-500 bg-slate-500/10 border-slate-500/20',
  };

  /**
   * Round 4a — RETRIAGE_REQUIRED alert click-through.
   *
   * Routes the nurse to the appropriate triage form (adult vs.
   * pediatric, decided by `visit.isPediatric`) with two query
   * params:
   *   - `fromAlert=<alertId>` so the form can ack the alert on submit.
   *   - `triggerSign=<code>` so the form pre-flags the matching boolean.
   *   - `visitId=<id>` so the form can submit against the right visit
   *     even though the route is patient-scoped.
   */
  const goToRetriage = (a: ClinicalAlertResponse) => {
    if (!visit?.patientId) return;
    const path = visit.isPediatric ? '/pediatric-triage' : '/adult-triage';
    const params = new URLSearchParams();
    params.set('fromAlert', a.id);
    params.set('visitId', visit.id);
    if (a.triggeringSignCode) params.set('triggerSign', a.triggeringSignCode);
    navigate(`${path}/${visit.patientId}?${params.toString()}`);
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
        ) : alerts.map((a: ClinicalAlertResponse) => {
          const isRetriage = a.alertType === 'RETRIAGE_REQUIRED' && !a.acknowledged;
          return (
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
            {/* Round 4a — surface the trigger sign label inline so the
                nurse sees what to look at before clicking through. */}
            {a.triggeringSignLabel && (
              <p className={`text-[11px] mt-1 ${text.muted}`}>
                Trigger: <span className="font-semibold">{a.triggeringSignLabel}</span>
              </p>
            )}
            <div className="flex items-center gap-2 mt-3 flex-wrap">
              {isRetriage && (
                <button
                  onClick={() => goToRetriage(a)}
                  disabled={!canTriage}
                  title={canTriage
                    ? 'Open the re-triage form for this patient'
                    : 'Your current shift does not authorise triage. The Triage Nurse or Charge Nurse on duty will pick this up.'}
                  className={`px-3 py-1.5 text-[10px] font-bold rounded-lg shadow-md transition-all inline-flex items-center gap-1 ${
                    canTriage
                      ? 'bg-gradient-to-r from-cyan-500 to-cyan-600 text-white hover:-translate-y-0.5'
                      : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                  }`}
                >
                  <Stethoscope className="w-3 h-3" /> {canTriage ? 'Re-triage now' : 'Triage authority required'}
                </button>
              )}
              {!a.acknowledged && (
                <button onClick={() => onAcknowledge(a.id)} className="px-3 py-1.5 text-[10px] font-bold rounded-lg bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors">
                  <CheckCircle2 className="w-3 h-3 inline mr-1" /> Acknowledge
                </button>
              )}
            </div>
            {a.acknowledged && (
              <p className={`text-[10px] mt-2 text-emerald-500`}>Acknowledged by {a.acknowledgedByName} at {a.acknowledgedAt ? format(new Date(a.acknowledgedAt), 'dd MMM HH:mm') : ''}</p>
            )}
          </div>
          );
        })}
      </div>
    </div>
  );
}

// ═══════ HELPER COMPONENTS ═══════

// ─── PendingTransferBanner ────────────────────────────────────────
//
// Renders the "incoming / outgoing" notice for an inter-zone transfer
// awaiting acceptance, with three CTAs: Accept, Treat in place
// (RESUS_IN_PLACE), Decline. Keeps the doctor's hand on the wheel —
// no zone change happens silently.
//
// Defined inline rather than in its own file because it's tightly
// coupled to OverviewTab's text/glass styling props.
function PendingTransferBanner({
  transfer, reload, text,
}: {
  transfer: import('@/api/zoneTransfers').ZoneTransferResponse;
  reload: () => Promise<void> | void;
  text: any;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showHandover, setShowHandover] = useState(false);
  const [handover, setHandover] = useState('');
  const [showDecline, setShowDecline] = useState(false);
  const [declineReason, setDeclineReason] = useState('');

  const handleAccept = async () => {
    setBusy(true); setError(null);
    try {
      const { zoneTransferApi } = await import('@/api/zoneTransfers');
      await zoneTransferApi.accept(transfer.id, handover.trim() || undefined);
      await reload();
    } catch (e: any) {
      setError(e?.message ?? 'Failed to accept transfer');
    } finally { setBusy(false); }
  };

  const handleResusInPlace = async () => {
    setBusy(true); setError(null);
    try {
      const { zoneTransferApi } = await import('@/api/zoneTransfers');
      await zoneTransferApi.markResusInPlace(transfer.id, handover.trim() || undefined);
      await reload();
    } catch (e: any) {
      setError(e?.message ?? 'Failed to mark resus-in-place');
    } finally { setBusy(false); }
  };

  const handleDecline = async () => {
    if (!declineReason.trim()) {
      setError('Decline reason is required.');
      return;
    }
    setBusy(true); setError(null);
    try {
      const { zoneTransferApi } = await import('@/api/zoneTransfers');
      await zoneTransferApi.decline(transfer.id, declineReason.trim());
      await reload();
    } catch (e: any) {
      setError(e?.message ?? 'Failed to decline transfer');
    } finally { setBusy(false); }
  };

  return (
    <div className="rounded-2xl p-4 border border-amber-500/40 bg-amber-500/10 flex flex-col gap-3 animate-fade-up">
      <div className="flex items-start gap-3">
        <RefreshCw className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-amber-900">
            Pending zone transfer: {transfer.fromZone ?? '—'} → {transfer.toZone}
          </p>
          <p className={`text-xs mt-0.5 ${text.body}`}>
            {transfer.reason ?? 'Inter-zone move requested.'} The original team
            remains responsible until a receiving doctor accepts.
          </p>
          {transfer.proposedClinicianName && (
            <p className={`text-[11px] mt-0.5 ${text.muted}`}>
              Proposed receiver: <span className="font-semibold">{transfer.proposedClinicianName}</span>
            </p>
          )}
        </div>
      </div>

      {showHandover && (
        <div className="ml-8">
          <label className="text-[10px] font-bold uppercase tracking-wider text-amber-900">SBAR handover (optional)</label>
          <textarea
            value={handover}
            onChange={(e) => setHandover(e.target.value)}
            rows={2}
            placeholder="Situation · Background · Assessment · Recommendation"
            className="w-full mt-1 px-2 py-1.5 text-sm rounded-md border border-amber-500/40 bg-white"
          />
        </div>
      )}

      {showDecline && (
        <div className="ml-8">
          <label className="text-[10px] font-bold uppercase tracking-wider text-amber-900">Decline reason</label>
          <input
            value={declineReason}
            onChange={(e) => setDeclineReason(e.target.value)}
            placeholder="e.g. Resus full — escalate via charge nurse"
            className="w-full mt-1 px-2 py-1.5 text-sm rounded-md border border-amber-500/40 bg-white"
          />
        </div>
      )}

      {error && <p className="ml-8 text-[11px] text-red-600 font-semibold">{error}</p>}

      <div className="ml-8 flex flex-wrap gap-2">
        {!showDecline && (
          <button
            type="button"
            disabled={busy}
            onClick={async () => {
              if (!showHandover) { setShowHandover(true); return; }
              await handleAccept();
            }}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50"
          >
            <CheckCircle2 className="w-3.5 h-3.5" />
            {showHandover ? 'Accept transfer' : 'Accept'}
          </button>
        )}
        {!showDecline && (
          <button
            type="button"
            disabled={busy}
            onClick={async () => {
              if (!showHandover) { setShowHandover(true); return; }
              await handleResusInPlace();
            }}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-amber-600 text-white hover:bg-amber-700 disabled:opacity-50"
            title="Treat patient at higher acuity in their current physical location"
          >
            <AlertTriangle className="w-3.5 h-3.5" />
            Treat in place
          </button>
        )}
        {!showHandover && (
          <button
            type="button"
            disabled={busy}
            onClick={async () => {
              if (!showDecline) { setShowDecline(true); return; }
              await handleDecline();
            }}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-white text-red-700 border border-red-500/40 hover:bg-red-50 disabled:opacity-50"
          >
            <XCircle className="w-3.5 h-3.5" />
            {showDecline ? 'Confirm decline' : 'Decline'}
          </button>
        )}
        {(showHandover || showDecline) && (
          <button
            type="button"
            disabled={busy}
            onClick={() => { setShowHandover(false); setShowDecline(false); setError(null); }}
            className={`text-[11px] font-bold px-2 py-1 ${text.muted}`}
          >
            Cancel
          </button>
        )}
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
