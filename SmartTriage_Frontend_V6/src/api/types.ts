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

// UNKNOWN is reserved for placeholder / unidentified patients
// (Direct Resus, EMS unknown arrivals) — never offered in registration UIs.
export type Gender = 'MALE' | 'FEMALE' | 'UNKNOWN';

/**
 * Phase 13b — structured pregnancy / lactation status. Mirrors the
 * backend `PregnancyStatus` enum.
 *  - PREGNANT — known active pregnancy.
 *  - BREASTFEEDING — postpartum lactation; drug crosses into milk.
 *  - POSSIBLY_PREGNANT — childbearing-age + missed period / unsure.
 *  - NOT_PREGNANT — explicitly ruled out.
 *  - NOT_APPLICABLE — male / pre-menarche / post-menopausal.
 *  - UNKNOWN — recorded but indeterminate.
 *  - null on the wire — never recorded; teratogen check falls back
 *    to chronicConditions free-text scan.
 */
export type PregnancyStatus =
  | 'PREGNANT'
  | 'BREASTFEEDING'
  | 'POSSIBLY_PREGNANT'
  | 'NOT_PREGNANT'
  | 'NOT_APPLICABLE'
  | 'UNKNOWN';
export type Role = 'SUPER_ADMIN' | 'HOSPITAL_ADMIN' | 'DOCTOR' | 'NURSE' | 'REGISTRAR' | 'PARAMEDIC' | 'LAB_TECHNICIAN' | 'READ_ONLY';
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
export type AlertType = 'TEWS_CRITICAL' | 'TEWS_ESCALATION' | 'VITAL_SIGN_ABNORMAL' | 'RETRIAGE_REQUIRED' | 'WAITING_TIME_EXCEEDED' | 'DETERIORATION_DETECTED' | 'SEPSIS_SCREENING' | 'PEDIATRIC_SAFETY' | 'REASSESSMENT_DUE' | 'CRITICAL_LAB_RESULT' | 'IOT_DEVICE_DISCONNECTED' | 'IOT_DEVICE_LOW_BATTERY' | 'IOT_SIGNAL_QUALITY_DEGRADED' | 'IOT_AUTO_RETRIAGE' | 'DOCTOR_NOTIFICATION' | 'DOCTOR_ESCALATION' | 'SURGE_WARNING' | 'INVESTIGATION_RESULTED' | 'MEDICATION_SAFETY_WARNING';
export type EdZone = 'RESUS' | 'ACUTE' | 'GENERAL' | 'AMBULATORY' | 'TRIAGE' | 'OBSERVATION' | 'ISOLATION' | 'PEDIATRIC' | 'NEONATAL';
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
  /** Optional. Server auto-generates from name initials (e.g. "King Faisal Hospital" → "KFH-001") when omitted. */
  hospitalCode?: string;
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
  hasPediatricResus?: boolean;
  hasNeonatalUnit?: boolean;
  twoStepVerificationEnabled?: boolean;
  // Structured Rwanda location FKs (V46+)
  provinceId?: string;
  districtId?: string;
  sectorId?: string;
  cellId?: string;
  villageId?: string;
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
  hasPediatricResus: boolean;
  hasNeonatalUnit: boolean;
  twoStepVerificationEnabled: boolean;
  active: boolean;
  provinceId: string | null;
  districtId: string | null;
  sectorId: string | null;
  cellId: string | null;
  villageId: string | null;
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
  /** Rwanda NID — primary deterministic id for adults. */
  nationalId?: string;
  /** Passport number — primary deterministic id for foreign nationals. */
  passportNumber?: string;
  /** Birth-certificate number — primary deterministic id for pediatric patients. */
  birthCertificateNumber?: string;
  phoneNumber?: string;
  address?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  bloodType?: string;
  knownAllergies?: string;
  chronicConditions?: string;
  // ── Guardian (pediatric) ──
  guardianNationalId?: string;
  guardianPhone?: string;
  guardianName?: string;
  guardianRelationship?: string;
  hospitalId: string;
  // ── V46+ structured Rwanda location IDs ──
  // All five levels are independent inputs; pass any subset. Backend
  // resolves IDs to entity FKs and silently drops unknown IDs after
  // logging, so a partially-loaded reference dataset doesn't block
  // registration.
  provinceId?: string;
  districtId?: string;
  sectorId?: string;
  cellId?: string;
  villageId?: string;
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
  passportNumber: string | null;
  birthCertificateNumber: string | null;
  phoneNumber: string;
  address: string;
  emergencyContactName: string;
  emergencyContactPhone: string;
  bloodType: string;
  knownAllergies: string;
  chronicConditions: string;
  /**
   * Phase 13b — structured pregnancy / lactation status. Drives the
   * teratogen safety check at prescribe time. NULL means "never
   * recorded" → safety check falls back to free-text scan of
   * chronicConditions (legacy behaviour).
   */
  pregnancyStatus: PregnancyStatus | null;
  pregnancyStatusRecordedAt: string | null;
  guardianNationalId: string | null;
  guardianPhone: string | null;
  guardianName: string | null;
  guardianRelationship: string | null;
  medicalRecordNumber: string;
  ageInYears: number;
  isPediatric: boolean;
  hospitalId: string;

  // ── Direct Resus placeholder (V44) ──
  /**
   * TRUE while this patient was admitted as a phonetic placeholder
   * via Direct Resus and identity has not yet been resolved.
   * Drives the "?" badge / italic display name in the UI.
   */
  isUnidentified: boolean;
  /**
   * Phonetic label assigned at admission ("Alpha", "Bravo-2"). Preserved
   * across identity resolution as an audit anchor — chart review of the
   * resolved patient can still see they were admitted as Unknown Alpha.
   * NULL for normally-registered patients.
   */
  placeholderLabel: string | null;
  placeholderAssignedAt: string | null;
  identifiedAt: string | null;
  identifiedByName: string | null;

  createdAt: string;
  updatedAt: string;
}

