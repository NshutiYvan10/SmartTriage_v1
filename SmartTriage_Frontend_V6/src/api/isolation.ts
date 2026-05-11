import { get, post, put } from './client';

export interface InfectionScreening {
  id: string;
  visitId: string;
  screenedAt: string;
  screenedByName: string;
  riskLevel: string;
  isolationType: string | null;
  suspectedCondition: string | null;
  notifiableDisease: string | null;
  hasFever: boolean;
  hasCough: boolean;
  hasNightSweats: boolean;
  hasWeightLoss: boolean;
  hasRash: boolean;
  hasDiarrhea: boolean;
  hasRecentTravel: boolean;
  hasContactWithInfectious: boolean;
  hasBleedingSymptoms: boolean;
  requiresN95: boolean;
  requiresGown: boolean;
  requiresGloves: boolean;
  requiresFaceShield: boolean;
  isolationRoomAssigned: string | null;
  isolationStartedAt: string | null;
  publicHealthNotifiedAt: string | null;
  notes: string | null;
  createdAt: string;
}

export interface ScreenInfectionRequest {
  visitId: string;
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
}

export const isolationApi = {
  screen: (data: ScreenInfectionRequest) => post<InfectionScreening>('/isolation/screen', data),
  assignRoom: (id: string, room: string) => put<InfectionScreening>(`/isolation/${id}/assign-room`, { isolationRoomAssigned: room }),
  endIsolation: (id: string) => put<InfectionScreening>(`/isolation/${id}/end`),
  notifyPublicHealth: (id: string) => put<InfectionScreening>(`/isolation/${id}/notify`),
  getForVisit: (visitId: string) => get<InfectionScreening[]>(`/isolation/visit/${visitId}`),
  getActiveIsolations: (hospitalId: string, zone?: string) =>
    get<InfectionScreening[]>(
      `/isolation/hospital/${hospitalId}/active${zone ? `?zone=${zone}` : ''}`,
    ),
};
