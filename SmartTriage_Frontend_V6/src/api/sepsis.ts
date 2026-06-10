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
  // B7 — backend path is PUT /sepsis/bundle/{id}/start (was calling
  // /sepsis/{id}/start-bundle → silent 404).
  startBundle: (screeningId: string) => put<SepsisScreening>(`/sepsis/bundle/${screeningId}/start`),
  // B7 — the backend completes ONE bundle item by its SepsisBundleItem enum
  // at PUT /sepsis/bundle/{id}/item/{item}. The old updateBundle PUT
  // /sepsis/{id}/bundle with a partial body was both a 404 and a shape
  // mismatch. Completing is one-way (idempotent set-true) server-side.
  completeBundleItem: (screeningId: string, item: string) =>
    put<SepsisScreening>(`/sepsis/bundle/${screeningId}/item/${item}`),
  getForVisit: (visitId: string) => get<SepsisScreening[]>(`/sepsis/visit/${visitId}`),
  getActive: (hospitalId: string, zone?: string) =>
    get<SepsisScreening[]>(
      `/sepsis/hospital/${hospitalId}/active${zone ? `?zone=${zone}` : ''}`,
    ),
};
