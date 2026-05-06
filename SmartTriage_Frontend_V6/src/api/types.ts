/* ═══════════════════════════════════════════════════════════════
   Backend API Type Definitions
   Maps 1:1 to Spring Boot DTOs from SmartTriage-server
   ═══════════════════════════════════════════════════════════════ */

// ── Generic Response Wrappers ──

export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

// ── Enums ──

export type Gender = 'MALE' | 'FEMALE' | 'OTHER' | 'UNKNOWN';
export type Role = 'SUPER_ADMIN' | 'HOSPITAL_ADMIN' | 'DOCTOR' | 'TRIAGE_NURSE' | 'NURSE' | 'REGISTRAR' | 'PARAMEDIC' | 'LAB_TECHNICIAN' | 'READ_ONLY';
export type ArrivalMode = 'WALK_IN' | 'AMBULANCE' | 'REFERRAL' | 'POLICE' | 'HELICOPTER' | 'OTHER';
export type VisitStatus = 'REGISTERED' | 'AWAITING_TRIAGE' | 'TRIAGED' | 'AWAITING_ASSESSMENT' | 'UNDER_ASSESSMENT' | 'UNDER_TREATMENT' | 'UNDER_OBSERVATION' | 'PENDING_DISPOSITION' | 'DISCHARGED' | 'ADMITTED' | 'TRANSFERRED' | 'ICU_ADMITTED' | 'LEFT_WITHOUT_BEING_SEEN' | 'DECEASED';
export type TriageCategory = 'RED' | 'ORANGE' | 'YELLOW' | 'GREEN' | 'BLUE';
export type DispositionType = 'DISCHARGED_HOME' | 'ADMITTED_TO_WARD' | 'ICU_ADMISSION' | 'TRANSFERRED' | 'LEFT_AGAINST_MEDICAL_ADVICE' | 'LEFT_WITHOUT_BEING_SEEN' | 'DECEASED';
export type AvpuScore = 'ALERT' | 'CONFUSED' | 'VERBAL' | 'PAIN' | 'UNRESPONSIVE';
export type VitalSource = 'MANUAL_ENTRY' | 'IOT_DEVICE' | 'AMBULANCE_MONITOR' | 'IMPORTED';
export type MobilityStatus = 'WALKING' | 'WITH_HELP' | 'STRETCHER';
export type TraumaStatus = 'NO_TRAUMA' | 'TRAUMA';
export type NoteType = 'PHYSICAL_FINDINGS' | 'PROGRESS_NOTE' | 'NURSING_NOTE' | 'DOCTOR_NOTE' | 'TRIAGE_NOTE' | 'HISTORY_OF_PRESENTING_COMPLAINT' | 'PAST_MEDICAL_HISTORY' | 'SOCIAL_HISTORY' | 'FAMILY_HISTORY' | 'REVIEW_OF_SYSTEMS' | 'ALLERGIES' | 'CURRENT_MEDICATIONS' | 'TREATMENT_PLAN' | 'DISCHARGE_SUMMARY' | 'HANDOVER' | 'OTHER';
export type DiagnosisType = 'PROVISIONAL' | 'CONFIRMED' | 'DIFFERENTIAL' | 'WORKING';
export type InvestigationType = 'LABORATORY' | 'RADIOLOGY' | 'ECG' | 'ULTRASOUND' | 'CT_SCAN' | 'MRI' | 'XRAY' | 'BLOOD_GAS' | 'URINALYSIS' | 'RAPID_TEST' | 'POINT_OF_CARE' | 'OTHER';
export type InvestigationStatus = 'ORDERED' | 'SPECIMEN_COLLECTED' | 'IN_PROGRESS' | 'RESULTED' | 'CANCELLED';
export type MedicationRoute = 'PO' | 'IV' | 'IM' | 'SC' | 'SL' | 'PR' | 'INH' | 'NEB' | 'TOP' | 'NASAL' | 'OPHTHALMIC' | 'OTIC' | 'ETT' | 'IO' | 'OTHER';
export type MedicationStatus = 'PRESCRIBED' | 'ADMINISTERED' | 'HELD' | 'REFUSED' | 'CANCELLED';
export type DeviceType = 'ESP32_MONITOR' | 'PULSE_OXIMETER' | 'ECG_MONITOR' | 'BP_MONITOR' | 'TEMPERATURE_PROBE' | 'GLUCOMETER' | 'AMBULANCE_MONITOR' | 'OTHER';
export type DeviceStatus = 'REGISTERED' | 'ONLINE' | 'OFFLINE' | 'MONITORING' | 'ERROR' | 'DECOMMISSIONED';
export type SignalQuality = 'GOOD' | 'ACCEPTABLE' | 'POOR' | 'INVALID' | 'UNKNOWN';
export type AlertSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
export type AlertType = 'TEWS_CRITICAL' | 'TEWS_ESCALATION' | 'VITAL_SIGN_ABNORMAL' | 'RETRIAGE_REQUIRED' | 'WAITING_TIME_EXCEEDED' | 'DETERIORATION_DETECTED' | 'SEPSIS_SCREENING' | 'PEDIATRIC_SAFETY' | 'REASSESSMENT_DUE' | 'CRITICAL_LAB_RESULT' | 'IOT_DEVICE_DISCONNECTED' | 'IOT_DEVICE_LOW_BATTERY' | 'IOT_SIGNAL_QUALITY_DEGRADED' | 'IOT_AUTO_RETRIAGE' | 'DOCTOR_NOTIFICATION' | 'DOCTOR_ESCALATION' | 'SURGE_WARNING' | 'INVESTIGATION_RESULTED';
export type EdZone = 'RESUS' | 'ACUTE' | 'GENERAL' | 'TRIAGE' | 'OBSERVATION' | 'ISOLATION' | 'PEDIATRIC';
export type ShiftPeriod = 'DAY' | 'NIGHT';
export type ShiftFunction = 'CHARGE_NURSE' | 'TRIAGE_NURSE' | 'ZONE_NURSE' | 'PRIMARY_DOCTOR' | 'SUPERVISING_DOCTOR' | 'RESIDENT';
export type Designation =
  | 'ED_HEAD' | 'CONSULTANT' | 'SENIOR_MEDICAL_OFFICER' | 'MEDICAL_OFFICER' | 'RESIDENT' | 'INTERN'
  | 'CHARGE_NURSE' | 'SENIOR_NURSE' | 'STAFF_NURSE' | 'STUDENT_NURSE'
  | 'HEAD_LAB_TECHNICIAN' | 'LAB_TECHNICIAN'
  | 'SENIOR_REGISTRAR' | 'REGISTRAR'
  | 'SENIOR_PARAMEDIC' | 'PARAMEDIC'
  | 'UNSPECIFIED';