// ── Patient Lookup (federated identity) ──

/**
 * Why a row matched a patient lookup. See backend
 * com.smartTriage.smartTriage_server.common.enums.MatchType.
 *
 * Tier 1 (deterministic) | Tier 2 (MRN) | Tier 3 (soft) | Tier 4 (demographic)
 */
export type MatchType =
  | 'NATIONAL_ID'
  | 'PASSPORT'
  | 'BIRTH_CERTIFICATE'
  | 'MRN'
  | 'PHONE_AND_DOB'
  | 'PHONE'
  | 'GUARDIAN_NATIONAL_ID'
  | 'GUARDIAN_PHONE'
  | 'DEMOGRAPHIC';

/**
 * Query parameters for /patients/hospital/{id}/lookup. Pass any combination
 * of identifiers — the backend unions matchers and returns a ranked list.
 */
export interface PatientLookupParams {
  nationalId?: string;
  passport?: string;
  birthCertificate?: string;
  mrn?: string;
  phone?: string;
  guardianNationalId?: string;
  guardianPhone?: string;
  firstName?: string;
  lastName?: string;
  /** ISO date YYYY-MM-DD. */
  dob?: string;
}

/**
 * One ranked candidate. The triage UI renders these as cards; the nurse
 * picks one (→ pre-fill registration) or "register new".
 */
export interface PatientLookupCandidate {
  patientId: string;
  medicalRecordNumber: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string | null;
  ageInYears: number | null;
  isPediatric: boolean;
  gender: Gender | null;
  /** Last 4 chars of NID — full NID is intentionally not in candidates. */
  nationalIdLast4: string | null;
  /** ISO instant of most recent active visit, null if none. */
  lastVisitAt: string | null;
  hospitalId: string;
  matchType: MatchType;
  /** 0.00 – 1.00 — 1.00 = deterministic Tier-1 hit. */
  confidence: number;
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
  /**
   * Identity fields populated by VisitMapper from the joined patient.
   * Carrying DOB rather than a pre-computed age lets the frontend
   * render months-granular ages for infants and stay correct over time.
   */
  patientDateOfBirth: string | null;
  patientGender: Gender | null;
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
  /** Phase 1 zone routing — canonical zone the patient is currently in. */
  currentEdZone: EdZone | null;
  /** Doctor of record (soft binding); null until first clinical action. */
  primaryClinicianId: string | null;
  primaryClinicianName: string | null;

  // ── Direct Resus Admission flags (V44) ──
  /**
   * TRUE when the visit was admitted to RESUS but no bed was available.
   * Frontend surfaces the resus-overflow banner + transfer prompt.
   */
  pendingResusOverflow: boolean;
  /**
   * TRUE when the visit was created from an ambulance call-ahead before
   * the patient physically arrived. Door clock has not started until
   * arrivalConfirmedAt is set.
   */
  ambulancePreArrival: boolean;
  /** Door-clock anchor for ambulance pre-arrivals. Null until confirmed. */
  arrivalConfirmedAt: string | null;

