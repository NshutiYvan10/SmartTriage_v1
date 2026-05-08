import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle, AlertCircle, CheckCircle, Clock, Shield, Heart,
  Wind, Eye, Activity, User, FileText, Users, Droplets,
  ChevronDown, ChevronUp, ChevronLeft, ChevronRight, Bell, Stethoscope, Brain,
  ArrowLeft, Save, Timer, Sparkles, BedDouble,
} from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';
import { useAuditStore } from '@/store/auditStore';
import { useTEWSHistoryStore } from '@/store/tewsHistoryStore';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';
import { triageApi } from '@/api/triage';
import { bedsApi } from '@/api/beds';
import type { EdZone } from '@/api/types';
import { BedSuggestionModal } from './BedSuggestionModal';
import { alertApi } from '@/api/alerts';
import { vitalApi } from '@/api/vitals';
import {
  validateTEWSInputs,
  getAbnormalValidations,
  hasImpossibleValues as checkImpossible,
  getValidationBgColor,
} from '@/utils/vitalValidation';
import { getScoreBreakdownText } from '@/utils/tewsTrend';
import {
  calculateAdultTEWS,
  getAdultTEWSColumns,
  determineAdultCategory,
  getAdultNormalRanges,
  getAdultVitalStatus,
  type AdultTEWSInput,
  type AdultMobility,
  type AdultAVPU,
  type AdultTriageCategory,
  type AdultCategoryResult,
} from '@/utils/adultTEWS';
import {
  VERY_URGENT_DISCRIMINATORS,
  URGENT_DISCRIMINATORS,
  hasCheckedDiscriminators,
  getCheckedDiscriminatorLabels,
  isDiscriminatorRequired,
  type DiscriminatorGroup,
} from '@/utils/discriminators';

// ════════════════════════════════════════════════════════════
// Emergency Signs definitions - Adult-specific
// ════════════════════════════════════════════════════════════

interface EmergencySignItem {
  id: string;
  label: string;
  hasField?: boolean;
  fieldLabel?: string;
  fieldUnit?: string;
  fieldType?: 'number' | 'text' | 'select';
  selectOptions?: string[];
}

interface EmergencySignGroup {
  title: string;
  icon: typeof AlertTriangle;
  color: string;
  bgColor: string;
  signs: EmergencySignItem[];
}

const EMERGENCY_SIGN_GROUPS: EmergencySignGroup[] = [
  {
    title: 'Airway & Cervical Spine',
    icon: Wind,
    color: 'text-red-600',
    bgColor: 'bg-red-50',
    signs: [
      { id: 'patent_airway', label: 'Patent airway' },
      { id: 'cervical_immobilization', label: 'Cervical spine immobilization (if indicated)' },
      { id: 'obstruction_stridor', label: 'Obstruction / Stridor' },
    ],
  },
  {
    title: 'Breathing',
    icon: Wind,
    color: 'text-blue-600',
    bgColor: 'bg-blue-50',
    signs: [
      { id: 'respiratory_rate', label: 'Respiratory rate', hasField: true, fieldLabel: 'RR', fieldUnit: '/min', fieldType: 'number' },
      { id: 'increased_wob', label: 'Increased work of breathing' },
      { id: 'accessory_muscles', label: 'Use of accessory muscles' },
      { id: 'apnoea_gasping', label: 'Apnoea / Gasping / Irregular breathing' },
      { id: 'central_cyanosis', label: 'Central cyanosis' },
      { id: 'oxygen_saturation', label: 'Oxygen saturation', hasField: true, fieldLabel: 'SpO₂', fieldUnit: '%', fieldType: 'number' },
      { id: 'breath_sounds', label: 'Breath sounds', hasField: true, fieldLabel: 'Description', fieldUnit: '', fieldType: 'text' },
      { id: 'tracheal_deviation', label: 'Tracheal deviation' },
    ],
  },
  {
    title: 'Circulation',
    icon: Heart,
    color: 'text-rose-600',
    bgColor: 'bg-rose-50',
    signs: [
      { id: 'heart_rate', label: 'Heart rate', hasField: true, fieldLabel: 'HR', fieldUnit: 'bpm', fieldType: 'number' },
      { id: 'central_pulses', label: 'Central pulses present' },
      { id: 'peripheral_pulses', label: 'Peripheral pulses present' },
      { id: 'cap_refill_gt3', label: 'Capillary refill time > 3 seconds' },
      { id: 'cold_peripheries', label: 'Cold peripheries' },
      { id: 'blood_pressure', label: 'Blood pressure', hasField: true, fieldLabel: 'BP', fieldUnit: 'mmHg', fieldType: 'text' },
      { id: 'reduced_skin_turgor', label: 'Reduced skin turgor' },
      { id: 'dry_mucous_membranes', label: 'Dry mucous membranes' },
      { id: 'chest_pain', label: 'Chest pain' },
      { id: 'active_bleeding', label: 'Active / uncontrolled bleeding' },
    ],
  },
  {
    title: 'Disability',
    icon: Brain,
    color: 'text-cyan-600',
    bgColor: 'bg-cyan-50',
    signs: [
      { id: 'avpu', label: 'AVPU', hasField: true, fieldLabel: 'Level', fieldUnit: '', fieldType: 'select', selectOptions: ['Alert', 'Voice', 'Pain', 'Unresponsive'] },
      { id: 'gcs', label: 'GCS', hasField: true, fieldLabel: 'Score', fieldUnit: '/15', fieldType: 'number' },
      { id: 'pupils', label: 'Pupils', hasField: true, fieldLabel: 'Size & reaction', fieldUnit: '', fieldType: 'text' },
      { id: 'posturing', label: 'Posturing / Seizures' },
      { id: 'neck_stiffness', label: 'Neck stiffness / Meningism' },
      { id: 'blood_glucose', label: 'Blood glucose', hasField: true, fieldLabel: 'Glucose', fieldUnit: 'mmol/L', fieldType: 'number' },
      { id: 'focal_neuro_deficit', label: 'Focal neurological deficit' },
    ],
  },
  {
    title: 'Exposure',
    icon: Eye,
    color: 'text-amber-600',
    bgColor: 'bg-amber-50',
    signs: [
      { id: 'rash_petechiae', label: 'Rash / Petechiae / Purpura' },
      { id: 'fever_history', label: 'Fever (history)' },
      { id: 'hypothermia', label: 'Hypothermia' },
      { id: 'bruising_bleeding', label: 'Bruising / Bleeding' },
      { id: 'oedema', label: 'Oedema' },
      { id: 'weight', label: 'Weight', hasField: true, fieldLabel: 'Weight', fieldUnit: 'kg', fieldType: 'number' },
      { id: 'allergies', label: 'Known allergies', hasField: true, fieldLabel: 'Details', fieldUnit: '', fieldType: 'text' },
    ],
  },
];

const CRITICAL_SIGN_IDS = [
  'obstruction_stridor', 'apnoea_gasping', 'central_cyanosis', 'accessory_muscles',
  'increased_wob', 'cap_refill_gt3', 'cold_peripheries', 'posturing',
  'neck_stiffness', 'tracheal_deviation', 'active_bleeding', 'focal_neuro_deficit',
];

// ════════════════════════════════════════════════════════════
// TEWS Table Constants - Adult ranges
// ════════════════════════════════════════════════════════════

const TEWS_COLUMNS = [3, 2, 1, 0, 1, 2, 3];

interface TEWSRowDef { key: string; label: string; cells: (string | null)[]; }

const TEWS_ROWS: TEWSRowDef[] = [
  { key: 'mobility', label: 'Mobility', cells: [null, null, 'Stretcher', 'Walking', 'With help', null, null] },
  { key: 'rr', label: 'RR', cells: [null, '< 9', null, '9–14', '15–20', '21–29', '≥ 30'] },
  { key: 'hr', label: 'P (HR)', cells: [null, '< 41', '41–50', '51–100', '101–110', '111–129', '≥ 130'] },
  { key: 'sbp', label: 'SBP', cells: [null, '< 71', '71–80', '81–100', '101–199', '≥ 200', null] },
  { key: 'temp', label: 'Temp', cells: [null, 'Cold / < 35', null, '35–38.4', 'Hot / > 38.4', null, null] },
  { key: 'avpu', label: 'AVPU', cells: [null, null, null, 'Alert', 'Voice', 'Pain', 'Unresponsive'] },
  { key: 'trauma', label: 'Trauma', cells: [null, null, null, 'No', 'Yes', null, null] },
];

function columnToTableIndex(col: number): number { return col + 3; }

// ════════════════════════════════════════════════════════════
// Category Timer
// ════════════════════════════════════════════════════════════