export type AccountStatus = 'PENDING_ACTIVATION' | 'ACTIVE' | 'DEACTIVATED';
export type PregnancyStatus = 'PREGNANT' | 'BREASTFEEDING' | 'POSSIBLY_PREGNANT' | 'NOT_PREGNANT' | 'NOT_APPLICABLE' | 'UNKNOWN';

// ── Auth ──

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RefreshTokenRequest {
  refreshToken: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  userId: string;
  email: string;
  firstName: string;
  lastName: string;
  role: Role;
  designation: Designation | null;
  designationLabel: string | null;
  hospitalId: string;
  hospitalName: string;
}

// ── Hospital ──

export interface CreateHospitalRequest {
  name: string;
  hospitalCode: string;
  address?: string;
  city?: string;
  province?: string;
  country?: string;
  phoneNumber?: string;
  email?: string;
  tier?: string;
  bedCapacity?: number;
  edCapacity?: number;
  icuCapacity?: number;
}

export interface HospitalResponse {
  id: string;
  name: string;
  hospitalCode: string;
  address: string;
  city: string;
  province: string;
  country: string;
  phoneNumber: string;
  email: string;
  tier: string;
  bedCapacity: number;
  edCapacity: number;
  icuCapacity: number;
  createdAt: string;
  updatedAt: string;
}

