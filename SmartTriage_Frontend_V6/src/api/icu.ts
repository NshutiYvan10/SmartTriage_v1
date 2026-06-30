import { get, post, put } from './client';

export interface IcuEscalation {
  id: string;
  visitId: string;
  patientName: string;
  visitNumber: string;
  triageCategory: string;
  /** Patient CURRENT physical location (distinct from icuBedNumber, the ICU destination). */
  currentEdZone: string | null;
  currentBed: string | null;
  escalationReason: string;
  triggerType: string;
  escalatedAt: string;
  escalatedByName: string;
  isAutomatic: boolean;
  icuTeamNotifiedAt: string | null;
  icuConsultant: string | null;
  icuRespondedAt: string | null;
  icuResponseMinutes: number | null;
  icuBedAvailable: boolean | null;
  icuBedNumber: string | null;
  status: string;
  notes: string | null;
  createdAt: string;
}

export interface IcuCapacity {
  totalBeds: number;
  occupiedBeds: number;
  availableBeds: number;
  occupancyPercent: number;
}

export const icuApi = {
  request: (data: { visitId: string; triggerType?: string; escalationReason: string }) => post<IcuEscalation>('/icu/request', data),
  autoEvaluate: (visitId: string) => post<IcuEscalation>(`/icu/auto-evaluate/${visitId}`),
  notifyTeam: (id: string) => put<IcuEscalation>(`/icu/${id}/notify-team`),
  recordResponse: (id: string, data: { accepted: boolean; declineReason?: string; bedNumber?: string }) => put<IcuEscalation>(`/icu/${id}/response`, data),
  assignBed: (id: string, bedNumber: string) => put<IcuEscalation>(`/icu/${id}/assign-bed`, { bedNumber }),
  transfer: (id: string) => put<IcuEscalation>(`/icu/${id}/transfer`),
  cancel: (id: string, reason: string) => put<IcuEscalation>(`/icu/${id}/cancel?reason=${encodeURIComponent(reason)}`),
  getActive: (hospitalId: string, page = 0, zone?: string) =>
    get<{ content: IcuEscalation[]; totalElements: number }>(
      `/icu/hospital/${hospitalId}/active?page=${page}&size=20${zone ? `&zone=${zone}` : ''}`,
    ),
  getForVisit: (visitId: string) => get<IcuEscalation>(`/icu/visit/${visitId}`),
  getCapacity: (hospitalId: string) => get<IcuCapacity>(`/icu/hospital/${hospitalId}/capacity`),
};