function CategoryTimer({ category, startedAt }: { category: AdultTriageCategory; startedAt: Date }) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const interval = setInterval(() => setElapsed(Math.floor((Date.now() - startedAt.getTime()) / 1000)), 1000);
    return () => clearInterval(interval);
  }, [startedAt]);

  const limits: Record<AdultTriageCategory, number> = { RED: 0, ORANGE: 600, YELLOW: 1800, GREEN: 3600 };
  const limitSec = limits[category];
  const mins = Math.floor(elapsed / 60);
  const secs = elapsed % 60;
  const pct = limitSec > 0 ? (elapsed / limitSec) * 100 : 0;
  const status = category === 'RED' ? 'overdue' : pct >= 100 ? (pct >= 150 ? 'escalated' : 'overdue') : pct >= 80 ? 'warning' : 'normal';
  const statusColor = { normal: 'text-green-700 bg-green-50 border-green-200', warning: 'text-amber-700 bg-amber-50 border-amber-200', overdue: 'text-red-700 bg-red-50 border-red-200', escalated: 'text-red-900 bg-red-100 border-red-400' }[status];
  const remaining = limitSec > 0 ? Math.max(0, limitSec - elapsed) : null;
  const remMins = remaining !== null ? Math.floor(remaining / 60) : null;
  const remSecs = remaining !== null ? remaining % 60 : null;

  return (
    <div className={`rounded-xl border p-3 ${statusColor}`}>
      <div className="flex items-center gap-1.5 mb-1.5">
        <Timer className="w-3.5 h-3.5" />
        <span className="text-xs font-bold">Treatment Timer</span>
      </div>
      {category === 'RED' ? (
        <div>
          <p className="text-xl font-bold font-mono">{String(mins).padStart(2, '0')}:{String(secs).padStart(2, '0')}</p>
          <p className="text-[10px] mt-0.5">Elapsed — Immediate attention required</p>
        </div>
      ) : (
        <div>
          <p className="text-xl font-bold font-mono">{remMins !== null ? `${String(remMins).padStart(2, '0')}:${String(remSecs!).padStart(2, '0')}` : '--:--'}</p>
          <p className="text-[10px] mt-0.5">
            {status === 'overdue' ? 'OVERDUE — Escalate to charge nurse' :
             status === 'escalated' ? 'CRITICAL OVERDUE — Department-wide alert' :
             status === 'warning' ? 'Warning — 80% of time limit reached' :
             `Countdown to doctor (${limits[category] / 60} min)`}
          </p>
          {limitSec > 0 && (
            <div className="mt-1.5 h-1.5 bg-white/50 rounded-full overflow-hidden">
              <div className={`h-full rounded-full transition-all ${pct >= 100 ? 'bg-red-500' : pct >= 80 ? 'bg-amber-500' : 'bg-green-500'}`} style={{ width: `${Math.min(pct, 100)}%` }} />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ════════════════════════════════════════════════════════════
// Main Component
// ════════════════════════════════════════════════════════════

export function AdultTriageForm() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const { patientId } = useParams<{ patientId: string }>();
  const navigate = useNavigate();
  // Round 4a — query params from the alert click-through. fromAlert is
  // the alert id we'll ack on submit; visitId pins the re-triage to
  // the right visit even though the route is patient-scoped;
  // triggerSign tells us which sign was the trigger so the banner can
  // show the nurse what to confirm.
  const [searchParams] = useSearchParams();
  const fromAlertId = searchParams.get('fromAlert');
  const sourceVisitId = searchParams.get('visitId');
  const triggerSignCode = searchParams.get('triggerSign');
  const [retriageBannerLabel, setRetriageBannerLabel] = useState<string | null>(null);
  const patient = usePatientStore((state) => patientId ? state.getPatient(patientId) : undefined);
  const addPatient = usePatientStore((state) => state.addPatient);
  const assignCategory = usePatientStore((state) => state.assignCategory);
  const setTriageStatus = usePatientStore((state) => state.setTriageStatus);
  const addAuditEntry = useAuditStore((state) => state.addEntry);
  const addTEWSHistoryEntry = useTEWSHistoryStore((state) => state.addEntry);
  const tewsTrend = useTEWSHistoryStore((state) => patientId ? state.getTrend(patientId) : null);
  const tewsHistoryCount = useTEWSHistoryStore((state) => patientId ? state.getHistory(patientId).length : 0);
  const authUser = useAuthStore((state) => state.user);
  const prevScoreRef = useRef<number>(0);
  const prevScoreTimeRef = useRef<number>(Date.now());
  const isStandalone = !patientId;

  // --- State ---
  const [arrivalTime] = useState(() => patient?.arrivalTimestamp ? new Date(patient.arrivalTimestamp) : new Date());
  const [dateTime, setDateTime] = useState(() => new Date().toISOString().slice(0, 16));

  // Patient demographics - preload from store
  const [patientNames, setPatientNames] = useState(patient?.fullName || '');
  const [dob, setDob] = useState('');
  const [patientAge, setPatientAge] = useState(patient?.age?.toString() || '');
  const [gender, setGender] = useState<string>(patient?.gender || 'MALE');
  const [ipMrNumber, setIpMrNumber] = useState(patient?.id || '');
  const [chiefComplaint, setChiefComplaint] = useState(patient?.chiefComplaint || '');
  const [nextOfKin, setNextOfKin] = useState(patient?.contactPerson?.name || '');
  const [phoneNumber, setPhoneNumber] = useState(patient?.contactPerson?.phone || '');
  const [arrivalMode, setArrivalMode] = useState(patient?.arrivalMode || 'WALK_IN');

  // Emergency signs
  const [checkedSigns, setCheckedSigns] = useState<Record<string, boolean>>({});
  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [signsReviewed, setSignsReviewed] = useState(false);
  const [signPage, setSignPage] = useState(0);
  const totalSignPages = EMERGENCY_SIGN_GROUPS.length;

  // TEWS inputs
  const [tewsInput, setTewsInput] = useState<AdultTEWSInput>({
    mobility: 'WALKING', respiratoryRate: null, heartRate: null,
    systolicBP: null, temperature: null, avpu: 'ALERT', trauma: false,
  });

  // Additional vitals
  const [systolicBPVital, setSystolicBPVital] = useState('');
  const [diastolicBP, setDiastolicBP] = useState('');
  const [spo2, setSpo2] = useState('');
  const [bloodGlucose, setBloodGlucose] = useState('');
  const [weightVal, setWeightVal] = useState('');
  const [heightVal, setHeightVal] = useState('');
  const [painScore, setPainScore] = useState('');

  // Round 4a — when the form is opened from a RETRIAGE_REQUIRED alert,
  // resolve the trigger sign label and pre-fill TEWS inputs + vitals
  // from the patient's latest recorded vitals so the nurse doesn't
  // re-enter numbers already on a monitor. Pre-fill is one-way: the
  // nurse can edit anything; we only seed empty fields.
  useEffect(() => {
    if (!triggerSignCode) return;
    let cancelled = false;
    import('@/modules/visit/clinicalSignDefinitions').then((mod) => {
      if (cancelled) return;
      const def = mod.SIGN_BY_CODE[triggerSignCode];
      setRetriageBannerLabel(def?.label ?? triggerSignCode);
    });
    return () => { cancelled = true; };
  }, [triggerSignCode]);

  useEffect(() => {
    if (!sourceVisitId) return;
    let cancelled = false;
    vitalApi.getLatest(sourceVisitId)
      .then((v) => {
        if (cancelled || !v) return;
        setTewsInput((prev) => ({
          ...prev,
          respiratoryRate: prev.respiratoryRate ?? v.respiratoryRate ?? null,
          heartRate: prev.heartRate ?? v.heartRate ?? null,
          systolicBP: prev.systolicBP ?? v.systolicBp ?? null,
          temperature: prev.temperature ?? v.temperature ?? null,
        }));
        if (v.spo2 != null) setSpo2((prev) => prev || String(v.spo2));
        if (v.diastolicBp != null) setDiastolicBP((prev) => prev || String(v.diastolicBp));
        if (v.bloodGlucose != null) setBloodGlucose((prev) => prev || String(v.bloodGlucose));
      })
      .catch(() => { /* non-fatal — nurse fills in manually */ });
    return () => { cancelled = true; };
  }, [sourceVisitId]);

  // Footer
  const [nurseName, setNurseName] = useState(authUser?.fullName || '');

  // Special-case clinical flags (V20+ entity already supports these). The
  // assault flag triggers the forensic-evidence pathway; the suicide flag
  // triggers the safety-sitter pathway. Hardcoding these to false was a
  // documented audit gap — these checkboxes close it.
  const [specialAssaultAbuse, setSpecialAssaultAbuse] = useState(false);
  const [specialSuicideAttempt, setSpecialSuicideAttempt] = useState(false);

  // Doctor-notification audit trail. Proves the RED-category 0-min /
  // ORANGE 10-min response targets were met. The nurse types the
  // notified doctor's name when they make the call; the attending name +
  // time is filled when the doctor physically arrives at the bedside.
  const [notifiedDoctorName, setNotifiedDoctorName] = useState('');
  const [doctorNotifiedAt, setDoctorNotifiedAt] = useState<string>('');
  const [attendingDoctorName, setAttendingDoctorName] = useState('');
  const [doctorAttendedAt, setDoctorAttendedAt] = useState<string>('');
  const [triageFinished, setTriageFinished] = useState(false);
  const [triageFinishTime, setTriageFinishTime] = useState<Date | null>(null);

  // ── Bed-suggestion confirm (Phase G #2) ──
  // After triage submits, the backend returns a recommended bed (zone-routed
  // by category, prefers monitored beds for RED/ORANGE). We surface a confirm
  // modal so the nurse keeps the final say on placement.
  const [suggestedBed, setSuggestedBed] = useState<{
    id: string;
    code: string;
    zone: EdZone;
    hasMonitor: boolean;
    visitId: string;
  } | null>(null);
  const [placingBed, setPlacingBed] = useState(false);
  const [bedPlaced, setBedPlaced] = useState<{ code: string; zone: EdZone; hasMonitor: boolean } | null>(null);
  const [bedError, setBedError] = useState<string | null>(null);
  const [alerts, setAlerts] = useState<string[]>([]);
  const [showDeteriorationWarning, setShowDeteriorationWarning] = useState(false);
  const [collapsedSections, setCollapsedSections] = useState<Record<string, boolean>>({});

  // Discriminator state (mSAT Step 3)
  const [checkedVeryUrgent, setCheckedVeryUrgent] = useState<Record<string, boolean>>({});
  const [checkedUrgent, setCheckedUrgent] = useState<Record<string, boolean>>({});
  const [discriminatorNotes, setDiscriminatorNotes] = useState('');
  const [discriminatorReviewed, setDiscriminatorReviewed] = useState(false);
  const [triageStartedAt] = useState(() => new Date());

  // --- Computed ---
  const normalRanges = useMemo(() => getAdultNormalRanges(), []);
  const tewsScoring = useMemo(() => calculateAdultTEWS(tewsInput), [tewsInput]);
  const tewsColumns = useMemo(() => getAdultTEWSColumns(tewsInput), [tewsInput]);
  const hasAnyCriticalSign = useMemo(() => CRITICAL_SIGN_IDS.some((id) => checkedSigns[id]), [checkedSigns]);
  const spo2Value = spo2 !== '' ? parseInt(spo2) : null;

  // Discriminator computed
  const hasVeryUrgentSigns = useMemo(() => hasCheckedDiscriminators(VERY_URGENT_DISCRIMINATORS, checkedVeryUrgent), [checkedVeryUrgent]);
  const hasUrgentSigns = useMemo(() => hasCheckedDiscriminators(URGENT_DISCRIMINATORS, checkedUrgent), [checkedUrgent]);
  const discriminatorNeeded = useMemo(() => isDiscriminatorRequired(tewsScoring.totalScore, hasAnyCriticalSign), [tewsScoring.totalScore, hasAnyCriticalSign]);

  // Vital validation (Module 3)
  const vitalWarnings = useMemo(() => getAbnormalValidations(
    validateTEWSInputs(
      {
        temperature: tewsInput.temperature,
        respiratoryRate: tewsInput.respiratoryRate,
        heartRate: tewsInput.heartRate,
        systolicBP: tewsInput.systolicBP,
        spo2: spo2Value,
      },
      false, // adult
    )
  ), [tewsInput, spo2Value]);

  const categoryResult: AdultCategoryResult = useMemo(() => determineAdultCategory(
    tewsScoring.totalScore,
    spo2Value,
    hasAnyCriticalSign,
    hasVeryUrgentSigns,
    hasUrgentSigns,
  ), [tewsScoring.totalScore, spo2Value, hasAnyCriticalSign, hasVeryUrgentSigns, hasUrgentSigns]);

  // --- Effects ---
  useEffect(() => {
    const now = Date.now();
    const timeDiff = (now - prevScoreTimeRef.current) / 60000;
    const scoreDiff = tewsScoring.totalScore - prevScoreRef.current;
    if (timeDiff <= 10 && scoreDiff >= 2 && prevScoreRef.current > 0) setShowDeteriorationWarning(true);
    prevScoreRef.current = tewsScoring.totalScore;
    prevScoreTimeRef.current = now;
  }, [tewsScoring.totalScore]);

  useEffect(() => {
    const a: string[] = [];
    if (hasAnyCriticalSign) a.push('Emergency signs present – immediate attention required.');
    if (spo2Value !== null && spo2Value < 92) a.push('SpO₂ below 92% – forced RED category.');
    if (spo2Value !== null && spo2Value < 94 && spo2Value >= 92) a.push('SpO₂ below 94% – monitor closely.');
    setAlerts(a);
  }, [hasAnyCriticalSign, spo2Value]);

  useEffect(() => {
    const rr = fieldValues['respiratory_rate'] ? parseInt(fieldValues['respiratory_rate']) : null;
    const hr = fieldValues['heart_rate'] ? parseInt(fieldValues['heart_rate']) : null;
    setTewsInput((prev) => ({ ...prev, respiratoryRate: rr ?? prev.respiratoryRate, heartRate: hr ?? prev.heartRate }));
  }, [fieldValues]);

  useEffect(() => {
    const sbp = systolicBPVital ? parseInt(systolicBPVital) : null;
    setTewsInput((prev) => ({ ...prev, systolicBP: sbp ?? prev.systolicBP }));
  }, [systolicBPVital]);

  // --- Validation ---
  const validationErrors = useMemo(() => {
    const e: string[] = [];
    if (!nurseName.trim()) e.push('Nurse name is required.');
    if (!signsReviewed) e.push('All emergency signs must be reviewed.');
    if (!chiefComplaint.trim()) e.push('Chief complaint is required.');
    if (!patientNames.trim()) e.push('Patient name is required.');
    if (discriminatorNeeded && !discriminatorReviewed) e.push('Discriminator assessment must be reviewed (TEWS 0-4).');
    return e;
  }, [nurseName, signsReviewed, chiefComplaint, patientNames, discriminatorNeeded, discriminatorReviewed]);
  const canFinish = validationErrors.length === 0;

  // --- Handlers ---
  const toggleSign = useCallback((id: string) => { setCheckedSigns((p) => ({ ...p, [id]: !p[id] })); }, []);
  const setFieldValueHandler = useCallback((id: string, value: string) => { setFieldValues((p) => ({ ...p, [id]: value })); }, []);
  const toggleSection = useCallback((key: string) => { setCollapsedSections((p) => ({ ...p, [key]: !p[key] })); }, []);
  // Doctor notification is now handled automatically via zone-aware routing
  const handleReviewAllSigns = useCallback(() => { setSignsReviewed(true); }, []);
  const toggleVeryUrgent = useCallback((id: string) => { setCheckedVeryUrgent((p) => ({ ...p, [id]: !p[id] })); }, []);
  const toggleUrgent = useCallback((id: string) => { setCheckedUrgent((p) => ({ ...p, [id]: !p[id] })); }, []);
  const handleDiscriminatorReviewed = useCallback(() => { setDiscriminatorReviewed(true); }, []);

  const handleFinishTriage = useCallback(async () => {
    if (!canFinish) return;
    setTriageFinished(true);
    const finishTime = new Date();
    setTriageFinishTime(finishTime);

    let targetPatientId = patient?.id;

    if (patient) {
      assignCategory(patient.id, categoryResult.category, tewsScoring.totalScore);
      setTriageStatus(patient.id, 'TRIAGED');
    } else {
      const age = parseFloat(patientAge) || 30;
      const np = addPatient({ fullName: patientNames || 'Unknown Patient', age, gender: (gender === 'MALE' || gender === 'FEMALE') ? gender : 'MALE', chiefComplaint: chiefComplaint || 'Adult triage', arrivalMode: 'WALK_IN', weight: parseFloat(weightVal) || undefined });
      assignCategory(np.id, categoryResult.category, tewsScoring.totalScore);
      targetPatientId = np.id;
    }

    // ── Submit to backend API ──
    if (targetPatientId) {
      const avpuMap: Record<string, 'ALERT' | 'CONFUSED' | 'VERBAL' | 'PAIN' | 'UNRESPONSIVE'> = {
        ALERT: 'ALERT', VOICE: 'VERBAL', PAIN: 'PAIN', UNRESPONSIVE: 'UNRESPONSIVE',
      };
      try {
        const triageResponse = await triageApi.perform({
          visitId: targetPatientId,
          // Emergency signs
          hasAirwayCompromise: !!checkedSigns['obstruction_stridor'],
          hasBreathingDistress: !!checkedSigns['increased_wob'] || !!checkedSigns['accessory_muscles'],
          hasSevereRespiratoryDistress: !!checkedSigns['apnoea_gasping'],
          hasCardiacArrest: false,
          hasUncontrolledHaemorrhage: !!checkedSigns['active_bleeding'],
          hasConvulsions: !!checkedSigns['posturing'],
          hasComa: tewsInput.avpu === 'PAIN' || tewsInput.avpu === 'UNRESPONSIVE',
          hasPurpuricRash: !!checkedSigns['rash_petechiae'],
          hasBurnFaceInhalation: false,
          hasHypoglycaemia: fieldValues['blood_glucose'] ? parseFloat(fieldValues['blood_glucose']) < 3.0 : false,
          hasStabGunWoundNeckChest: false,
          // TEWS components
          mobility: tewsInput.mobility as 'WALKING' | 'WITH_HELP' | 'STRETCHER',
          avpu: avpuMap[tewsInput.avpu] || 'ALERT',
          traumaStatus: tewsInput.trauma ? 'TRAUMA' : 'NO_TRAUMA',
          // Discriminators — Very Urgent
          vuFocalNeurologicDeficit: !!checkedVeryUrgent['focal_neuro_deficit'] || !!checkedSigns['focal_neuro_deficit'],
          vuAlteredMentalStatus: !!checkedVeryUrgent['altered_mental_status'],
          vuChestPain: !!checkedSigns['chest_pain'] || !!checkedVeryUrgent['chest_pain'],
          vuPoisoningOverdose: !!checkedVeryUrgent['poisoning_overdose'],
          vuShortnessOfBreath: !!checkedVeryUrgent['shortness_of_breath'],
          vuAggression: !!checkedVeryUrgent['aggression'],
          vuCoughingVomitingBlood: !!checkedVeryUrgent['coughing_vomiting_blood'],
          vuDiabeticHighGlucose: !!checkedVeryUrgent['diabetic_high_glucose'],
          vuPregnantAbdominalPain: !!checkedVeryUrgent['pregnant_abdominal_pain'],
          vuBurnOver20Percent: !!checkedVeryUrgent['burn_over_20_percent'],
          vuOpenFracture: !!checkedVeryUrgent['open_fracture'],
          vuThreatenedLimb: !!checkedVeryUrgent['threatened_limb'],
          vuEyeInjury: !!checkedVeryUrgent['eye_injury'],
          vuLargeJointDislocation: !!checkedVeryUrgent['large_joint_dislocation'],
          vuSevereMechanismOfInjury: !!checkedVeryUrgent['severe_mechanism_of_injury'],
          vuVerySeverePain: !!checkedVeryUrgent['very_severe_pain'],
          vuPregnantAbdominalTrauma: !!checkedVeryUrgent['pregnant_abdominal_trauma'],
          // Discriminators — Urgent
          urgUnableToDrinkVomits: !!checkedUrgent['unable_to_drink_vomits'],
          urgAbdominalPain: !!checkedUrgent['abdominal_pain'],
          urgVeryPale: !!checkedUrgent['very_pale'],
          urgPregnantVaginalBleeding: !!checkedUrgent['pregnant_vaginal_bleeding'],
          urgDiabeticVeryHighGlucose: !!checkedUrgent['diabetic_very_high_glucose'],
          urgFingerToeDislocation: !!checkedUrgent['finger_toe_dislocation'],
          urgClosedFracture: !!checkedUrgent['closed_fracture'],
          urgBurnWithoutUrgentSigns: !!checkedUrgent['burn_without_urgent_signs'],
          urgPregnantTraumaNonAbdominal: !!checkedUrgent['pregnant_trauma_non_abdominal'],
          urgModeratePain: !!checkedUrgent['moderate_pain'],
          urgLacerationAbscess: !!checkedUrgent['laceration_abscess'],
          urgForeignBodyAspiration: !!checkedUrgent['foreign_body_aspiration'],
          // Clinical metadata
          presentingComplaints: chiefComplaint,
          clinicalNotes: discriminatorNotes || undefined,
          // Additional Vitals
          spo2: spo2 ? parseInt(spo2) : undefined,
          diastolicBp: diastolicBP ? parseInt(diastolicBP) : undefined,
          bloodGlucose: bloodGlucose ? parseFloat(bloodGlucose) : undefined,
          painScore: painScore ? parseInt(painScore) : undefined,
          weightKg: weightVal ? parseFloat(weightVal) : undefined,
          heightCm: heightVal ? parseFloat(heightVal) : undefined,
          // Vitals for TEWS — the backend needs these to calculate TEWS when no VitalSigns DB record exists
          respiratoryRate: tewsInput.respiratoryRate ?? undefined,
          heartRate: tewsInput.heartRate ?? undefined,
          systolicBP: tewsInput.systolicBP ?? undefined,
          temperature: tewsInput.temperature ?? undefined,
          // Special considerations
          specialAcuteTrauma: tewsInput.trauma,
          specialSeizureHistory: !!checkedSigns['posturing'],
          specialAssaultAbuse,
          specialSuicideAttempt,
          // Form Footer — Nurse + doctor-notification timestamps
          triageNurseName: nurseName || undefined,
          notifiedDoctorName: notifiedDoctorName || undefined,
          doctorNotifiedAt: doctorNotifiedAt
            ? new Date(doctorNotifiedAt).toISOString()
            : undefined,
          attendingDoctorName: attendingDoctorName || undefined,
          doctorAttendedAt: doctorAttendedAt
            ? new Date(doctorAttendedAt).toISOString()
            : undefined,
        });

        // Bed suggestion (Phase G #2) — only present on perform responses.
        // Server-side rules: RED→RESUS, ORANGE→ACUTE (or PEDIATRIC for kids),
        // YELLOW→PEDIATRIC for kids; GREEN/BLUE produce no suggestion. The
        // nurse confirms before the patient is actually placed.
        if (triageResponse.suggestedBedId && triageResponse.suggestedBedCode && triageResponse.suggestedBedZone) {
          setSuggestedBed({
            id: triageResponse.suggestedBedId,
            code: triageResponse.suggestedBedCode,
            zone: triageResponse.suggestedBedZone,
            hasMonitor: !!triageResponse.suggestedBedHasMonitor,
            visitId: targetPatientId,
          });
        }

        // Round 4a — if this triage was launched from a
        // RETRIAGE_REQUIRED alert, ack the alert now that the nurse
        // has actually acted on it. Best-effort: a failed ack should
        // not undo the successful triage submission.
        if (fromAlertId) {
          try {
            await alertApi.acknowledge(fromAlertId);
          } catch (ackErr) {
            console.warn('Failed to acknowledge originating alert', fromAlertId, ackErr);
          }
        }
      } catch (err) {
        console.error('Failed to submit triage to backend:', err);
        // triage still recorded locally — backend sync will retry
      }
    }

    // Audit: triage completed
    addAuditEntry({
      action: 'TRIAGE_COMPLETED',
      performedBy: nurseName || 'UNKNOWN',
      performedByName: nurseName || 'Unknown Nurse',
      patientId: targetPatientId,
      details: `Adult triage completed. TEWS: ${tewsScoring.totalScore}, Category: ${categoryResult.category}. ${discriminatorNeeded ? `Discriminator: VU=${hasVeryUrgentSigns}, U=${hasUrgentSigns}.` : 'Discriminator not required (TEWS > 4 or emergency).'} Duration: ${Math.round((finishTime.getTime() - triageStartedAt.getTime()) / 1000)}s`,
    });

    // Audit: category assigned
    addAuditEntry({
      action: 'CATEGORY_ASSIGNED',
      performedBy: nurseName || 'UNKNOWN',
      performedByName: nurseName || 'Unknown Nurse',
      patientId: targetPatientId,
      details: `Assigned ${categoryResult.category} — ${categoryResult.reason}`,
      newValue: categoryResult.category,
    });

    // Record in TEWS history (Module 3)
    if (targetPatientId) {
      addTEWSHistoryEntry(
        targetPatientId,
        {
          mobilityScore: tewsScoring.mobilityScore,
          temperatureScore: tewsScoring.temperatureScore,
          respiratoryRateScore: tewsScoring.respiratoryRateScore,
          avpuScore: tewsScoring.avpuScore,
          pulseScore: tewsScoring.heartRateScore,
          traumaScore: tewsScoring.traumaScore,
          systolicBPScore: tewsScoring.systolicBPScore,
          totalScore: tewsScoring.totalScore,
        },
        categoryResult.category,
        categoryResult.reason,
        {
          spo2: spo2Value ?? undefined,
          hadEmergencySigns: hasAnyCriticalSign,
          discriminatorApplied: discriminatorNeeded && discriminatorReviewed,
          performedBy: nurseName || 'Unknown Nurse',
        },
      );
    }
  }, [canFinish, patient, patientNames, patientAge, gender, chiefComplaint, weightVal, categoryResult, tewsScoring, assignCategory, setTriageStatus, addPatient, addAuditEntry, addTEWSHistoryEntry, nurseName, discriminatorNeeded, discriminatorReviewed, discriminatorNotes, hasVeryUrgentSigns, hasUrgentSigns, hasAnyCriticalSign, spo2Value, triageStartedAt, tewsInput, checkedSigns, checkedVeryUrgent, checkedUrgent, fieldValues]);

  const handleConfirmPlaceBed = useCallback(async () => {
    if (!suggestedBed) return;
    setPlacingBed(true);
    setBedError(null);
    try {
      await bedsApi.placePatient(suggestedBed.id, { visitId: suggestedBed.visitId });
      setBedPlaced({
        code: suggestedBed.code,
        zone: suggestedBed.zone,
        hasMonitor: suggestedBed.hasMonitor,
      });
      setSuggestedBed(null);
    } catch (err) {
      // Most likely cause: another nurse just placed someone in the bed
      // (race). Tell the user and let them dismiss + place manually.
      const msg = err instanceof Error ? err.message : 'Failed to place patient in bed';
      setBedError(msg);
    } finally {
      setPlacingBed(false);
    }
  }, [suggestedBed]);

  const getVitalBg = useCallback((key: string, value: string) => {
    if (!value) return '';
    const num = parseFloat(value);
    if (isNaN(num)) return '';
    const range = normalRanges[key];
    if (!range) return '';
    const s = getAdultVitalStatus(num, range);
    if (s === 'critical') return 'bg-red-50 border-red-300 ring-1 ring-red-200';
    if (s === 'warning') return 'bg-amber-50 border-amber-300 ring-1 ring-amber-200';
    return 'bg-green-50 border-green-300';
  }, [normalRanges]);

  const isDemo = !!patientId && !patient;

  const categoryColor: Record<AdultTriageCategory, string> = { RED: 'bg-red-600', ORANGE: 'bg-orange-500', YELLOW: 'bg-yellow-400', GREEN: 'bg-green-500' };
  const categoryBorder: Record<AdultTriageCategory, string> = { RED: 'border-red-500', ORANGE: 'border-orange-400', YELLOW: 'border-yellow-400', GREEN: 'border-green-400' };
  const categoryBg: Record<AdultTriageCategory, string> = { RED: 'bg-red-50', ORANGE: 'bg-orange-50', YELLOW: 'bg-yellow-50', GREEN: 'bg-green-50' };
  const catTextColor = categoryResult.category === 'RED' ? '#dc2626' : categoryResult.category === 'ORANGE' ? '#ea580c' : categoryResult.category === 'YELLOW' ? '#ca8a04' : '#16a34a';

  const currentGroup = EMERGENCY_SIGN_GROUPS[signPage];
  const inputCls = 'w-full px-2.5 py-1.5 bg-white/80 border border-slate-200/60 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 transition-all placeholder:text-slate-400';
  const labelCls = 'block text-[10px] font-semibold text-slate-500 uppercase tracking-wider mb-0.5';
  const selectCls = 'w-full px-2.5 py-1.5 bg-white/80 border border-slate-200/60 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 appearance-none transition-all';

  return (
    <div className={`min-h-full ${isDark ? '' : 'bg-gradient-to-br from-slate-50/80 via-cyan-50/30 to-slate-100/80'}`}>
      <div className="p-4 lg:p-6 space-y-4">

        {/* Round 4a — context banner when the form was opened from a
            RETRIAGE_REQUIRED alert. We deliberately don't auto-flag the
            corresponding emergency-sign / discriminator checkbox: that
            should be the nurse's deliberate clinical action after
            looking at the patient. The banner just makes sure they know
            what triggered the re-triage and what to confirm. */}
        {fromAlertId && (
          <div className="rounded-2xl px-4 py-3 flex items-start gap-3 bg-amber-500/10 border border-amber-500/40 text-amber-900">
            <Sparkles className="w-5 h-5 flex-shrink-0 mt-0.5 text-amber-600" />
            <div className="flex-1 min-w-0">
              <p className="font-bold text-xs">Re-triage prompted by clinical-sign worsening</p>
              <p className="text-[11px] mt-0.5">
                {retriageBannerLabel
                  ? <>The system flagged <span className="font-bold">{retriageBannerLabel}</span> as the trigger. Confirm it on the form below if appropriate, then complete the rest of the assessment normally. The originating alert will acknowledge automatically on submit.</>
                  : <>This re-triage was prompted by an alert. Complete the assessment as usual; the alert will acknowledge automatically on submit.</>}
                {sourceVisitId && <> Vitals have been pre-filled from the latest reading; edit any field that needs updating.</>}
              </p>
            </div>
          </div>
        )}

        {/* Emergency Banner */}
        {(hasAnyCriticalSign || (spo2Value !== null && spo2Value < 92)) && (
          <div className="bg-red-600 text-white px-4 py-2.5 flex items-center gap-2.5 animate-pulse rounded-2xl shadow-lg shadow-red-500/20">
            <AlertTriangle className="w-5 h-5 flex-shrink-0" />
            <span className="font-bold text-xs">EMERGENCY — {hasAnyCriticalSign ? 'Emergency signs present' : 'SpO₂ < 92%'} — Immediate attention required</span>
          </div>
        )}

        {/* Deterioration Warning */}
        {showDeteriorationWarning && (
          <div className="bg-amber-500 text-white px-4 py-2.5 flex items-center justify-between rounded-2xl">
            <div className="flex items-center gap-2.5">
              <Activity className="w-4 h-4" />
              <span className="font-bold text-xs">WARNING: TEWS increased by ≥2 points within 10 min — Patient may be deteriorating</span>
            </div>
            <button onClick={() => setShowDeteriorationWarning(false)} className="text-[10px] bg-white/20 px-2.5 py-1 rounded-lg hover:bg-white/30">Dismiss</button>
          </div>
        )}

        {/* HEADER */}
        <div className="rounded-2xl overflow-hidden shadow-xl" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900 px-5 py-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-white/10 rounded-xl flex items-center justify-center backdrop-blur-sm border border-white/10">
                <Shield className="w-5 h-5 text-cyan-400" />
              </div>
              <div>
                <h1 className="text-base font-bold text-white tracking-wide">Adult Triage Form</h1>
                <p className="text-white/50 text-[11px]">King Faisal Hospital — Ages 12+</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <div className="bg-white/10 backdrop-blur-md rounded-xl px-3 py-1.5 flex items-center gap-2 border border-white/10">
                <Users className="w-4 h-4 text-white/70" />
                <span className="text-[11px] font-bold text-white/90 tracking-wide">ADULT</span>
              </div>
              <div className={`rounded-xl px-3.5 py-1.5 flex items-center gap-2 border-2 shadow-md ${categoryBorder[categoryResult.category]} bg-white/95`}>
                <span className={`w-2.5 h-2.5 rounded-full ${categoryColor[categoryResult.category]}`} />
                <span className="text-xs font-bold text-gray-900">TEWS: {tewsScoring.totalScore}</span>
                <span className="text-xs font-bold" style={{ color: catTextColor }}>{categoryResult.category}</span>
              </div>
            </div>
          </div>

          <div className={`border-b px-5 py-2 flex items-center gap-2 ${isDark ? 'bg-cyan-900/20 border-cyan-500/20' : 'bg-gradient-to-r from-cyan-50/80 to-cyan-100/60 border-cyan-200/40'}`}>
            <Users className="w-3.5 h-3.5 text-cyan-600" />
            <span className={`text-[11px] font-medium ${isDark ? 'text-cyan-400' : 'text-cyan-700'}`}>Adult patient — Age 12 and above</span>
          </div>

          {/* Patient Information */}
          <div className="p-4">
            <div className="flex items-center gap-1.5 mb-3">
              <FileText className="w-3.5 h-3.5 text-slate-400" />
              <span className="text-[10px] font-semibold text-slate-500 uppercase tracking-wider">Patient Information</span>
              {patient && <span className="ml-auto text-[10px] bg-green-100 text-green-700 px-2 py-0.5 rounded-full font-medium">Pre-loaded</span>}
            </div>
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
              <div><label className={labelCls}>Patient Names</label><input type="text" value={patientNames} onChange={(e) => setPatientNames(e.target.value)} className={inputCls} /></div>
              <div><label className={labelCls}>Date of Birth</label><input type="date" value={dob} onChange={(e) => setDob(e.target.value)} className={inputCls} /></div>
              <div className="grid grid-cols-2 gap-2">
                <div><label className={labelCls}>Age (yrs)</label><input type="number" value={patientAge} onChange={(e) => setPatientAge(e.target.value)} className={inputCls} /></div>
                <div><label className={labelCls}>Gender</label><select value={gender} onChange={(e) => setGender(e.target.value)} className={selectCls}><option value="MALE">Male</option><option value="FEMALE">Female</option></select></div>
              </div>
              <div><label className={labelCls}>IP / MR Number</label><input type="text" value={ipMrNumber} onChange={(e) => setIpMrNumber(e.target.value)} className={inputCls} /></div>
              <div>
                <label className={labelCls}>ED Zone (auto)</label>
                <div className={`w-full px-2.5 py-1.5 border rounded-lg text-xs font-semibold flex items-center gap-1.5 ${
                  categoryResult.category === 'RED' ? 'bg-red-50 border-red-300 text-red-700' :
                  categoryResult.category === 'ORANGE' ? 'bg-orange-50 border-orange-300 text-orange-700' :
                  categoryResult.category === 'YELLOW' ? 'bg-yellow-50 border-yellow-300 text-yellow-700' :
                  'bg-green-50 border-green-300 text-green-700'
                }`}>
                  <span className={`w-2 h-2 rounded-full ${categoryColor[categoryResult.category]}`} />
                  {categoryResult.category === 'RED' ? 'Resuscitation' :
                   categoryResult.category === 'ORANGE' ? 'Acute Care' :
                   categoryResult.category === 'YELLOW' ? 'Sub-Acute' :
                   'General / Walk-in'}
                </div>
              </div>
              <div><label className={labelCls}>Arrival Mode</label>
                <div className="w-full px-2.5 py-1.5 bg-slate-100/80 border border-slate-200/60 rounded-lg text-xs text-slate-600">
                  {arrivalMode === 'AMBULANCE' ? '🚑 Ambulance' : arrivalMode === 'REFERRAL' ? '🏥 Referral' : '🚶 Walk-in'}
                </div>
              </div>
              <div><label className={labelCls}>Next of Kin {patient?.contactPerson && <span className="text-green-600 text-[8px]">✓ loaded</span>}</label><input type="text" value={nextOfKin} onChange={(e) => setNextOfKin(e.target.value)} className={inputCls} /></div>
              <div><label className={labelCls}>Phone Number {patient?.contactPerson && <span className="text-green-600 text-[8px]">✓ loaded</span>}</label><input type="tel" value={phoneNumber} onChange={(e) => setPhoneNumber(e.target.value)} placeholder="+250 ..." className={inputCls} /></div>
            </div>
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mt-3">
              <div><label className={labelCls}>Date / Time</label><input type="datetime-local" value={dateTime} onChange={(e) => setDateTime(e.target.value)} className={inputCls} /></div>
              <div>
                <label className={labelCls}>Arrival Time (locked)</label>
                <div className="w-full px-2.5 py-1.5 bg-slate-100/80 border border-slate-200/60 rounded-lg text-xs text-slate-600 flex items-center gap-1.5">
                  <Clock className="w-3 h-3 text-slate-400" />{arrivalTime.toLocaleTimeString()}
                </div>
              </div>
            </div>
            <div className="mt-3">
              <label className={labelCls}>Chief Complaint</label>
              <textarea value={chiefComplaint} onChange={(e) => setChiefComplaint(e.target.value)} rows={2} className="w-full px-2.5 py-1.5 bg-white/80 border border-slate-200/60 rounded-lg text-xs resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 placeholder:text-slate-400" placeholder="Describe the presenting complaint..." />
            </div>
          </div>
        </div>

        {/* Active Alerts */}
        {alerts.length > 0 && (
          <div className="space-y-1.5">
            {alerts.map((alert, i) => (
              <div key={i} className="flex items-center gap-2.5 rounded-xl px-3.5 py-2" style={{ ...glassInner, background: 'rgba(254,226,226,0.6)', border: '1px solid rgba(252,165,165,0.4)' }}>
                <AlertTriangle className="w-3.5 h-3.5 text-red-600 flex-shrink-0" />
                <span className="text-xs font-semibold text-red-800">{alert}</span>
              </div>
            ))}
          </div>
        )}

        {/* EMERGENCY SIGNS - PAGINATED */}
        <div className="rounded-2xl overflow-hidden" style={glassCard}>
          <div className="px-4 py-3 border-b border-white/40 flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-7 h-7 rounded-lg bg-red-100 flex items-center justify-center"><AlertCircle className="w-4 h-4 text-red-600" /></div>
              <div>
                <h2 className="text-sm font-bold text-slate-800">Emergency Signs</h2>
                <p className="text-[10px] text-slate-500">Check all that apply — Critical signs → RED</p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <div className="flex items-center gap-1">
                {EMERGENCY_SIGN_GROUPS.map((g, i) => {
                  const gc = g.signs.filter(s => checkedSigns[s.id]).length;
                  return (
                    <button key={i} onClick={() => setSignPage(i)} className={`w-6 h-6 rounded-full text-[9px] font-bold transition-all flex items-center justify-center ${i === signPage ? 'bg-cyan-600 text-white shadow-md shadow-cyan-500/30 scale-110' : gc > 0 ? 'bg-cyan-100 text-cyan-700 hover:bg-cyan-200' : 'bg-slate-100 text-slate-400 hover:bg-slate-200'}`} title={g.title}>{i + 1}</button>
                  );
                })}
              </div>
              {!signsReviewed ? (
                <button onClick={handleReviewAllSigns} className="px-3 py-1.5 bg-cyan-600 text-white text-[10px] font-semibold rounded-lg hover:bg-cyan-500 transition-all shadow-sm">Mark Reviewed</button>
              ) : (
                <span className="flex items-center gap-1 text-[10px] font-semibold text-green-700 bg-green-50 px-2 py-1 rounded-full"><CheckCircle className="w-3 h-3" /> Reviewed</span>
              )}
            </div>
          </div>

          <div className="p-4">
            <div className={`rounded-lg px-3 py-2 flex items-center gap-2 mb-3 ${currentGroup.bgColor}`}>
              <currentGroup.icon className={`w-3.5 h-3.5 ${currentGroup.color}`} />
              <span className="text-xs font-bold text-slate-700">{currentGroup.title}</span>
              <span className="ml-auto text-[10px] bg-white/80 text-slate-500 px-2 py-0.5 rounded-full font-medium">{currentGroup.signs.filter(s => checkedSigns[s.id]).length}/{currentGroup.signs.length}</span>
              <span className="text-[10px] text-slate-400">Step {signPage + 1} of {totalSignPages}</span>
            </div>

            <div className="space-y-1">
              {currentGroup.signs.map((sign) => {
                const isChecked = !!checkedSigns[sign.id];
                const isCritical = CRITICAL_SIGN_IDS.includes(sign.id) && isChecked;
                return (
                  <div key={sign.id} className={`rounded-lg px-3 py-2 flex items-center gap-2.5 transition-all ${isCritical ? 'bg-red-50/80 border border-red-200/60' : isChecked ? 'bg-cyan-50/60 border border-cyan-200/40' : 'hover:bg-white/60 border border-transparent'}`}>
                    <input type="checkbox" checked={isChecked} onChange={() => toggleSign(sign.id)} className={`w-3.5 h-3.5 rounded border-slate-300 ${isCritical ? 'text-red-600 focus:ring-red-500' : 'text-cyan-600 focus:ring-cyan-500/30'}`} />
                    <span className={`text-xs flex-1 ${isCritical ? 'font-semibold text-red-800' : 'text-slate-700'}`}>{sign.label}</span>
                    {sign.hasField && (
                      <div className="flex-shrink-0 w-28">
                        {sign.fieldType === 'select' ? (
                          <select value={fieldValues[sign.id] || ''} onChange={(e) => setFieldValueHandler(sign.id, e.target.value)} className="w-full px-2 py-1 bg-white border border-slate-200 rounded-md text-[11px] focus:outline-none focus:ring-1 focus:ring-cyan-500/20 appearance-none">
                            <option value="">Select\u2026</option>
                            {sign.selectOptions?.map((opt) => <option key={opt} value={opt}>{opt}</option>)}
                          </select>
                        ) : (
                          <div className="relative">
                            <input type={sign.fieldType} value={fieldValues[sign.id] || ''} onChange={(e) => setFieldValueHandler(sign.id, e.target.value)} placeholder={sign.fieldLabel} className={`w-full px-2 py-1 border border-slate-200 rounded-md text-[11px] focus:outline-none focus:ring-1 focus:ring-cyan-500/20 ${sign.fieldType === 'number' ? getVitalBg(sign.id === 'respiratory_rate' ? 'respiratoryRate' : sign.id === 'heart_rate' ? 'heartRate' : sign.id === 'oxygen_saturation' ? 'spo2' : sign.id === 'blood_glucose' ? 'glucose' : '', fieldValues[sign.id] || '') : 'bg-white'}`} />
                            {sign.fieldUnit && <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">{sign.fieldUnit}</span>}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>

            <div className="flex items-center justify-between mt-4 pt-3 border-t border-slate-200/40">
              <button onClick={() => setSignPage((p) => Math.max(0, p - 1))} disabled={signPage === 0} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold transition-all disabled:opacity-40 disabled:cursor-not-allowed bg-white/80 border border-slate-200/60 text-slate-600 hover:bg-white hover:shadow-sm">
                <ChevronLeft className="w-3.5 h-3.5" /> Previous
              </button>
              <span className="text-[10px] text-slate-500 font-medium">{signPage + 1} / {totalSignPages}</span>
              <button onClick={() => setSignPage((p) => Math.min(totalSignPages - 1, p + 1))} disabled={signPage === totalSignPages - 1} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold transition-all disabled:opacity-40 disabled:cursor-not-allowed bg-cyan-600 text-white hover:bg-cyan-500 shadow-sm">
                Next <ChevronRight className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        </div>

        {/* TEWS SCORING TABLE */}
        <div className="rounded-2xl overflow-hidden" style={glassCard}>
          <div className="px-4 py-3 border-b border-white/40 flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-7 h-7 rounded-lg bg-cyan-100 flex items-center justify-center"><Activity className="w-4 h-4 text-cyan-600" /></div>
              <div>
                <h2 className="text-sm font-bold text-slate-800">TEWS — Triage Early Warning Score</h2>
                <p className="text-[10px] text-slate-500">Enter measured values. Score calculates automatically.</p>
              </div>
            </div>
            <div className={`flex items-center gap-2.5 px-3.5 py-1.5 rounded-xl border-2 ${categoryBorder[categoryResult.category]} ${categoryBg[categoryResult.category]}`}>
              <div className="text-center">
                <div className="text-lg font-bold text-gray-900">{tewsScoring.totalScore}</div>
                <div className="text-[9px] text-slate-500 uppercase tracking-wider">Total</div>
              </div>
              <div className="w-px h-6 bg-slate-300" />
              <div className="text-center">
                <div className="text-sm font-bold" style={{ color: catTextColor }}>{categoryResult.category}</div>
                <div className="text-[9px] text-slate-500">{categoryResult.maxTimeToDoctor}</div>
              </div>
            </div>
          </div>

          <div className="px-4 py-3 border-b border-white/30" style={{ background: isDark ? 'rgba(12,74,110,0.25)' : 'rgba(248,250,252,0.5)' }}>
            <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-7 gap-3">
              <div><label className={labelCls}>Mobility</label><select value={tewsInput.mobility} onChange={(e) => setTewsInput({ ...tewsInput, mobility: e.target.value as AdultMobility })} className={selectCls}><option value="WALKING">Walking</option><option value="WITH_HELP">With help</option><option value="STRETCHER">Stretcher</option></select></div>
              <div><label className={labelCls}>Respiratory Rate</label><div className="relative"><input type="number" value={tewsInput.respiratoryRate ?? ''} onChange={(e) => setTewsInput({ ...tewsInput, respiratoryRate: e.target.value ? parseInt(e.target.value) : null })} placeholder="—" className={`${inputCls} ${getVitalBg('respiratoryRate', tewsInput.respiratoryRate?.toString() || '')}`} /><span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">/min</span></div></div>
              <div><label className={labelCls}>Heart Rate</label><div className="relative"><input type="number" value={tewsInput.heartRate ?? ''} onChange={(e) => setTewsInput({ ...tewsInput, heartRate: e.target.value ? parseInt(e.target.value) : null })} placeholder="—" className={`${inputCls} ${getVitalBg('heartRate', tewsInput.heartRate?.toString() || '')}`} /><span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">bpm</span></div></div>
              <div><label className={labelCls}>Systolic BP</label><div className="relative"><input type="number" value={tewsInput.systolicBP ?? ''} onChange={(e) => { const v = e.target.value ? parseInt(e.target.value) : null; setTewsInput({ ...tewsInput, systolicBP: v }); setSystolicBPVital(e.target.value); }} placeholder="—" className={`${inputCls} ${getVitalBg('systolicBP', tewsInput.systolicBP?.toString() || '')}`} /><span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">mmHg</span></div></div>
              <div><label className={labelCls}>Temperature</label><div className="relative"><input type="number" step="0.1" value={tewsInput.temperature ?? ''} onChange={(e) => setTewsInput({ ...tewsInput, temperature: e.target.value ? parseFloat(e.target.value) : null })} placeholder="—" className={`${inputCls} ${getVitalBg('temperature', tewsInput.temperature?.toString() || '')}`} /><span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">°C</span></div></div>
              <div><label className={labelCls}>AVPU</label><select value={tewsInput.avpu} onChange={(e) => setTewsInput({ ...tewsInput, avpu: e.target.value as AdultAVPU })} className={selectCls}><option value="ALERT">Alert</option><option value="VOICE">Responds to Voice</option><option value="PAIN">Responds to Pain</option><option value="UNRESPONSIVE">Unresponsive</option></select></div>
              <div><label className={labelCls}>Trauma</label><select value={tewsInput.trauma ? 'YES' : 'NO'} onChange={(e) => setTewsInput({ ...tewsInput, trauma: e.target.value === 'YES' })} className={selectCls}><option value="NO">No</option><option value="YES">Yes</option></select></div>
            </div>

            {/* Special-case clinical flags. Each one triggers its own
                pathway downstream — the assault flag opens the forensic-
                evidence chain, the suicide flag triggers safety-sitter
                allocation. Both are persisted on TriageRecord so an
                inspector can audit why those pathways activated. */}
            <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-2">
              <label className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-rose-50/80 border border-rose-200/60 cursor-pointer hover:bg-rose-50">
                <input type="checkbox" checked={specialAssaultAbuse} onChange={(e) => setSpecialAssaultAbuse(e.target.checked)} className="w-3.5 h-3.5 accent-rose-600" />
                <span className="text-[11px] font-semibold text-rose-700">Assault / abuse case</span>
                <span className="text-[9px] text-rose-500/70">forensic chain</span>
              </label>
              <label className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-amber-50/80 border border-amber-200/60 cursor-pointer hover:bg-amber-50">
                <input type="checkbox" checked={specialSuicideAttempt} onChange={(e) => setSpecialSuicideAttempt(e.target.checked)} className="w-3.5 h-3.5 accent-amber-600" />
                <span className="text-[11px] font-semibold text-amber-700">Suicide attempt / SI</span>
                <span className="text-[9px] text-amber-600/70">safety sitter</span>
              </label>
            </div>
          </div>

          <div className="p-4 overflow-x-auto">
            <table className="w-full text-[11px] border-collapse">
              <thead>
                <tr>
                  <th className="text-left py-1.5 px-2 bg-slate-100/80 rounded-tl-lg font-semibold text-slate-600 w-20">Parameter</th>
                  {TEWS_COLUMNS.map((score, i) => (
                    <th key={i} className={`py-1.5 px-1.5 text-center font-bold ${i === 3 ? 'bg-green-100 text-green-800' : i < 3 ? 'bg-blue-50 text-blue-700' : 'bg-red-50 text-red-700'} ${i === 6 ? 'rounded-tr-lg' : ''}`}>{score}</th>
                  ))}
                  <th className="py-1.5 px-2 bg-slate-100/80 rounded-tr-lg font-semibold text-slate-600 w-12 text-center">Score</th>
                </tr>
              </thead>
              <tbody>
                {TEWS_ROWS.map((row, ri) => {
                  const colVal = row.key === 'mobility' ? tewsColumns.mobility : row.key === 'rr' ? tewsColumns.rr : row.key === 'hr' ? tewsColumns.hr : row.key === 'sbp' ? tewsColumns.sbp : row.key === 'temp' ? tewsColumns.temp : row.key === 'avpu' ? tewsColumns.avpu : tewsColumns.trauma;
                  const highlightIdx = columnToTableIndex(colVal);
                  const rowScore = row.key === 'mobility' ? tewsScoring.mobilityScore : row.key === 'rr' ? tewsScoring.respiratoryRateScore : row.key === 'hr' ? tewsScoring.heartRateScore : row.key === 'sbp' ? tewsScoring.systolicBPScore : row.key === 'temp' ? tewsScoring.temperatureScore : row.key === 'avpu' ? tewsScoring.avpuScore : tewsScoring.traumaScore;
                  return (
                    <tr key={row.key} className={ri % 2 === 0 ? 'bg-white/40' : 'bg-slate-50/30'}>
                      <td className="py-2 px-2 font-semibold text-slate-700 border-r border-slate-200/40">{row.label}</td>
                      {row.cells.map((cell, ci) => {
                        const isHighlighted = cell !== null && ci === highlightIdx;
                        return (
                          <td key={ci} className={`py-2 px-1.5 text-center border-r border-slate-100/40 transition-all ${cell === null ? 'bg-slate-100/40 text-slate-300' : isHighlighted ? 'bg-cyan-600 text-white font-bold rounded-md shadow-sm' : 'text-slate-600'}`}>{cell ?? '–'}</td>
                        );
                      })}
                      <td className={`py-2 px-2 text-center font-bold ${rowScore > 0 ? 'text-red-600' : 'text-slate-400'}`}>{rowScore}</td>
                    </tr>
                  );
                })}
              </tbody>
              <tfoot>
                <tr className="bg-slate-800 text-white">
                  <td className="py-2 px-2 font-bold rounded-bl-lg text-xs" colSpan={8}>Total TEWS Score</td>
                  <td className="py-2 px-2 text-center text-base font-bold rounded-br-lg">{tewsScoring.totalScore}</td>
                </tr>
              </tfoot>
            </table>
          </div>
        </div>

        {/* ADDITIONAL VITALS — Clinical completeness (not TEWS-scored) */}
        <div className="rounded-2xl overflow-hidden" style={glassCard}>
          <div className="px-4 py-3 border-b border-white/40 flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <div className="w-7 h-7 rounded-lg bg-violet-100 flex items-center justify-center"><Droplets className="w-4 h-4 text-violet-600" /></div>
              <div>
                <h2 className="text-sm font-bold text-slate-800">Additional Vitals</h2>
                <p className="text-[10px] text-slate-500">Supplementary measurements — recorded but not TEWS-scored</p>
              </div>
            </div>
          </div>
          <div className="px-4 py-3">
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
              <div>
                <label className={labelCls}>SpO₂</label>
                <div className="relative">
                  <input type="number" value={spo2} onChange={(e) => setSpo2(e.target.value)} placeholder="—" className={`${inputCls} ${spo2 ? (parseInt(spo2) < 92 ? 'bg-red-50 border-red-300 ring-1 ring-red-200' : parseInt(spo2) < 95 ? 'bg-amber-50 border-amber-300 ring-1 ring-amber-200' : 'bg-green-50 border-green-300') : ''}`} />
                  <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">%</span>
                </div>
                {spo2 && parseInt(spo2) < 92 && <p className="text-[9px] text-red-600 font-bold mt-0.5">⚠ Critical — forces RED</p>}
              </div>
              <div>
                <label className={labelCls}>Diastolic BP</label>
                <div className="relative">
                  <input type="number" value={diastolicBP} onChange={(e) => setDiastolicBP(e.target.value)} placeholder="—" className={`${inputCls} ${diastolicBP ? (parseInt(diastolicBP) > 110 || parseInt(diastolicBP) < 50 ? 'bg-red-50 border-red-300 ring-1 ring-red-200' : parseInt(diastolicBP) > 90 || parseInt(diastolicBP) < 60 ? 'bg-amber-50 border-amber-300 ring-1 ring-amber-200' : 'bg-green-50 border-green-300') : ''}`} />
                  <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">mmHg</span>
                </div>
              </div>
              <div>
                <label className={labelCls}>Blood Glucose</label>
                <div className="relative">
                  <input type="number" step="0.1" value={bloodGlucose} onChange={(e) => setBloodGlucose(e.target.value)} placeholder="—" className={`${inputCls} ${bloodGlucose ? (parseFloat(bloodGlucose) < 3.0 || parseFloat(bloodGlucose) > 25 ? 'bg-red-50 border-red-300 ring-1 ring-red-200' : parseFloat(bloodGlucose) < 4.0 || parseFloat(bloodGlucose) > 11 ? 'bg-amber-50 border-amber-300 ring-1 ring-amber-200' : 'bg-green-50 border-green-300') : ''}`} />
                  <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">mmol/L</span>
                </div>
              </div>
              <div>
                <label className={labelCls}>Pain Score</label>
                <div className="relative">
                  <select value={painScore} onChange={(e) => setPainScore(e.target.value)} className={`${selectCls} ${painScore ? (parseInt(painScore) >= 8 ? 'bg-red-50 border-red-300' : parseInt(painScore) >= 5 ? 'bg-amber-50 border-amber-300' : 'bg-green-50 border-green-300') : ''}`}>
                    <option value="">—</option>
                    {[0,1,2,3,4,5,6,7,8,9,10].map(n => <option key={n} value={n}>{n} — {n === 0 ? 'None' : n <= 3 ? 'Mild' : n <= 6 ? 'Moderate' : n <= 9 ? 'Severe' : 'Worst'}</option>)}
                  </select>
                  <span className="absolute right-6 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">/10</span>
                </div>
              </div>
              <div>
                <label className={labelCls}>Weight</label>
                <div className="relative">
                  <input type="number" step="0.1" value={weightVal} onChange={(e) => setWeightVal(e.target.value)} placeholder="—" className={inputCls} />
                  <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">kg</span>
                </div>
              </div>
              <div>
                <label className={labelCls}>Height</label>
                <div className="relative">
                  <input type="number" value={heightVal} onChange={(e) => setHeightVal(e.target.value)} placeholder="—" className={inputCls} />
                  <span className="absolute right-1.5 top-1/2 -translate-y-1/2 text-[9px] text-slate-400">cm</span>
                </div>
              </div>
            </div>
            {/* BMI auto-calc */}
            {weightVal && heightVal && parseFloat(heightVal) > 0 && (
              <div className="mt-2 px-3 py-1.5 bg-slate-50/80 rounded-lg text-[11px] text-slate-600 font-medium">
                BMI: <span className="font-bold text-slate-800">{(parseFloat(weightVal) / ((parseFloat(heightVal)/100) ** 2)).toFixed(1)}</span> kg/m²
                {(() => { const bmi = parseFloat(weightVal) / ((parseFloat(heightVal)/100) ** 2); return bmi < 18.5 ? ' — Underweight' : bmi < 25 ? ' — Normal' : bmi < 30 ? ' — Overweight' : ' — Obese'; })()}
              </div>
            )}
          </div>
        </div>

        {/* MODULE 3: VITAL VALIDATION WARNINGS + TEWS TREND */}
        {(vitalWarnings.length > 0 || (tewsTrend && tewsHistoryCount >= 2)) && (
          <div className="rounded-2xl overflow-hidden border border-amber-200/60" style={glassCard}>
            <div className="px-4 py-3 border-b border-white/40">
              <div className="flex items-center gap-2.5">
                <div className="w-7 h-7 rounded-lg bg-amber-100 flex items-center justify-center"><Shield className="w-4 h-4 text-amber-600" /></div>
                <div>
                  <h2 className="text-sm font-bold text-slate-800">Input Validation & Score Trend</h2>
                  <p className="text-[10px] text-slate-500">Physiologic range checks &amp; TEWS change tracking</p>
                </div>
              </div>
            </div>
            <div className="p-4 space-y-3">
              {/* Score breakdown */}
              <div className="p-2.5 bg-gray-50/80 rounded-xl text-xs text-gray-600 text-center font-medium">
                {getScoreBreakdownText({
                  mobilityScore: tewsScoring.mobilityScore,
                  temperatureScore: tewsScoring.temperatureScore,
                  respiratoryRateScore: tewsScoring.respiratoryRateScore,
                  avpuScore: tewsScoring.avpuScore,
                  pulseScore: tewsScoring.heartRateScore,
                  traumaScore: tewsScoring.traumaScore,
                  systolicBPScore: tewsScoring.systolicBPScore,
                  totalScore: tewsScoring.totalScore,
                })}
              </div>

              {/* Trend indicator */}
              {tewsTrend && tewsHistoryCount >= 2 && (
                <div className={`p-2.5 rounded-xl text-xs font-semibold text-center border ${
                  tewsTrend.direction === 'WORSENING' ? 'bg-red-50 border-red-200 text-red-800' :
                  tewsTrend.direction === 'IMPROVING' ? 'bg-green-50 border-green-200 text-green-800' :
                  'bg-gray-50 border-gray-200 text-gray-700'
                }`}>
                  {tewsTrend.direction === 'WORSENING' && `\u25b2 Score worsened by ${tewsTrend.delta} (prev: ${tewsTrend.previousScore})`}
                  {tewsTrend.direction === 'IMPROVING' && `\u25bc Score improved by ${Math.abs(tewsTrend.delta)} (prev: ${tewsTrend.previousScore})`}
                  {tewsTrend.direction === 'STABLE' && `\u25cf Score stable at ${tewsTrend.currentScore}`}
                  {tewsTrend.alertRequired && tewsTrend.alertMessage && (
                    <div className="mt-1 text-red-600 font-bold">{tewsTrend.alertMessage}</div>
                  )}
                  {tewsTrend.recommendation && (
                    <div className="mt-1 text-gray-600 font-normal italic">{tewsTrend.recommendation}</div>
                  )}
                </div>
              )}

              {/* Validation warnings */}
              {vitalWarnings.length > 0 && (
                <div className="space-y-1.5">
                  <div className="text-xs font-bold text-amber-900">{vitalWarnings.length} validation alert{vitalWarnings.length > 1 ? 's' : ''}:</div>
                  {vitalWarnings.map((v, i) => (
                    <div key={i} className={`px-3 py-1.5 rounded-lg text-xs font-medium border ${getValidationBgColor(v.severity)}`}>
                      {v.message}
                    </div>
                  ))}
                  {checkImpossible(vitalWarnings) && (
                    <div className="text-xs text-red-700 font-bold mt-1">
                      \u26d4 One or more values appear physiologically impossible. Please verify.
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        )}

        {/* mSAT STEP 3: DISCRIMINATOR ASSESSMENT */}
        {discriminatorNeeded && (
          <div className="rounded-2xl overflow-hidden" style={glassCard}>
            <div className="px-4 py-3 border-b border-white/40 flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="w-7 h-7 rounded-lg bg-amber-100 flex items-center justify-center"><AlertCircle className="w-4 h-4 text-amber-600" /></div>
                <div>
                  <h2 className="text-sm font-bold text-slate-800">mSAT Discriminator Assessment</h2>
                  <p className="text-[10px] text-slate-500">TEWS 0-4 — Check symptoms to determine ORANGE / YELLOW / GREEN</p>
                </div>
              </div>
              <div className="flex items-center gap-2">
                {hasVeryUrgentSigns && <span className="text-[10px] font-bold bg-orange-100 text-orange-700 px-2 py-0.5 rounded-full">Very Urgent</span>}
                {hasUrgentSigns && !hasVeryUrgentSigns && <span className="text-[10px] font-bold bg-yellow-100 text-yellow-700 px-2 py-0.5 rounded-full">Urgent</span>}
                {!hasVeryUrgentSigns && !hasUrgentSigns && <span className="text-[10px] font-medium bg-green-100 text-green-700 px-2 py-0.5 rounded-full">Routine</span>}
                {!discriminatorReviewed ? (
                  <button onClick={handleDiscriminatorReviewed} className="px-3 py-1.5 bg-amber-600 text-white text-[10px] font-semibold rounded-lg hover:bg-amber-500 transition-all shadow-sm">Mark Reviewed</button>
                ) : (
                  <span className="flex items-center gap-1 text-[10px] font-semibold text-green-700 bg-green-50 px-2 py-1 rounded-full"><CheckCircle className="w-3 h-3" /> Reviewed</span>
                )}
              </div>
            </div>

            <div className="p-4 space-y-4">
              {/* Very Urgent Section */}
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <div className="w-2.5 h-2.5 rounded-full bg-orange-500" />
                  <span className="text-xs font-bold text-slate-800">Very Urgent Signs</span>
                  <span className="text-[10px] text-orange-600 font-medium">(Any checked = ORANGE, 10 min)</span>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {VERY_URGENT_DISCRIMINATORS.map((group) => (
                    <div key={group.system} className={`rounded-xl p-3 ${group.bgColor} border border-white/40`}>
                      <div className="flex items-center gap-1.5 mb-2">
                        <span className="text-xs">{group.icon}</span>
                        <span className={`text-[10px] font-bold ${group.color} uppercase tracking-wider`}>{group.system}</span>
                      </div>
                      <div className="space-y-1">
                        {group.items.map((item) => (
                          <label key={item.id} className="flex items-start gap-2 cursor-pointer">
                            <input type="checkbox" checked={!!checkedVeryUrgent[item.id]} onChange={() => toggleVeryUrgent(item.id)} className="w-3.5 h-3.5 rounded border-slate-300 text-orange-600 focus:ring-orange-500/30 mt-0.5" />
                            <span className={`text-[11px] ${checkedVeryUrgent[item.id] ? 'font-semibold text-orange-800' : 'text-slate-600'}`}>{item.label}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Urgent Section */}
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <div className="w-2.5 h-2.5 rounded-full bg-yellow-500" />
                  <span className="text-xs font-bold text-slate-800">Urgent Signs</span>
                  <span className="text-[10px] text-yellow-600 font-medium">(Any checked = YELLOW, 30 min)</span>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {URGENT_DISCRIMINATORS.map((group) => (
                    <div key={group.system} className={`rounded-xl p-3 ${group.bgColor} border border-white/40`}>
                      <div className="flex items-center gap-1.5 mb-2">
                        <span className="text-xs">{group.icon}</span>
                        <span className={`text-[10px] font-bold ${group.color} uppercase tracking-wider`}>{group.system}</span>
                      </div>
                      <div className="space-y-1">
                        {group.items.map((item) => (
                          <label key={item.id} className="flex items-start gap-2 cursor-pointer">
                            <input type="checkbox" checked={!!checkedUrgent[item.id]} onChange={() => toggleUrgent(item.id)} className="w-3.5 h-3.5 rounded border-slate-300 text-yellow-600 focus:ring-yellow-500/30 mt-0.5" />
                            <span className={`text-[11px] ${checkedUrgent[item.id] ? 'font-semibold text-yellow-800' : 'text-slate-600'}`}>{item.label}</span>
                          </label>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Discriminator Notes */}
              <div>
                <label className={labelCls}>Clinical Notes (discriminator)</label>
                <textarea value={discriminatorNotes} onChange={(e) => setDiscriminatorNotes(e.target.value)} rows={2} className="w-full px-2.5 py-1.5 bg-white/80 border border-slate-200/60 rounded-lg text-xs resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 placeholder:text-slate-400" placeholder="Document clinical findings supporting discriminator selection..." />
              </div>

              {/* Discriminator Result Summary */}
              <div className={`rounded-xl p-3 border ${hasVeryUrgentSigns ? 'bg-orange-50 border-orange-200' : hasUrgentSigns ? 'bg-yellow-50 border-yellow-200' : 'bg-green-50 border-green-200'}`}>
                <div className="flex items-center gap-2">
                  <div className={`w-3 h-3 rounded-full ${hasVeryUrgentSigns ? 'bg-orange-500' : hasUrgentSigns ? 'bg-yellow-500' : 'bg-green-500'}`} />
                  <span className="text-xs font-bold text-slate-800">
                    Discriminator Result: {hasVeryUrgentSigns ? 'VERY URGENT → ORANGE (10 min)' : hasUrgentSigns ? 'URGENT → YELLOW (30 min)' : 'ROUTINE → GREEN (60 min)'}
                  </span>
                </div>
                {(hasVeryUrgentSigns || hasUrgentSigns) && (
                  <div className="mt-2 space-y-0.5">
                    {getCheckedDiscriminatorLabels(VERY_URGENT_DISCRIMINATORS, checkedVeryUrgent).map((label, i) => (
                      <div key={i} className="text-[10px] text-orange-700 flex items-center gap-1"><span className="w-1 h-1 bg-orange-500 rounded-full" />{label}</div>
                    ))}
                    {getCheckedDiscriminatorLabels(URGENT_DISCRIMINATORS, checkedUrgent).map((label, i) => (
                      <div key={i} className="text-[10px] text-yellow-700 flex items-center gap-1"><span className="w-1 h-1 bg-yellow-500 rounded-full" />{label}</div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}

        {!discriminatorNeeded && (
          <div className="rounded-xl p-3 flex items-center gap-2.5" style={{ ...glassInner, background: 'rgba(219,234,254,0.5)', border: '1px solid rgba(147,197,253,0.4)' }}>
            <Shield className="w-3.5 h-3.5 text-blue-600 flex-shrink-0" />
            <span className="text-[11px] text-blue-800 font-medium">
              {hasAnyCriticalSign
                ? 'Discriminator step skipped — Emergency signs present (RED).'
                : `Discriminator step skipped — TEWS ${tewsScoring.totalScore} ≥ 5 determines category automatically.`}
            </span>
          </div>
        )}

        {/* ADDITIONAL VITALS + CATEGORY RESULT */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="lg:col-span-2 rounded-2xl p-4" style={glassCard}>
            <div className="flex items-center gap-1.5 mb-3">
              <Stethoscope className="w-3.5 h-3.5 text-cyan-600" />
              <h3 className="text-xs font-bold text-slate-800">Additional Vitals</h3>
            </div>
            <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
              <div><label className={labelCls}>SpO₂ (%)</label><input type="number" value={spo2} onChange={(e) => setSpo2(e.target.value)} placeholder="—" className={`${inputCls} ${getVitalBg('spo2', spo2)}`} />{spo2Value !== null && spo2Value < 92 && <p className="text-[9px] text-red-600 mt-0.5 font-semibold">⚠ Critical – forces RED</p>}</div>
              <div><label className={labelCls}>Diastolic BP (mmHg)</label><input type="number" value={diastolicBP} onChange={(e) => setDiastolicBP(e.target.value)} placeholder="—" className={`${inputCls} ${getVitalBg('diastolicBP', diastolicBP)}`} /></div>
              <div><label className={labelCls}>Blood Glucose (mmol/L)</label><input type="number" step="0.1" value={bloodGlucose} onChange={(e) => setBloodGlucose(e.target.value)} placeholder="—" className={`${inputCls} ${getVitalBg('glucose', bloodGlucose)}`} /></div>
              <div><label className={labelCls}>Weight (kg)</label><input type="number" step="0.1" value={weightVal} onChange={(e) => setWeightVal(e.target.value)} placeholder="Optional" className={inputCls} /></div>
              <div><label className={labelCls}>Height (cm)</label><input type="number" value={heightVal} onChange={(e) => setHeightVal(e.target.value)} placeholder="Optional" className={inputCls} /></div>
              <div><label className={labelCls}>Pain Score (0-10)</label><input type="number" min="0" max="10" value={painScore} onChange={(e) => setPainScore(e.target.value)} placeholder="—" className={inputCls} /></div>
            </div>
          </div>

          <div className={`rounded-2xl border-2 p-4 flex flex-col items-center justify-center text-center ${categoryBorder[categoryResult.category]} ${categoryBg[categoryResult.category]}`} style={{ boxShadow: '0 4px 16px rgba(0,0,0,0.06)' }}>
            <div className={`w-12 h-12 rounded-xl ${categoryColor[categoryResult.category]} flex items-center justify-center mb-2 shadow-lg`}>
              {categoryResult.category === 'RED' ? <AlertTriangle className="w-6 h-6 text-white" /> : categoryResult.category === 'ORANGE' ? <AlertCircle className="w-6 h-6 text-white" /> : categoryResult.category === 'YELLOW' ? <Clock className="w-6 h-6 text-white" /> : <CheckCircle className="w-6 h-6 text-white" />}
            </div>
            <p className="text-xl font-bold text-gray-900 mb-0.5">{categoryResult.category}</p>
            <p className="text-[10px] text-slate-600 mb-2">{categoryResult.reason}</p>
            <div className="w-full bg-white/60 rounded-lg px-2.5 py-1.5 mt-1">
              <p className="text-[9px] text-slate-500 uppercase tracking-wider">Max time to doctor</p>
              <p className="text-sm font-bold text-gray-900">{categoryResult.maxTimeToDoctor}</p>
            </div>
            <div className="w-full bg-white/60 rounded-lg px-2.5 py-1.5 mt-1.5">
              <p className="text-[9px] text-slate-500 uppercase tracking-wider">TEWS Score</p>
              <p className="text-lg font-bold text-gray-900">{tewsScoring.totalScore} <span className="text-xs font-normal text-slate-400">/ 17</span></p>
            </div>
            {triageFinished && <div className="w-full mt-2"><CategoryTimer category={categoryResult.category} startedAt={triageFinishTime!} /></div>}
          </div>
        </div>

        {/* FOOTER */}
        <div className="rounded-2xl p-4" style={glassCard}>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <div className="space-y-3">
              <div className="flex items-center gap-1.5 mb-1"><User className="w-3.5 h-3.5 text-slate-400" /><h3 className="text-xs font-bold text-slate-800">Triage Details</h3></div>
              <div>
                <label className={labelCls}>Triage Nurse <span className="text-red-500">*</span> {authUser && <span className="text-green-600 text-[8px]">✓ auto-filled</span>}</label>
                <input type="text" value={nurseName} onChange={(e) => setNurseName(e.target.value)} placeholder="Enter nurse name or ID" className={`${inputCls} ${!nurseName.trim() ? 'border-red-300 bg-red-50/50' : ''}`} />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div><label className={labelCls}>Arrival Time</label><div className="px-2.5 py-1.5 bg-slate-100/80 border border-slate-200/60 rounded-lg text-xs text-slate-600">{arrivalTime.toLocaleString()}</div></div>
                <div><label className={labelCls}>Triage Finish</label><div className="px-2.5 py-1.5 bg-slate-100/80 border border-slate-200/60 rounded-lg text-xs text-slate-600">{triageFinishTime ? triageFinishTime.toLocaleString() : 'Pending\u2026'}</div></div>
              </div>

              {/* Doctor notification audit trail. Captures the moment the
                  nurse called the doctor and the moment the doctor
                  arrived at the bedside \u2014 the data that proves RED 0-min
                  / ORANGE 10-min response targets were met. Both pairs
                  are optional at triage time (the doctor may not have
                  been called yet) but always recordable later via the
                  same fields. */}
              <div className="pt-2 mt-2 border-t border-slate-200/70">
                <div className="flex items-center gap-1.5 mb-2"><Bell className="w-3 h-3 text-cyan-500" /><h4 className="text-[10px] font-bold text-slate-700 uppercase tracking-wider">Doctor Notification</h4></div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className={labelCls}>Notified Doctor</label>
                    <input type="text" value={notifiedDoctorName} onChange={(e) => setNotifiedDoctorName(e.target.value)} placeholder="Dr. name (when called)" className={inputCls} />
                  </div>
                  <div>
                    <label className={labelCls}>Notified At</label>
                    <input type="datetime-local" value={doctorNotifiedAt} onChange={(e) => setDoctorNotifiedAt(e.target.value)} className={inputCls} />
                  </div>
                  <div>
                    <label className={labelCls}>Attending Doctor</label>
                    <input type="text" value={attendingDoctorName} onChange={(e) => setAttendingDoctorName(e.target.value)} placeholder="Dr. name (at bedside)" className={inputCls} />
                  </div>
                  <div>
                    <label className={labelCls}>Attended At</label>
                    <input type="datetime-local" value={doctorAttendedAt} onChange={(e) => setDoctorAttendedAt(e.target.value)} className={inputCls} />
                  </div>
                </div>
              </div>
            </div>
            <div className="space-y-3">
              <div className="flex items-center gap-1.5 mb-1">
                <Bell className="w-3.5 h-3.5 text-cyan-500" /><h3 className="text-xs font-bold text-slate-800">Zone-Aware Doctor Notification</h3>
                <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded-full ${
                  categoryResult.category === 'RED' ? 'bg-red-100 text-red-700' :
                  categoryResult.category === 'ORANGE' ? 'bg-orange-100 text-orange-700' :
                  categoryResult.category === 'YELLOW' ? 'bg-yellow-100 text-yellow-700' :
                  'bg-green-100 text-green-700'
                }`}>{categoryResult.category}</span>
              </div>
              {(categoryResult.category === 'RED' || categoryResult.category === 'ORANGE' || categoryResult.category === 'YELLOW') ? (
                <div className="rounded-lg p-3 border" style={{ background: 'rgba(6,182,212,0.05)', borderColor: 'rgba(6,182,212,0.2)' }}>
                  <div className="flex items-start gap-2">
                    <div className="w-6 h-6 rounded-full bg-cyan-100 flex items-center justify-center flex-shrink-0 mt-0.5">
                      <Bell className="w-3 h-3 text-cyan-600" />
                    </div>
                    <div>
                      <p className="text-[11px] font-semibold text-slate-700">Automatic zone-routed alert</p>
                      <p className="text-[10px] text-slate-500 mt-0.5">Upon triage completion, an alert will be automatically sent to the doctor assigned to the <span className="font-bold text-cyan-700">{categoryResult.category === 'RED' ? 'RESUS' : categoryResult.category === 'ORANGE' ? 'ACUTE' : 'GENERAL'}</span> zone.</p>
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        <span className="text-[9px] bg-slate-100 text-slate-600 px-1.5 py-0.5 rounded">Tier 1: Instant to zone doctor</span>
                        <span className="text-[9px] bg-slate-100 text-slate-600 px-1.5 py-0.5 rounded">Tier 2: All doctors at 2 min</span>
                        <span className="text-[9px] bg-slate-100 text-slate-600 px-1.5 py-0.5 rounded">Tier 3: Hospital-wide at 5 min</span>
                      </div>
                    </div>
                  </div>
                </div>
              ) : (
                <div className="flex items-center gap-1.5 text-slate-400 py-6 justify-center">
                  <CheckCircle className="w-4 h-4" /><span className="text-xs">Doctor notification not required for {categoryResult.category} patients</span>
                </div>
              )}
            </div>
          </div>

          <div className="mt-4 pt-4 border-t border-slate-200/40">
            {validationErrors.length > 0 && !triageFinished && (
              <div className="mb-3 rounded-lg p-3" style={{ background: 'rgba(254,243,199,0.6)', border: '1px solid rgba(252,211,77,0.4)' }}>
                <p className="text-[10px] font-bold text-amber-800 mb-1.5">Please complete before finishing:</p>
                <ul className="space-y-0.5">{validationErrors.map((err, i) => <li key={i} className="text-[10px] text-amber-700 flex items-center gap-1.5"><span className="w-1 h-1 bg-amber-500 rounded-full flex-shrink-0" />{err}</li>)}</ul>
              </div>
            )}
            {triageFinished && (
              <div className="mb-3 rounded-lg p-3 flex items-center gap-2.5" style={{ background: 'rgba(220,252,231,0.6)', border: '1px solid rgba(134,239,172,0.4)' }}>
                <CheckCircle className="w-4 h-4 text-green-600 flex-shrink-0" />
                <div>
                  <p className="text-xs font-bold text-green-800">Triage completed successfully</p>
                  <p className="text-[10px] text-green-600">Patient assigned {categoryResult.category} at {triageFinishTime?.toLocaleTimeString()}.{categoryResult.category === 'RED' || categoryResult.category === 'ORANGE' ? ' Treatment timer has started.' : ` Doctor should see patient within ${categoryResult.maxTimeToDoctor}.`}</p>
                </div>
              </div>
            )}
            {bedPlaced && (
              <div className="mb-3 rounded-lg p-3 flex items-center gap-2.5" style={{ background: 'rgba(207,250,254,0.6)', border: '1px solid rgba(103,232,249,0.4)' }}>
                <BedDouble className="w-4 h-4 text-cyan-600 flex-shrink-0" />
                <div>
                  <p className="text-xs font-bold text-cyan-800">Patient placed in bed {bedPlaced.code}</p>
                  <p className="text-[10px] text-cyan-700">
                    Zone: {bedPlaced.zone}
                    {bedPlaced.hasMonitor ? ' · Monitor will begin streaming vitals automatically.' : ' · No monitor assigned to this bed.'}
                  </p>
                </div>
              </div>
            )}
            <div className="flex items-center justify-between">
              <button onClick={() => navigate(-1)} className="px-4 py-2 bg-white/80 border border-slate-200/60 text-slate-700 rounded-xl text-xs font-semibold hover:bg-white hover:shadow-md transition-all flex items-center gap-1.5">
                <ArrowLeft className="w-3.5 h-3.5" /> Back
              </button>
              <div className="flex items-center gap-3">
                {triageFinished ? (
                  <button onClick={() => navigate('/dashboard')} className="px-5 py-2 bg-gradient-to-r from-cyan-600 to-slate-800 text-white rounded-xl text-xs font-bold hover:shadow-xl transition-all hover:-translate-y-0.5">Return to Dashboard</button>
                ) : (
                  <button onClick={handleFinishTriage} disabled={!canFinish} className="px-5 py-2 bg-gradient-to-r from-cyan-600 to-slate-800 text-white rounded-xl text-xs font-bold hover:shadow-xl transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1.5 hover:-translate-y-0.5">
                    <Save className="w-3.5 h-3.5" /> Finish Triage
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>

      </div>

      {/* Bed-suggestion confirm modal (Phase G #2). Renders only after the
          backend returns a suggestion — typically RED/ORANGE/YELLOW. The
          nurse confirms; cancel just dismisses without placing. */}
      {suggestedBed && (
        <BedSuggestionModal
          bed={suggestedBed}
          category={categoryResult.category}
          placing={placingBed}
          error={bedError}
          onConfirm={handleConfirmPlaceBed}
          onCancel={() => { setSuggestedBed(null); setBedError(null); }}
        />
      )}
    </div>
  );
}
