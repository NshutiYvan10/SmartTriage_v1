/* ── EMS / Paramedic API ── */
import { get, post, patch, downloadBlob } from './client';

export type EmsRunStatus =
  | 'DISPATCHED' | 'EN_ROUTE' | 'ARRIVED' | 'HANDED_OFF' | 'CANCELLED';

export type EmsService = 'SAMU' | 'HOSPITAL' | 'PRIVATE' | 'OTHER';

export type EmsInterventionType =
  | 'OXYGEN' | 'IV_ACCESS' | 'FLUID' | 'MEDICATION'
  | 'DEFIBRILLATION' | 'AIRWAY' | 'IMMOBILISATION'
  | 'SPLINTING' | 'TOURNIQUET' | 'CPR' | 'OTHER';

export type FieldTriageCategory = 'RED' | 'ORANGE' | 'YELLOW' | 'GREEN' | 'BLUE';

export type MobilityStatus = 'WALKING' | 'WITH_HELP' | 'STRETCHER';
export type AvpuScore = 'ALERT' | 'CONFUSED' | 'VERBAL' | 'PAIN' | 'UNRESPONSIVE';
export type TraumaStatus = 'NO_TRAUMA' | 'TRAUMA';

export interface EmsIntervention {
  id: string;
  type: EmsInterventionType;
  givenAt: string;
  givenByName: string | null;
  detail: string | null;
  dose: string | null;
  route: string | null;
  outcome: string | null;
  notes: string | null;
}

export interface EmsRun {
  id: string;
  hospitalId: string;
  visitId: string | null;
  /** Linked patient (null for a pre-arrival run with no visit yet). */
  patientId: string | null;
  /** Patient display name once a visit is linked; null for an unlinked pre-arrival run. */
  patientName: string | null;
  visitNumber: string | null;
  paramedicUserId: string | null;
  paramedicName: string | null;
  service: EmsService;
  unitCallsign: string | null;

  dispatchedAt: string;
  sceneArrivedAt: string | null;
  sceneLeftAt: string | null;
  edArrivedAt: string | null;
  handedOffAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;

  patientAgeYears: number | null;
  patientSex: string | null;
  incidentLocation: string | null;
  mechanism: string | null;
  historySummary: string | null;
  injuriesObserved: string | null;

  fieldTriageCategory: FieldTriageCategory | null;
  fieldTriageReason: string | null;
  fieldTewsScore: number | null;
  fieldTriageDecisionPath: string | null;
  fieldTriageIsChild: boolean | null;
  fieldTriageInput: string | null;

  fieldGcs: number | null;
  fieldRespRate: number | null;
  fieldHr: number | null;
  fieldSbp: number | null;
  fieldDbp: number | null;
  fieldSpo2: number | null;
  fieldTemp: number | null;
  fieldGlucose: number | null;

  status: EmsRunStatus;
  handedOffToUserId: string | null;
  handedOffToName: string | null;
  handoverAcknowledgementText: string | null;

  etaMinutes: number | null;
  notes: string | null;

  lightsActive: boolean;
  lightsActivatedAt: string | null;

  preArrivalAckedAt: string | null;
  preArrivalAckedByName: string | null;

  /** When the ED acknowledged the patient AT THE DOOR (acking the EMS_ARRIVED alert). */
  arrivalAckedAt: string | null;
  arrivalAckedByName: string | null;

  createdAt: string;
  updatedAt: string;

  interventions?: EmsIntervention[];
}

export interface CreateEmsRunRequest {
  hospitalId: string;
  service?: EmsService;
  unitCallsign?: string;
  paramedicName?: string;
  patientAgeYears?: number;
  patientSex?: string;
  incidentLocation?: string;
  mechanism?: string;
  historySummary?: string;
}

export interface UpdateEmsRunRequest {
  unitCallsign?: string;
  paramedicName?: string;
  patientAgeYears?: number;
  patientSex?: string;
  incidentLocation?: string;
  mechanism?: string;
  historySummary?: string;
  injuriesObserved?: string;
  fieldTriageCategory?: FieldTriageCategory;
  fieldTriageReason?: string;
  fieldGcs?: number;
  fieldRespRate?: number;
  fieldHr?: number;
  fieldSbp?: number;
  fieldDbp?: number;
  fieldSpo2?: number;
  fieldTemp?: number;
  fieldGlucose?: number;
  etaMinutes?: number;
  notes?: string;
}

export interface AddInterventionRequest {
  type: EmsInterventionType;
  givenAt?: string;
  givenByName?: string;
  detail?: string;
  dose?: string;
  route?: string;
  outcome?: string;
  notes?: string;
}

export interface PreregisterRequest {
  patientId?: string;
  etaMinutes?: number;
  preArrivalNote?: string;
}

export interface TransferOfCareRequest {
  receivedByName?: string;
  acknowledgementText?: string;
}