// ── User ──

export interface CreateUserRequest {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  phoneNumber?: string;
  role: Role;
  designation?: Designation;
  employeeNumber?: string;
  professionalLicense?: string;
  department?: string;
  hospitalId: string;
}

export interface UserResponse {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  phoneNumber: string;
  role: Role;
  designation: Designation | null;
  designationLabel: string | null;
  employeeNumber: string;
  professionalLicense: string;
  department: string;
  hospitalId: string;
  hospitalName: string;
  accountStatus: AccountStatus;
  createdAt: string;
  updatedAt: string;
}

// ── Invitation Flow ──

export interface InviteUserRequest {
  email: string;
  role: Role;
  designation?: Designation;
  department?: string;
  hospitalId: string;
}

export interface ActivateAccountRequest {
  token: string;
  firstName: string;
  lastName: string;
  password: string;
  phoneNumber?: string;
  employeeNumber?: string;
  professionalLicense?: string;
}

export interface InvitationTokenInfo {
  email: string;
  role: string;
  hospitalName: string;
  expired: boolean;
  used: boolean;
}

// ── Patient ──

export interface CreatePatientRequest {
  firstName: string;
  lastName: string;
  dateOfBirth?: string;
  gender: Gender;
  nationalId?: string;
  phoneNumber?: string;
  address?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  /** Legal guardian — required for pediatric patients, leave undefined for adults. */
  guardianName?: string;
  guardianPhone?: string;
  guardianRelationship?: string;
  guardianNationalId?: string;
  bloodType?: string;
  knownAllergies?: string;
  chronicConditions?: string;
  hospitalId: string;
}

/** Combined registration request — creates Patient + Visit atomically */
export interface RegisterPatientRequest extends CreatePatientRequest {
  arrivalMode?: ArrivalMode;
  chiefComplaint?: string;
  referringFacility?: string;
}

/** Combined registration response */
export interface RegisterPatientResponse {
  patient: PatientResponse;
  visit: VisitResponse;
}

export interface PatientResponse {
  id: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: Gender;
  nationalId: string;
  phoneNumber: string;
  address: string;
  emergencyContactName: string;
  emergencyContactPhone: string;
  /** Legal guardian for pediatric patients; null for adults. */
  guardianName: string | null;
  guardianPhone: string | null;
  guardianRelationship: string | null;
  guardianNationalId: string | null;
  bloodType: string;
  knownAllergies: string;
  chronicConditions: string;
  pregnancyStatus: PregnancyStatus | null;
  pregnancyStatusRecordedAt: string | null;
  medicalRecordNumber: string;
  ageInYears: number;
  isPediatric: boolean;
  hospitalId: string;
  createdAt: string;
  updatedAt: string;
}

// ── Visit ──

export interface CreateVisitRequest {
  patientId: string;
  hospitalId: string;
  arrivalMode?: ArrivalMode;
  chiefComplaint?: string;
  referringFacility?: string;
}

export interface VisitResponse {
  id: string;
  visitNumber: string;
  patientId: string;
  patientName: string;
  hospitalId: string;
  arrivalMode: ArrivalMode;
  arrivalTime: string;
  chiefComplaint: string;
  status: VisitStatus;
  currentTriageCategory: TriageCategory | null;
  currentTewsScore: number | null;
  triageTime: string | null;
  assessmentStartTime: string | null;
  dispositionType: DispositionType | null;
  dispositionTime: string | null;
  dispositionNotes: string | null;
  referringFacility: string | null;
  isPediatric: boolean;
  retriageCount: number;
  createdAt: string;
  updatedAt: string;
}

// ── Vital Signs ──