  /** Phase 1 EMS — link to pre-hospital run record. Null for walk-ins. */
  emsRunId?: string | null;
  /** Paramedic's field triage call. Authoritative ED triage is on TriageRecord. */
  fieldTriageCategory?: string | null;
  /** When the ED nurse must re-triage by. */
  edRetriageDueAt?: string | null;

  createdAt: string;
  updatedAt: string;

  // ── Shift-handoff priority signals ──
  // Populated by the backend on active-visits list endpoints (not on
  // single visit-by-id reads — the detail page already has the full
  // collections). Drive at-a-glance priority badges on patient cards
  // so an inheriting doctor sees "3 pending labs, 1 critical result
  // back, ICU pending" without opening the chart.
  pendingInvestigationsCount?: number | null;
  unacknowledgedCriticalResultsCount?: number | null;
  pendingMedicationsCount?: number | null;
  hasOpenIcuEscalation?: boolean | null;
}

// ── Direct Resus Admission (V44) ──

/**
 * Request for POST /api/v1/admissions/direct-resus.
 *
 * Two modes:
 * - existing patient: set `patientId`
 * - unidentified arrival: leave `patientId` null and set `hospitalId`;
 *   the server creates a placeholder Patient ("Unknown Alpha")
 */
export interface DirectResusAdmissionRequest {
  patientId?: string | null;
  hospitalId?: string | null;
  /** One short clinical phrase ("cardiac arrest", "GSW to chest"). Required. */
  reason: string;
  isPediatric: boolean;
  arrivalMode?: ArrivalMode | null;
  /** TRUE for ambulance call-aheads — patient not yet physically present. */
  ambulancePreArrival?: boolean;
  /** Pre-hospital interventions, vitals, ETA — free text. */
  preArrivalNotes?: string | null;
  /** "MALE" / "FEMALE" / "OTHER" — used only for unidentified arrivals. */
  estimatedGender?: string | null;
}

export interface TransferCandidateInfo {
  visitId: string;
  visitNumber: string;
  bedId: string;
  bedCode: string;
  patientDisplayName: string;
  currentCategory: string;
  admitCategory: string;
  placedAt: string | null;
  minutesInBed: number;
  suggestedDestinationZone: EdZone | null;
  rationale: string;
}

export interface DirectResusAdmissionResponse {
  visitId: string;
  visitNumber: string;
  patientId: string;
  patientFirstName: string;
  patientLastName: string;
  isUnidentified: boolean;
  /** Phonetic label ("Alpha", "Bravo-2") if patient was created as placeholder. */
  placeholderLabel: string | null;
  triageRecordId: string;
  bedId: string | null;
  bedCode: string | null;
  bedZone: EdZone | null;
  bedHasMonitor: boolean;
  /** TRUE when no RESUS bed was available; transferCandidates is populated. */
  overflow: boolean;
  transferCandidates: TransferCandidateInfo[];
  /** TRUE when the patient was admitted as unidentified — show "Set Identity" CTA. */
  identityRequired: boolean;
  arrivalTime: string | null;
  ambulancePreArrival: boolean;
}

/**
 * Request for POST /api/v1/patients/{id}/resolve-identity.
 * Either firstName + lastName, OR mergeIntoPatientId, is required.
 */
