// Core Types
export type TriageCategory = 'RED' | 'ORANGE' | 'YELLOW' | 'GREEN' | 'BLUE';
export type AVPU = 'A' | 'V' | 'P' | 'U';
export type Gender = 'MALE' | 'FEMALE' | 'OTHER';
export type ArrivalMode = 'WALK_IN' | 'AMBULANCE' | 'REFERRAL';
export type Mobility = 'AMBULATORY' | 'WHEELCHAIR' | 'STRETCHER';

// Contact Person (next of kin / responsible party)
export interface ContactPerson {
  name: string;
  phone: string;
  relationship: string;
}

// Guardian (mandatory for pediatric patients)
export interface Guardian {
  name: string;
  phone: string;
  relationship: string;
  nationalId?: string;
}

// Audit Log Entry
export type AuditAction =
  | 'PATIENT_REGISTERED'
  | 'PATIENT_UPDATED'
  | 'TRIAGE_STARTED'
  | 'TRIAGE_COMPLETED'
  | 'CATEGORY_ASSIGNED'
  | 'CATEGORY_OVERRIDDEN'
  | 'VITALS_RECORDED'
  | 'ALERT_ACKNOWLEDGED'
  | 'NURSE_ASSIGNED'
  | 'DEMOGRAPHICS_EDITED';

export interface AuditLogEntry {
  id: string;
  timestamp: Date;
  action: AuditAction;
  performedBy: string; // clinician/user ID
  performedByName: string;
  patientId?: string;
  details: string;
  previousValue?: string;
  newValue?: string;
}

// Emergency Signs
export interface EmergencySigns {
  airwayCompromise: boolean;
  coma: boolean; // AVPU = P or U
  severeRespiratoryDistress: boolean;
  severeBurns: boolean;
  shockSigns: boolean;
  convulsions: boolean;
  hypoglycemia: boolean;
}

// TEWS Inputs
export interface TEWSInput {
  mobility: Mobility;
  temperature: number; // °C
  respiratoryRate: number; // breaths/min
  avpu: AVPU;
  pulse: number; // bpm
  trauma: boolean;
  systolicBP: number; // mmHg
  spo2: number; // %
}

// Patient
export interface Patient {
  id: string;
  fullName: string;
  age: number;
  ageMonths?: number; // month-level precision for pediatric
  gender: Gender;
  nationalId?: string;
  chiefComplaint: string;
  arrivalMode: ArrivalMode;
  mobility?: Mobility; // physical transport mode on arrival
  referringFacility?: string;
  referralDocument?: string;
  referralDocumentFile?: File; // uploaded referral document
  arrivalTimestamp: Date;
  isPediatric: boolean;
  weight?: number; // kg, mandatory for pediatric
  triageStatus: 'WAITING' | 'IN_TRIAGE' | 'TRIAGED' | 'IN_TREATMENT';
  category?: TriageCategory;
  categoryAssignedAt?: Date;
  emergencySigns?: EmergencySigns;
  tewsInput?: TEWSInput;
  tewsScore?: number;
  vitals?: VitalSigns;
  aiAlerts: AIAlert[];
  overrideHistory: Override[];
  // Module 1 additions
  contactPerson?: ContactPerson;
  guardian?: Guardian; // mandatory for pediatric
  assignedNurseId?: string;
  assignedNurseName?: string;
  registrationCompletedAt?: Date;
  registeredBy?: string; // clinician who registered
}

// Vital Signs (IoT)
export interface VitalSigns {
  heartRate: number; // bpm
  respiratoryRate: number; // breaths/min
  spo2: number; // %
  systolicBP: number; // mmHg
  diastolicBP: number; // mmHg
  temperature: number; // °C
  ecg: number; // mV (ST-segment deviation, ~0 normal)
  ecgRhythm?: string; // e.g. "NSR", "AF", "SVT"
  ecgQrsDuration?: number; // ms
  glucose: number; // mg/dL
  timestamp: Date;
  deviceConnected: boolean;
}

// Vital Reading (for trends)
export interface VitalReading {
  timestamp: Date;
  value: number;
}

// AI Alert
export interface AIAlert {
  id: string;
  patientId: string;
  timestamp: Date;
  type: 'DETERIORATION' | 'THRESHOLD_BREACH' | 'TREND_WARNING' | 'DOCTOR_NOTIFICATION';
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  message: string;
  title?: string;
  previousCategory?: TriageCategory;
  recommendedCategory?: TriageCategory;
  contributingFactors: string[];
  acknowledged: boolean;
  acknowledgedBy?: string;
  acknowledgedAt?: Date;
  comment?: string;
  // Zone-aware escalation fields
  targetZone?: string;
  escalationTier?: number;
  targetDoctorName?: string;
  satsTargetMinutes?: number;
  visitNumber?: string;
  patientName?: string;
}

// Override
export interface Override {
  id: string;
  timestamp: Date;
  clinicianId: string;
  clinicianName: string;
  originalCategory: TriageCategory;
  newCategory: TriageCategory;
  reason: string;
}

// Pediatric Thresholds
export interface PediatricThresholds {
  ageGroup: 'INFANT' | 'TODDLER' | 'CHILD' | 'ADOLESCENT';
  heartRate: { min: number; max: number };
  respiratoryRate: { min: number; max: number };
  systolicBP: { min: number };
  spo2Threshold: number;
}

// TEWS Scoring Rules
export interface TEWSScoring {
  mobilityScore: number;
  temperatureScore: number;
  respiratoryRateScore: number;
  avpuScore: number;
  pulseScore: number;
  traumaScore: number;
  systolicBPScore: number;
  totalScore: number;
}