export interface RecordVitalsRequest {
  visitId: string;
  respiratoryRate?: number;
  heartRate?: number;
  systolicBp?: number;
  diastolicBp?: number;
  temperature?: number;
  spo2?: number;
  avpu?: AvpuScore;
  bloodGlucose?: number;
  painScore?: number;
  gcsScore?: number;
  source?: VitalSource;
  deviceId?: string;
  notes?: string;
}

export interface VitalSignsResponse {
  id: string;
  visitId: string;
  respiratoryRate: number;
  heartRate: number;
  systolicBp: number;
  diastolicBp: number;
  temperature: number;
  spo2: number;
  avpu: AvpuScore;
  bloodGlucose: number;
  painScore: number;
  gcsScore: number;
  source: VitalSource;
  deviceId: string | null;
  notes: string;
  recordedById: string;
  recordedByName: string;
  recordedAt: string;
  createdAt: string;
}

// ── Triage ──

export interface PerformTriageRequest {
  visitId: string;
  // Emergency Signs
  hasAirwayCompromise?: boolean;
  hasBreathingDistress?: boolean;
  hasSevereRespiratoryDistress?: boolean;
  hasCardiacArrest?: boolean;
  hasUncontrolledHaemorrhage?: boolean;
  hasStabGunWoundNeckChest?: boolean;
  hasConvulsions?: boolean;
  convulsionGlucose?: number | null;
  hasComa?: boolean;
  comaGlucose?: number | null;
  hasHypoglycaemia?: boolean;
  hasPurpuricRash?: boolean;
  hasBurnFaceInhalation?: boolean;
  // Child-Specific Emergency Signs
  childCentralCyanosis?: boolean;
  childPulseLowOrAbsent?: boolean;
  childColdHandsComposite?: boolean;
  childColdHandsLethargic?: boolean;
  childColdHandsPulseWeakFast?: boolean;
  childColdHandsCapRefill?: boolean;
  childSevereDehydration?: boolean;
  childDehydrationSkinPinch?: boolean;
  childDehydrationLethargy?: boolean;
  childDehydrationSunkenEyes?: boolean;
  childWeightKg?: number | null;
  childHeightCm?: number | null;
  // Additional Vitals (recorded but not TEWS-scored)
  spo2?: number;
  diastolicBp?: number;
  bloodGlucose?: number;
  painScore?: number;
  weightKg?: number;
  heightCm?: number;
  // Vitals from triage form — used for TEWS calculation
  respiratoryRate?: number;
  heartRate?: number;
  systolicBP?: number;
  temperature?: number;
  // TEWS Components
  mobility: MobilityStatus;
  avpu: AvpuScore;
  traumaStatus: TraumaStatus;
  vitalSignsId?: string | null;
  // Very Urgent — Medical
  vuFocalNeurologicDeficit?: boolean;
  vuAlteredMentalStatus?: boolean;
  vuNeurologicalGlucose?: number | null;
  vuChestPain?: boolean;
  vuPoisoningOverdose?: boolean;
  vuPregnantAbdominalPain?: boolean;
  vuCoughingVomitingBlood?: boolean;
  vuDiabeticHighGlucose?: boolean;
  vuDiabeticGlucose?: number | null;
  vuAggression?: boolean;
  vuShortnessOfBreath?: boolean;
  // Very Urgent — Trauma
  vuBurnOver20Percent?: boolean;
  vuOpenFracture?: boolean;
  vuThreatenedLimb?: boolean;
  vuEyeInjury?: boolean;
  vuLargeJointDislocation?: boolean;
  vuSevereMechanismOfInjury?: boolean;
  vuVerySeverePain?: boolean;
  vuPregnantAbdominalTrauma?: boolean;
  // Urgent Signs
  urgUnableToDrinkVomits?: boolean;
  urgAbdominalPain?: boolean;
  urgVeryPale?: boolean;
  urgPregnantVaginalBleeding?: boolean;
  urgDiabeticVeryHighGlucose?: boolean;
  urgDiabeticGlucose?: number | null;
  urgFingerToeDislocation?: boolean;
  urgClosedFracture?: boolean;
  urgBurnWithoutUrgentSigns?: boolean;
  urgPregnantTraumaNonAbdominal?: boolean;
  urgModeratePain?: boolean;
  urgLacerationAbscess?: boolean;
  urgForeignBodyAspiration?: boolean;
  // Clinical Metadata
  presentingComplaints?: string;
  clinicalNotes?: string;
  // Special Considerations
  specialAcuteTrauma?: boolean;
  specialSeizureHistory?: boolean;
  specialAssaultAbuse?: boolean;
  specialSuicideAttempt?: boolean;
  // Form Footer — Nurse & Doctor Notification
  triageNurseName?: string;
  notifiedDoctorName?: string;
  doctorNotifiedAt?: string;   // ISO 8601
  attendingDoctorName?: string;
  doctorAttendedAt?: string;   // ISO 8601
}