export interface ResolveIdentityRequest {
  firstName?: string;
  lastName?: string;
  dateOfBirth?: string;
  gender?: Gender;
  nationalId?: string;
  phoneNumber?: string;
  address?: string;
  /** Set to merge the placeholder into an existing patient record. */
  mergeIntoPatientId?: string;
  resolutionNote?: string;
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
  /** Phase 12b — adult body weight in kg. Drives Cockcroft-Gault eGFR. */
  weightKg?: number;
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
  /** Phase 12b — adult body weight in kg. Nullable for most rows. */
  weightKg: number | null;
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
  // V38 — KFH peds form Very Urgent (peds-only)
  vuPedsMoreSleepyThanNormal?: boolean;
  vuPedsInconsolableSeverePain?: boolean;
  vuPedsFloppyIrritableRestless?: boolean;
  vuPedsTinyBabyUnder2Months?: boolean;
  vuPedsBurnOver10Percent?: boolean;
  // V38 — KFH peds form Urgent (peds-only)
  urgPedsPittingEdemaFaceOrFeet?: boolean;
  urgPedsSomeRespiratoryDistress?: boolean;
  urgPedsSevereMalnutritionWasting?: boolean;
  urgPedsUnwellWithKnownDiabetes?: boolean;
  urgPedsDiarrheaVomitingDehydration?: boolean;
  urgPedsDehydrationSunkenEyes?: boolean;
  urgPedsDehydrationDryMouth?: boolean;
  urgPedsDehydrationDecreasedUrine?: boolean;
  urgPedsDehydrationSlowSkinPinch?: boolean;
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
  /** V56 — precise user-id when picked from the on-duty dropdown. */
  notifiedDoctorUserId?: string;
  /** V56 — precise user-id when picked from the on-duty dropdown. */
  attendingDoctorUserId?: string;
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
  // Round 3 — system-triggered re-triage audit. Populated only when
  // isSystemTriggered=true; null otherwise. The label resolves
  // server-side from ClinicalSignDefinitions so we don't have to ship
  // the catalog twice for the audit message.
  triggeringSignEventId: string | null;
  triggeringSignCode: string | null;
  triggeringSignLabel: string | null;
  triggeringSignStatus: 'PRESENT' | 'ABSENT' | 'IMPROVING' | 'WORSENING' | 'UNKNOWN' | null;
  triggeringSignRecordedAt: string | null;
  triageNurseName: string;
  notifiedDoctorName: string | null;
  doctorNotifiedAt: string | null;
  attendingDoctorName: string | null;
  doctorAttendedAt: string | null;

  // ── Bed Suggestion (Phase G #2) ──
  // Populated only on the response from POST /triage (performTriage), so
  // the form can show the nurse a "Place in suggested bed?" confirm.
  // Null on subsequent reads (history, getLatest) — those return the
  // stored record without re-running the suggestion engine.
  suggestedBedId?: string | null;
  suggestedBedCode?: string | null;
  suggestedBedZone?: EdZone | null;
  suggestedBedHasMonitor?: boolean;
  /**
   * Option A — true when the backend already placed the patient in the
   * suggested bed as part of this triage submission. Frontend shows a
   * success toast instead of the BedSuggestionModal. False means the
   * nurse needs to pick a bed manually (no bed available, or a rare
   * placement race lost) — fall back to the modal.
   */
  autoPlaced?: boolean;
  /** Human-readable note for the success/warning toast. */
  autoPlacementNote?: string | null;

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
  /** Display name of the author (server-derived from the authenticated user). */
  recordedByName: string;
  /**
   * UUID of the User who wrote the note. Server-derived from the security
   * context — never set from the client. Null only for legacy rows created
   * before V21.
   */
  authorUserId: string | null;
  /**
   * Role of the author at write time (DOCTOR, NURSE, ...). Captured at write
   * time so the timeline still renders correctly even if the user's role
   * changes later.
   */
  authorRole: Role | null;
  /**
   * If this note corrects an earlier one, the original's id. Non-null
   * indicates a supersede; the original row remains visible in the timeline.
   */
  supersedesId: string | null;
  recordedAt: string;
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
  /** Phase 12b — optional numeric value (e.g. 1.8 for "Cr 1.8 mg/dL"). */
  resultNumeric?: number;
  /** Phase 12b — unit string ("mg/dL", "µmol/L", "mmol/L", …). */
  resultUnit?: string;
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
  /** Phase 12b — principal scalar value, drives Cockcroft-Gault eGFR. */
  resultNumeric: number | null;
  resultUnit: string | null;
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
  /** TRUE when the prescriber acknowledged a known-allergy conflict in
   *  the PrescribeSafetyDialog and chose to prescribe anyway. The
   *  backend stamps the timestamp and persists the matches snapshot. */
  prescribedDespiteAllergy?: boolean;
  /** Free-text snapshot of the matches the dialog showed at decision
   *  time, formatted by formatAllergyMatches() in utils/allergyCheck. */
  allergyOverrideMatches?: string;
  /** TRUE when the prescriber acknowledged a drug–drug interaction in
   *  the PrescribeSafetyDialog and chose to prescribe anyway. */
  prescribedDespiteInteraction?: boolean;
  /** Free-text snapshot of the interaction conflicts at decision time,
   *  formatted by formatInteractionMatches() in utils/interactionCheck. */
  interactionOverrideMatches?: string;
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
  /** TRUE when this medication was prescribed against a known patient
   *  allergy. Drives the amber "Allergy override" badge on the
   *  medication card. */
  prescribedDespiteAllergy?: boolean | null;
  /** Free-text snapshot of conflicts at prescribe time. */
  allergyOverrideMatches?: string | null;
  /** Server timestamp of the override acknowledgement. */
  allergyOverrideAcknowledgedAt?: string | null;
  /** TRUE when this medication was prescribed despite a drug–drug
   *  interaction with another active medication on the same visit.
   *  Drives the orange "Interaction override" badge. */
  prescribedDespiteInteraction?: boolean | null;
  /** Free-text snapshot of interaction conflicts at prescribe time. */
  interactionOverrideMatches?: string | null;
  /** Server timestamp of the interaction override acknowledgement. */
  interactionOverrideAcknowledgedAt?: string | null;
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
  /** V53 — admin inventory flag. true = active pool. */
  inService: boolean;
  /** V54 — admin triage-zone flag. true = surfaces in triage form's monitor picker. */
  triageMonitor: boolean;
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

/**
 * Clinical-facing monitoring lifecycle state — see the backend
 * MonitoringState enum for the full transition table.
 */
export type MonitoringState =
  | 'NOT_STARTED'
  | 'STARTING'
  | 'LIVE'
  | 'DEGRADED'
  | 'STALLED'
  | 'PAUSED'
  | 'DISCONNECTED'
  | 'ENDED';

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
  trendStatus: 'WORSENING' | 'STABLE' | 'IMPROVING' | 'UNKNOWN' | null;
  trendUpdatedAt: string | null;
  monitoringState: MonitoringState;
  monitoringStateAt: string | null;
  pausedAt: string | null;
  pausedByName: string | null;
  resumedAt: string | null;
  resumedByName: string | null;
  continuityGroupId: string | null;
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
  // Round 4a — RETRIAGE_REQUIRED trigger audit. Populated server-side
  // when the alert was created from a worsening clinical-sign event.
  // The frontend's click-handler routes the nurse to a triage form
  // pre-flagged with this sign.
  triggeringSignEventId: string | null;
  triggeringSignCode: string | null;
  triggeringSignLabel: string | null;
}

