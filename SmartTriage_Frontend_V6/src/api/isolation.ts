import { get, post, put } from './client';

export type IsolationTypeValue = 'AIRBORNE' | 'DROPLET' | 'CONTACT' | 'STRICT' | 'PROTECTIVE';
export type InfectionRiskLevelValue =
  | 'CONFIRMED' | 'HIGH_RISK' | 'MODERATE_RISK' | 'LOW_RISK' | 'CLEARED';

/** Mirrors the backend InfectionScreeningResponse DTO. */
export interface InfectionScreening {
  id: string;
  visitId: string;
  visitNumber: string | null;
  patientName: string | null;
  currentZone: string | null;
  screenedAt: string;
  screenedByName: string | null;
  riskLevel: InfectionRiskLevelValue | string;
  isolationType: IsolationTypeValue | string | null;
  suspectedCondition: string | null;
  notifiableDisease: string | null;
  // Screening criteria
  hasFever: boolean;
  hasCough: boolean;
  hasCoughDurationWeeks: number | null;
  hasNightSweats: boolean;
  hasWeightLoss: boolean;
  hasRash: boolean;
  hasDiarrhea: boolean;
  hasRecentTravel: boolean;
  recentTravelLocation: string | null;
  hasContactWithInfectious: boolean;
  contactDetails: string | null;
  hasBleedingSymptoms: boolean;
  isHealthcareWorker: boolean;
  immunocompromised: boolean;
  hasNeckStiffness: boolean;
  // PPE
  requiresN95: boolean;
  requiresGown: boolean;
  requiresGloves: boolean;
  requiresFaceShield: boolean;
  requiresApron: boolean;
  requiresBootCovers: boolean;
  // Actions + trail
  isolationRoomAssigned: string | null;
  isolationRoomAssignedAt: string | null;
  isolationAssignedByName: string | null;
  isolationStartedAt: string | null;
  placementDueAt: string | null;
  isolationEndedAt: string | null;
  isolationEndedByName: string | null;
  isolationEndReason: string | null;
  publicHealthNotifiedAt: string | null;
  publicHealthReferenceNumber: string | null;
  publicHealthNotifiedByName: string | null;
  notes: string | null;
  findings: string[] | null;
  createdAt: string;
}

export interface ScreenInfectionRequest {
  hasFever: boolean;
  hasCough: boolean;
  hasCoughDurationWeeks?: number;
  hasNightSweats: boolean;
  hasWeightLoss: boolean;
  hasRash: boolean;
  hasDiarrhea: boolean;
  hasRecentTravel: boolean;
  recentTravelLocation?: string;
  hasContactWithInfectious: boolean;
  contactDetails?: string;
  hasBleedingSymptoms: boolean;
  isHealthcareWorker: boolean;
  immunocompromised: boolean;
  hasNeckStiffness: boolean;
  notes?: string;
}

export const isolationApi = {
  // visitId is a PATH param (was wrongly posted bare → 404).
  screen: (visitId: string, data: ScreenInfectionRequest) =>
    post<InfectionScreening>(`/isolation/screen/${visitId}`, data),
  // body field is `roomNumber` (was wrongly `isolationRoomAssigned` → 400).
  assignRoom: (id: string, roomNumber: string) =>
    put<InfectionScreening>(`/isolation/${id}/assign-room`, { roomNumber }),
  // clearance requires a reason; path is /notify-public-health (was /notify → 404).
  endIsolation: (id: string, reason: string) =>
    put<InfectionScreening>(`/isolation/${id}/end`, { reason }),
  notifyPublicHealth: (id: string, referenceNumber?: string) =>
    put<InfectionScreening>(`/isolation/${id}/notify-public-health`, { referenceNumber }),
  getForVisit: (visitId: string) => get<InfectionScreening[]>(`/isolation/visit/${visitId}`),
  getActiveIsolations: (hospitalId: string, zone?: string) =>
    get<InfectionScreening[]>(
      `/isolation/hospital/${hospitalId}/active${zone ? `?zone=${zone}` : ''}`,
    ),
  getNotifiableDiseases: (hospitalId: string) =>
    get<InfectionScreening[]>(`/isolation/hospital/${hospitalId}/notifiable-diseases`),
};