export interface TriageRecordResponse {
  id: string;
  visitId: string;
  triagedById: string;
  triagedByName: string;
  vitalSignsId: string | null;
  triageTime: string;
  // All input fields echoed back
  hasAirwayCompromise: boolean;
  hasBreathingDistress: boolean;
  hasSevereRespiratoryDistress: boolean;
  hasCardiacArrest: boolean;
  hasUncontrolledHaemorrhage: boolean;
  hasStabGunWoundNeckChest: boolean;
  hasConvulsions: boolean;
  convulsionGlucose: number | null;
  hasComa: boolean;
  comaGlucose: number | null;
  hasHypoglycaemia: boolean;
  hasPurpuricRash: boolean;
  hasBurnFaceInhalation: boolean;
  childCentralCyanosis: boolean;
  childPulseLowOrAbsent: boolean;
  childColdHandsComposite: boolean;
  childColdHandsLethargic: boolean;
  childColdHandsPulseWeakFast: boolean;
  childColdHandsCapRefill: boolean;
  childSevereDehydration: boolean;
  childDehydrationSkinPinch: boolean;
  childDehydrationLethargy: boolean;
  childDehydrationSunkenEyes: boolean;
  childWeightKg: number | null;
  childHeightCm: number | null;
  mobility: MobilityStatus;
  avpu: AvpuScore;
  traumaStatus: TraumaStatus;
  vuFocalNeurologicDeficit: boolean;
  vuAlteredMentalStatus: boolean;
  vuNeurologicalGlucose: number | null;
  vuChestPain: boolean;
  vuPoisoningOverdose: boolean;
  vuPregnantAbdominalPain: boolean;
  vuCoughingVomitingBlood: boolean;
  vuDiabeticHighGlucose: boolean;
  vuDiabeticGlucose: number | null;
  vuAggression: boolean;
  vuShortnessOfBreath: boolean;
  vuBurnOver20Percent: boolean;
  vuOpenFracture: boolean;
  vuThreatenedLimb: boolean;
  vuEyeInjury: boolean;
  vuLargeJointDislocation: boolean;
  vuSevereMechanismOfInjury: boolean;
  vuVerySeverePain: boolean;
  vuPregnantAbdominalTrauma: boolean;
  urgUnableToDrinkVomits: boolean;
  urgAbdominalPain: boolean;
  urgVeryPale: boolean;
  urgPregnantVaginalBleeding: boolean;
  urgDiabeticVeryHighGlucose: boolean;
  urgDiabeticGlucose: number | null;
  urgFingerToeDislocation: boolean;
  urgClosedFracture: boolean;
  urgBurnWithoutUrgentSigns: boolean;
  urgPregnantTraumaNonAbdominal: boolean;
  urgModeratePain: boolean;
  urgLacerationAbscess: boolean;
  urgForeignBodyAspiration: boolean;
  presentingComplaints: string;
  clinicalNotes: string;
  // General patient weight / height captured at triage. childWeightKg above
  // is the pediatric-form-specific echo; weightKg is the canonical column
  // on triage_records used for medication-safety pediatric mg/kg dosing.
  weightKg: number | null;
  heightCm: number | null;
  specialAcuteTrauma: boolean;
  specialSeizureHistory: boolean;
  specialAssaultAbuse: boolean;
  specialSuicideAttempt: boolean;
  // Computed results
  tewsScore: number;
  triageCategory: TriageCategory;
  decisionPath: string;
  isChildForm: boolean;
  isRetriage: boolean;
  isSystemTriggered: boolean;
  previousCategory: TriageCategory | null;
  triageNurseName: string;
  notifiedDoctorName: string | null;
  doctorNotifiedAt: string | null;
  attendingDoctorName: string | null;
  doctorAttendedAt: string | null;
  // ── Bed Suggestion (Phase G #2) ──
  // Populated only on the response from POST /triage (performTriage), so
  // the form can show the nurse a "Place in suggested bed?" confirm. Null
  // on subsequent reads (history, getLatest) — those return the stored
  // record without re-running the suggestion engine.
  suggestedBedId?: string | null;
  suggestedBedCode?: string | null;
  suggestedBedZone?: EdZone | null;
  suggestedBedHasMonitor?: boolean;
  createdAt: string;
}