// ── Shift Assignments ──

export interface CreateShiftAssignmentRequest {
  userId: string;
  zone: EdZone;
  shiftFunction: ShiftFunction;
  /** Optional — set to true to also grant the shift-lead badge (transfers it from any current holder). */
  isShiftLead?: boolean;
  /**
   * Target shift date (ISO yyyy-MM-dd). Optional — when omitted the server
   * uses today's current shift. When set, {@link shiftPeriod} must also be
   * set; both-or-neither is enforced server-side. Past dates are rejected.
   */
  shiftDate?: string;
  /** Target shift period. Must be set together with {@link shiftDate}. */
  shiftPeriod?: ShiftPeriod;
}

/** Bulk shift-planning op: copy one full week of assignments to another. */
export interface CopyWeekRequest {
  /** Monday of the source week, ISO yyyy-MM-dd. */
  fromWeekStart: string;
  /** Monday of the target week, ISO yyyy-MM-dd. */
  toWeekStart: string;
}

/** Bulk shift-planning op: materialise a template across a date range. */
export interface ApplyTemplateRequest {
  templateId: string;
  fromDate: string;
  toDate: string;
  /** Periods to apply the template to. Server rejects period mismatches. */
  periods: ShiftPeriod[];
  /**
   * V55 — how to handle a slot that already has a roster.
   * FILL_EMPTY (default) skips occupied slots; OVERWRITE replaces them.
   * The manual "Apply Template" UI button sends OVERWRITE.
   */
  mode?: 'FILL_EMPTY' | 'OVERWRITE';
}

/**
 * V56 — One option in the Triage form's Notified Doctor / Attending Doctor
 * picker. Returned by GET /shifts/hospital/{hospitalId}/doctors-on-duty?zone=X.
 * Sorted server-side by clinical hierarchy: PRIMARY_DOCTOR → SUPERVISING_DOCTOR
 * → RESIDENT.
 */