/**
 * Field-triage submission. Vitals + TEWS components + a focused set of
 * emergency / very-urgent / urgent discriminators. The backend runs the
 * SAME engine the ED uses and returns the computed category/TEWS.
 */
export interface FieldTriageRequest {
  respiratoryRate?: number;
  heartRate?: number;
  systolicBp?: number;
  diastolicBp?: number;
  spo2?: number;
  temperature?: number;
  bloodGlucose?: number;
  gcs?: number;
  painScore?: number;

  mobility?: MobilityStatus;
  avpu?: AvpuScore;
  traumaStatus?: TraumaStatus;

  isChild?: boolean;
  reason?: string;
  /** Must be true to record a re-compute that LOWERS acuity below a prior computed category. */
  acknowledgeDowngrade?: boolean;

  // Emergency signs (any → RED)
  hasAirwayCompromise?: boolean;
  hasSevereRespiratoryDistress?: boolean;
  hasCardiacArrest?: boolean;
  hasUncontrolledHaemorrhage?: boolean;
  hasStabGunWoundNeckChest?: boolean;
  hasConvulsions?: boolean;
  hasComa?: boolean;
  hasHypoglycaemia?: boolean;
  hasBurnFaceInhalation?: boolean;
  childCentralCyanosis?: boolean;
  childPulseLowOrAbsent?: boolean;

  // Very urgent
  vuAlteredMentalStatus?: boolean;
  vuFocalNeurologicDeficit?: boolean;
  vuChestPain?: boolean;
  vuShortnessOfBreath?: boolean;
  vuPoisoningOverdose?: boolean;
  vuCoughingVomitingBlood?: boolean;
  vuSevereMechanismOfInjury?: boolean;
  vuOpenFracture?: boolean;
  vuThreatenedLimb?: boolean;
  vuVerySeverePain?: boolean;
  vuBurnOver20Percent?: boolean;

  // Urgent
  urgAbdominalPain?: boolean;
  urgModeratePain?: boolean;
  urgClosedFracture?: boolean;
  urgLacerationAbscess?: boolean;
  urgVeryPale?: boolean;
  urgUnableToDrinkVomits?: boolean;
}

export interface RerouteRequest {
  hospitalId: string;
  reason?: string;
}

export interface PatientHistory {
  known: boolean;
  displayName: string | null;
  unidentified: boolean;
  knownAllergies: string | null;
  chronicConditions: string | null;
  bloodType: string | null;
  priorVisitCount: number;
  lastVisitAt: string | null;
}

export interface DestinationHospital {
  id: string;
  name: string | null;
  hospitalCode: string | null;
  city: string | null;
}

export const emsApi = {
  create: (body: CreateEmsRunRequest) => post<EmsRun>('/ems/runs', body),

  update: (id: string, body: UpdateEmsRunRequest) =>
    patch<EmsRun>(`/ems/runs/${id}`, body),

  addIntervention: (id: string, body: AddInterventionRequest) =>
    post<EmsRun>(`/ems/runs/${id}/interventions`, body),

  fieldTriage: (id: string, body: FieldTriageRequest) =>
    post<EmsRun>(`/ems/runs/${id}/field-triage`, body),

  setLights: (id: string, active: boolean) =>
    post<EmsRun>(`/ems/runs/${id}/lights?active=${active}`, {}),

  reroute: (id: string, body: RerouteRequest) =>
    post<EmsRun>(`/ems/runs/${id}/reroute`, body),

  patientHistory: (id: string) =>
    get<PatientHistory>(`/ems/runs/${id}/patient-history`),

  destinations: () =>
    get<DestinationHospital[]>('/ems/destinations'),

  preregister: (id: string, body?: PreregisterRequest) =>
    post<EmsRun>(`/ems/runs/${id}/preregister`, body ?? {}),

  confirmArrival: (id: string) =>
    post<EmsRun>(`/ems/runs/${id}/confirm-arrival`, {}),

  transferOfCare: (id: string, body?: TransferOfCareRequest) =>
    post<EmsRun>(`/ems/runs/${id}/transfer-of-care`, body ?? {}),

  cancel: (id: string, reason?: string) =>
    post<EmsRun>(`/ems/runs/${id}/cancel${reason ? `?reason=${encodeURIComponent(reason)}` : ''}`, {}),

  getById: (id: string) => get<EmsRun>(`/ems/runs/${id}`),

  myRuns: () => get<EmsRun[]>('/ems/runs/mine'),

  getInbound: (hospitalId: string) =>
    get<EmsRun[]>(`/ems/hospital/${hospitalId}/inbound`),

  getByVisit: (visitId: string) =>
    get<EmsRun | null>(`/ems/visits/${visitId}`),

  /** Download the run's Patient Care Report (PCR) as a PDF (blob + filename from the server). */
  downloadPcr: (id: string) => downloadBlob(`/ems/runs/${id}/pcr`, `pcr-${id}.pdf`),
};