// ── Bed Seed Defaults (Phase G #4) ──

/**
 * Result returned by POST /api/v1/beds/hospital/{hospitalId}/seed-defaults.
 * Mirrors BedService.SeedResult on the server.
 */
export interface SeedResult {
  bedsCreated: number;
  zonesSeeded: EdZone[];
  zonesSkipped: EdZone[];
  tierUsed: string;
}

// ── Clinical Notes ──

export interface CreateClinicalNoteRequest {
  visitId: string;
  noteType: NoteType;
  content: string;
  recordedByName?: string;
  section?: string;
}

export interface ClinicalNoteResponse {
  id: string;
  visitId: string;
  noteType: NoteType;
  content: string;
  recordedById: string;
  recordedByName: string;
  section: string;
  createdAt: string;
  updatedAt: string;
}

// ── Diagnoses ──

export interface CreateDiagnosisRequest {
  visitId: string;
  diagnosisType: DiagnosisType;
  icdCode?: string;
  description: string;
  diagnosedByName?: string;
  isPrimary?: boolean;
  notes?: string;
}

export interface DiagnosisResponse {
  id: string;
  visitId: string;
  diagnosisType: DiagnosisType;
  icdCode: string;
  description: string;
  diagnosedById: string;
  diagnosedByName: string;
  isPrimary: boolean;
  notes: string;
  createdAt: string;
  updatedAt: string;
}

// ── Investigations ──

export interface OrderInvestigationRequest {
  visitId: string;
  investigationType: InvestigationType;
  testName: string;
  orderedByName?: string;
  priority?: string;
  notes?: string;
}

export interface RecordInvestigationResultRequest {
  investigationId: string;
  result: string;
  isAbnormal?: boolean;
  isCritical?: boolean;
  notes?: string;
}

export interface InvestigationResponse {
  id: string;
  visitId: string;
  investigationType: InvestigationType;
  testName: string;
  orderedById: string;
  orderedByName: string;
  priority: string;
  status: InvestigationStatus;
  result: string | null;
  isAbnormal: boolean;
  isCritical: boolean;
  notes: string;
  orderedAt: string;
  specimenCollectedAt: string | null;
  resultedAt: string | null;
  cancelledAt: string | null;
  cancellationReason: string | null;
  createdAt: string;
  updatedAt: string;
}

// ── Medications ──

export interface PrescribeMedicationRequest {
  visitId: string;
  drugName: string;
  dose?: string;
  route: MedicationRoute;
  frequency?: string;
  prescribedByName?: string;
  notes?: string;
}

export interface AdministerMedicationRequest {
  medicationId: string;
  administeredByName: string;
  notes?: string;
}

export interface CountersignMedicationRequest {
  medicationId: string;
  countersignedByName: string;
  notes?: string;
}