export interface DoctorOnDutyResponse {
  userId: string;
  fullName: string;
  shiftFunction: 'PRIMARY_DOCTOR' | 'SUPERVISING_DOCTOR' | 'RESIDENT';
  zone: EdZone;
  shiftLead: boolean;
  /** Zone-aggregate active patient count (proxy for "how busy"). */
  zonePatientCount: number;
  /** Last activity timestamp, or null when not tracked. */
  lastActiveAt: string | null;
}

export interface BulkPlanResultSlot {
  date: string;
  period: 'DAY' | 'NIGHT';
  /** "FILLED" | "REPLACED" | "SKIPPED_EXISTING" | "SKIPPED_NO_SOURCE" | "SKIPPED_PAST" */
  status: string;
  rowsCreated: number;
  note: string | null;
}

export interface BulkPlanResult {
  slotsFilled: number;
  /** V55 — slots where the existing roster was soft-deleted and replaced. */
  slotsReplaced: number;
  slotsSkipped: number;
  rowsCreated: number;
  slots: BulkPlanResultSlot[];
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

/* ─────────────────────── Charge Nurse Delegation ─────────────────────── */

export interface ChargeNurseDelegationResponse {
  id: string;
  hospitalId: string;
  delegatingUserId: string;
  delegatingUserName: string;
  delegateUserId: string;
  delegateUserName: string;
  startsAt: string;
  endsAt: string | null;
  reason: string;
  revokedAt: string | null;
  revokedById: string | null;
  revokedByName: string | null;
  revocationReason: string | null;
  currentlyActive: boolean;
}

export interface CreateChargeNurseDelegationRequest {
  delegateUserId: string;
  startsAt: string;
  endsAt?: string | null;
  reason: string;
}

export interface RevokeChargeNurseDelegationRequest {
  revocationReason?: string;
}

/* ─────────────────────── Staff Leave ─────────────────────── */

export type LeaveType =
  | 'ANNUAL'
  | 'SICK'
  | 'MATERNITY'
  | 'BEREAVEMENT'
  | 'COMPASSIONATE'
  | 'STUDY'
  | 'OTHER';

export type LeaveStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

export interface StaffLeaveResponse {
  id: string;
  hospitalId: string;
  userId: string;
  userName: string;
  leaveType: LeaveType;
  leaveStatus: LeaveStatus;
  startsOn: string;   // YYYY-MM-DD
  endsOn: string;     // YYYY-MM-DD
  reason: string | null;
  requestedAt: string;
  requestedById: string | null;
  requestedByName: string | null;
  approvedAt: string | null;
  approvedById: string | null;
  approvedByName: string | null;
  rejectedAt: string | null;
  rejectedById: string | null;
  rejectedByName: string | null;
  rejectionReason: string | null;
  cancelledAt: string | null;
  cancelledById: string | null;
  cancelledByName: string | null;
  externalReference: string | null;
}

export interface CreateStaffLeaveRequest {
  /** Omit to file leave for yourself; CN/admin only when present. */
  userId?: string;
  leaveType: LeaveType;
  startsOn: string;
  endsOn: string;
  reason?: string;
  /** CN-only: create the row already in APPROVED status. */
  autoApprove?: boolean;
}

export interface LeaveDecisionRequest {
  note?: string;
}

/* ─────────────────────── Shift Swap ─────────────────────── */

export type SwapStatus =
  | 'REQUESTED'
  | 'PENDING_PARTNER_ACCEPT'
  | 'PENDING_CHARGE_APPROVAL'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED';

export interface SwapAssignmentSnapshot {
  assignmentId: string;
  userId: string;
  userName: string;
  shiftDate: string;
  shiftPeriod: ShiftPeriod;
  zone: EdZone;
  shiftFunction: ShiftFunction;
}

export interface ShiftSwapResponse {
  id: string;
  hospitalId: string;
  status: SwapStatus;
  requestReason: string | null;
  requesterSide: SwapAssignmentSnapshot;
  partnerSide: SwapAssignmentSnapshot;
  createdAt: string;
  partnerRespondedAt: string | null;
  partnerResponseNote: string | null;
  chargeRespondedAt: string | null;
  chargeResponderId: string | null;
  chargeResponderName: string | null;
  chargeResponseNote: string | null;
  cancelledAt: string | null;
  cancelledById: string | null;
  cancelledByName: string | null;
  rejectionReason: string | null;
}

export interface CreateShiftSwapRequest {
  requesterAssignmentId: string;
  partnerAssignmentId: string;
  requestReason?: string;
}

export interface SwapDecisionRequest {
  note?: string;
}
