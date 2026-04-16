import { get, post, put } from './client';

export interface SepsisScreening {
  id: string;
  visitId: string;
  screenedAt: string;
  screenedByName: string;
  sepsisStatus: string;
  qsofaScore: number;
  alteredMentation: boolean;
  respiratoryRateHigh: boolean;
  systolicBpLow: boolean;
  sirsScore: number;
  temperatureCriteriaMet: boolean;
  heartRateCriteriaMet: boolean;
  respiratoryRateCriteriaMet: boolean;
  wbcCriteriaMet: boolean;
  suspectedInfectionSource: string;
  lactateLevel: number | null;
  bundleStartedAt: string | null;
  bundleCompletedAt: string | null;
  bloodCultureObtained: boolean;
  broadSpectrumAntibiotics: boolean;
  ivCrystalloidBolus: boolean;
  lactateMeasured: boolean;
  vasopressorsIfNeeded: boolean;
  repeatLactateIfElevated: boolean;
  notes: string;
  createdAt: string;
}

export const sepsisApi = {
  screen: (visitId: string) => post<SepsisScreening>(`/sepsis/screen/${visitId}`),
  startBundle: (screeningId: string) => put<SepsisScreening>(`/sepsis/${screeningId}/start-bundle`),
  updateBundle: (screeningId: string, data: Partial<SepsisScreening>) => put<SepsisScreening>(`/sepsis/${screeningId}/bundle`, data),
  getForVisit: (visitId: string) => get<SepsisScreening[]>(`/sepsis/visit/${visitId}`),
  getActive: (hospitalId: string) => get<SepsisScreening[]>(`/sepsis/hospital/${hospitalId}/active`),
};