export interface MedicationResponse {
  id: string;
  visitId: string;
  drugName: string;
  dose: string;
  route: MedicationRoute;
  frequency: string;
  status: MedicationStatus;
  prescribedById: string;
  prescribedByName: string;
  prescribedAt: string;
  administeredById: string | null;
  administeredByName: string | null;
  administeredAt: string | null;
  countersignedById: string | null;
  countersignedByName: string | null;
  countersignedAt: string | null;
  holdReason: string | null;
  refusalReason: string | null;
  cancellationReason: string | null;
  notes: string;
  createdAt: string;
  updatedAt: string;
}

// ── IoT Devices ──

export interface RegisterDeviceRequest {
  serialNumber: string;
  deviceName: string;
  deviceType: DeviceType;
  hospitalId: string;
  firmwareVersion?: string;
  macAddress?: string;
  location?: string;
  heartbeatTimeoutSeconds?: number;
  dataIntervalSeconds?: number;
  notes?: string;
}

export interface DeviceResponse {
  id: string;
  serialNumber: string;
  deviceName: string;
  deviceType: DeviceType;
  hospitalId: string;
  status: DeviceStatus;
  firmwareVersion: string;
  macAddress: string;
  location: string;
  lastHeartbeatAt: string | null;
  lastDataAt: string | null;
  batteryLevel: number | null;
  wifiRssi: number | null;
  ipAddress: string | null;
  apiKey: string | null;
  activeVisitId: string | null;
  notes: string;
  createdAt: string;
  updatedAt: string;
}

export interface StartMonitoringRequest {
  deviceId: string;
  visitId: string;
  startedByName?: string;
}

export interface DeviceSessionResponse {
  id: string;
  deviceId: string;
  deviceName: string;
  deviceSerialNumber: string;
  visitId: string;
  visitNumber: string;
  startedByName: string;
  startedAt: string;
  endedByName: string | null;
  endedAt: string | null;
  endReason: string | null;
  sessionActive: boolean;
  totalReadings: number;
  rejectedReadings: number;
  alertsGenerated: number;
  retriagesTriggered: number;
  createdAt: string;
}

export interface VitalStreamResponse {
  id: string;
  visitId: string;
  deviceId: string;
  sessionId: string;
  capturedAt: string;
  receivedAt: string;
  heartRate: number | null;
  spo2: number | null;
  respiratoryRate: number | null;
  temperature: number | null;
  systolicBp: number | null;
  diastolicBp: number | null;
  bloodGlucose: number | null;
  ecgRhythm: string | null;
  ecgQrsDuration: number | null;
  ecgStDeviation: number | null;
  signalQuality: SignalQuality;
  spo2PerfusionIndex: number | null;
  isValidated: boolean;
  rejectionReason: string | null;
  batteryLevel: number | null;
  wifiRssi: number | null;
  sequenceNumber: number;
}

// ── Alerts ──

export interface ClinicalAlertResponse {
  id: string;
  visitId: string;
  visitNumber: string | null;
  patientName: string;
  alertType: AlertType;
  severity: AlertSeverity;
  title: string | null;
  message: string;
  // Zone-aware escalation
  targetZone: EdZone | null;
  escalationTier: number;
  escalatedAt: string | null;
  targetDoctorId: string | null;
  targetDoctorName: string | null;
  satsTargetMinutes: number | null;
  // Acknowledgment
  acknowledged: boolean;
  acknowledgedById: string | null;
  acknowledgedByName: string | null;
  acknowledgedAt: string | null;
  autoGenerated: boolean;
  createdAt: string;
}

// ── Shift Assignments ──

export interface CreateShiftAssignmentRequest {
  userId: string;
  zone: EdZone;
  shiftFunction: ShiftFunction;
  /** Optional — set to true to also grant the shift-lead badge (transfers it from any current holder). */
  isShiftLead?: boolean;
}