// Dashboard Stats
export interface DashboardStats {
  totalPatients: number;
  criticalCount: number;
  averageTEWS: number;
  waitingForTriage: number;
  inTriage: number;
  triaged: number;
  categoryBreakdown: {
    RED: number;
    ORANGE: number;
    YELLOW: number;
    GREEN: number;
    BLUE: number;
  };
}

// Timer State
export interface TimerState {
  patientId: string;
  category: TriageCategory;
  targetMinutes: number; // Based on category
  elapsedMinutes: number;
  percentComplete: number;
  status: 'NORMAL' | 'WARNING' | 'OVERDUE' | 'CRITICAL';
}

// ── Module 3: TEWS Score History & Trends ──────────────

/** A single TEWS calculation snapshot */
export interface TEWSHistoryEntry {
  id: string;
  timestamp: Date;
  /** Individual parameter scores */
  scoring: TEWSScoring;
  /** Category assigned at this calculation */
  category: TriageCategory;
  /** Reason for category (e.g. "TEWS score 6 (5-6)") */
  categoryReason: string;
  /** SpO2 at time of calculation */
  spo2?: number;
  /** Whether emergency signs were present */
  hadEmergencySigns: boolean;
  /** Whether discriminators contributed to category */
  discriminatorApplied: boolean;
  /** Who performed this calculation */
  performedBy?: string;
}

/** Direction of TEWS score trend */
export type TEWSTrendDirection = 'IMPROVING' | 'STABLE' | 'WORSENING';

/** Computed trend information from TEWS history */
export interface TEWSTrend {
  /** Current score */
  currentScore: number;
  /** Previous score (null if first calculation) */
  previousScore: number | null;
  /** Change from previous calculation */
  delta: number;
  /** Trend direction */
  direction: TEWSTrendDirection;
  /** Number of consecutive calculations in same direction */
  consecutiveCount: number;
  /** Rate of change per hour (score units / hr) */
  ratePerHour: number | null;
  /** Whether trend warrants an alert */
  alertRequired: boolean;
  /** Alert message if applicable */
  alertMessage?: string;
  /** Recommended action */
  recommendation?: string;
}

/** Summary statistics for a patient's TEWS history */
export interface TEWSHistorySummary {
  entryCount: number;
  highestScore: number;
  lowestScore: number;
  averageScore: number;
  currentCategory: TriageCategory;
  worstCategory: TriageCategory;
  firstCalculation: Date | null;
  lastCalculation: Date | null;
  totalDurationMinutes: number;
  categoryChanges: number;
}

// ── Module 4: IoT Vital Integration ──────────────

/** Types of IoT medical devices */
export type DeviceType =
  | 'PULSE_OXIMETER'
  | 'ECG_MONITOR'
  | 'BP_MONITOR'
  | 'THERMOMETER'
  | 'GLUCOMETER'
  | 'MULTI_PARAMETER'
  | 'RESPIRATORY_MONITOR';

/** Device connection status */
export type ConnectionStatus =
  | 'DISCONNECTED'
  | 'SCANNING'
  | 'PAIRING'
  | 'CONNECTED'
  | 'RECONNECTING'
  | 'ERROR';

/** Signal quality level */
export type SignalQuality = 'EXCELLENT' | 'GOOD' | 'FAIR' | 'POOR' | 'LOST';

/** IoT device health snapshot */
export interface DeviceHealth {
  batteryPercent: number;         // 0-100
  signalStrength: number;        // 0-100 (RSSI mapped)
  signalQuality: SignalQuality;
  lastDataReceived: Date | null;
  dataDropRate: number;           // 0-1 (percentage of missed packets)
  uptimeMinutes: number;
  firmwareUpToDate: boolean;
  errorCount: number;
  lastError?: string;
}

/** IoT medical device */
export interface IoTDevice {
  id: string;
  name: string;
  type: DeviceType;
  manufacturer: string;
  model: string;
  serialNumber: string;
  firmwareVersion: string;
  connectionStatus: ConnectionStatus;
  health: DeviceHealth;
  /** Patient currently paired to (null if unpaired) */
  pairedPatientId: string | null;
  /** When device was paired to current patient */
  pairedAt: Date | null;
  /** Vital parameters this device provides */
  providedVitals: (keyof Omit<VitalSigns, 'timestamp' | 'deviceConnected'>)[];
  /** Whether streaming real-time data */
  isStreaming: boolean;
  /** Sampling interval in milliseconds */
  samplingIntervalMs: number;
  /** Connection history entries */
  connectionLog: DeviceConnectionEvent[];
}

/** Device connection event log entry */
export interface DeviceConnectionEvent {
  timestamp: Date;
  event: 'CONNECTED' | 'DISCONNECTED' | 'RECONNECTED' | 'ERROR' | 'PAIRED' | 'UNPAIRED' | 'BATTERY_LOW' | 'SIGNAL_LOST';
  details: string;
}

/** Device-augmented vital reading */
export interface DeviceVitalReading extends VitalReading {
  deviceId: string;
  signalQuality: SignalQuality;
  isInterpolated: boolean;  // true when device was briefly disconnected and value was estimated
}

/** Summary of all devices connected to a patient */
export interface PatientDeviceSummary {
  patientId: string;
  connectedDevices: number;
  totalDevices: number;
  overallHealth: 'HEALTHY' | 'WARNING' | 'CRITICAL';
  lowestBattery: number;
  weakestSignal: SignalQuality;
  anyDisconnected: boolean;
  coveredVitals: string[];
  uncoveredVitals: string[];
}