export interface ShiftAssignmentResponse {
  id: string;
  hospitalId: string;
  shiftDate: string;
  shiftPeriod: ShiftPeriod;
  userId: string;
  userName: string;
  userEmail: string;
  userRole: Role;
  userDesignation: Designation | null;
  userDesignationLabel: string | null;
  zone: EdZone;
  shiftFunction: ShiftFunction;
  startedAt: string | null;
  endedAt: string | null;
  active: boolean;
  /**
   * Shift-lead badge. Exactly one active assignment per (hospital, shift_date,
   * shift_period) may carry this flag at a time.
   */
  isShiftLead: boolean;
}

export interface ShiftPeriodInfo {
  shiftDate: string;
  shiftPeriod: ShiftPeriod;
}

// ── Shift Templates ──

/** One row inside a shift template — default placement for a specific user. */
export interface ShiftTemplateAssignmentDto {
  /** Undefined when creating; server assigns it on save. */
  id?: string;
  userId: string;
  /** Read-only enrichment from server. */
  userName?: string;
  /** Read-only enrichment from server. */
  userEmail?: string;
  zone: EdZone;
  shiftFunction: ShiftFunction;
  isShiftLead: boolean;
}

/** POST / PUT body for creating or replacing a template. */
export interface UpsertShiftTemplateRequest {
  name: string;
  description?: string;
  shiftPeriod: ShiftPeriod;
  assignments: ShiftTemplateAssignmentDto[];
}

/** Server response shape for a shift template. */
export interface ShiftTemplateResponse {
  id: string;
  hospitalId: string;
  name: string;
  description: string | null;
  shiftPeriod: ShiftPeriod;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  assignments: ShiftTemplateAssignmentDto[];
}

// ──────────────────────────────────────────────────────────────────────
// BED MANAGEMENT
// ──────────────────────────────────────────────────────────────────────

/**
 * Lifecycle of a treatment space. Only OCCUPIED implies a linked visit.
 * Discharge / transfer always moves the bed through CLEANING so infection
 * control and vitals-contamination rules are respected.
 */
export type BedStatus = 'AVAILABLE' | 'OCCUPIED' | 'CLEANING' | 'OUT_OF_SERVICE';

export interface BedResponse {
  id: string;
  hospitalId: string;
  zone: EdZone;
  code: string;
  label: string | null;
  status: BedStatus;
  hasMonitor: boolean;
  displayOrder: number;
  notes: string | null;

  // Occupant (null when not OCCUPIED)
  currentVisitId: string | null;
  currentVisitNumber: string | null;
  currentPatientName: string | null;
  currentTriageCategory: string | null; // "RED" | "ORANGE" | "YELLOW" | "GREEN" | "BLUE"
  currentTewsScore: number | null;
  currentPlacedAt: string | null;

  // Assigned monitor (null when bed has no permanently-mounted device)
  assignedDeviceId: string | null;
  assignedDeviceName: string | null;
  assignedDeviceStatus: DeviceStatus | null;

  // Active monitoring session (non-null iff bed is OCCUPIED and paired to a device)
  activeSessionId: string | null;

  createdAt: string;
  updatedAt: string;
}

export interface CreateBedRequest {
  hospitalId: string;
  zone: EdZone;
  code: string;
  label?: string;
  hasMonitor?: boolean;
  displayOrder?: number;
  notes?: string;
}

export interface UpdateBedRequest {
  code?: string;
  label?: string;
  hasMonitor?: boolean;
  displayOrder?: number;
  notes?: string;
}

export interface PlacePatientRequest {
  visitId: string;
}

export interface TransferPatientRequest {
  destinationBedId: string;
  reason?: string;
}

export interface AssignDeviceRequest {
  /** null / undefined → detach any currently-assigned device. */
  deviceId: string | null;
}

/** Aggregated zone snapshot used by the bed-grid header. */
export interface ZoneOccupancyResponse {
  zone: EdZone;
  zoneLabel: string;
  totalBeds: number;
  occupied: number;
  available: number;
  cleaning: number;
  outOfService: number;
  beds: BedResponse[];
}
