import { get, post, put } from './client';

export interface SepsisScreening {
  id: string;
  visitId: string;
  visitNumber?: string;
  patientName?: string;
  // Patient location context (denormalised) — so a sepsis card shows WHERE.
  currentZone?: string | null;
  currentBedLabel?: string | null;
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
  // Pediatric safety + data quality (V74)
  pediatric?: boolean;
  pediatricCaveat?: string | null;
  insufficientData?: boolean;
  dataQualityNote?: string | null;
  // Time-stamped action trail (V74)
  bundleStartedByName?: string | null;
  bundleCompletedByName?: string | null;
  notes: string;
  createdAt: string;
}

/**
 * Optional overrides a clinician can attach when running a screening — these
 * are looked up nowhere automatically (the system has no coded lab catalog),
 * so they're operator-entered. They drive: WBC → SIRS WBC criterion; infection
 * source → SEPSIS_SUSPECTED; lactate > 2.0 mmol/L → SEVERE_SEPSIS. Mirrors the
 * backend SepsisScreeningRequest DTO. All optional — omitting the body screens
 * on vitals alone.
 */
export interface SepsisScreeningRequest {
  suspectedInfectionSource?: string;
  lactateLevel?: number;   // mmol/L
  wbcCount?: number;       // absolute count, cells/µL
  wbcBandsElevated?: boolean;
  notes?: string;
}

export const sepsisApi = {
  // post() only serializes a body when one is passed, so screen(visitId) with
  // no body is byte-for-byte the prior vitals-only request.
  screen: (visitId: string, body?: SepsisScreeningRequest) =>
    post<SepsisScreening>(`/sepsis/screen/${visitId}`, body),
  // B7 — backend path is PUT /sepsis/bundle/{id}/start (was calling
  // /sepsis/{id}/start-bundle → silent 404).
  startBundle: (screeningId: string) => put<SepsisScreening>(`/sepsis/bundle/${screeningId}/start`),
  // B7 — the backend completes ONE bundle item by its SepsisBundleItem enum
  // at PUT /sepsis/bundle/{id}/item/{item}. The old updateBundle PUT
  // /sepsis/{id}/bundle with a partial body was both a 404 and a shape
  // mismatch. Completing is one-way (idempotent set-true) server-side.
  completeBundleItem: (screeningId: string, item: string) =>
    put<SepsisScreening>(`/sepsis/bundle/${screeningId}/item/${item}`),
  // GET /sepsis/visit/{id} returns a Spring Page<SepsisScreeningResponse>
  // ({content,totalElements,…}), NOT a bare array — unwrap .content so
  // callers get a real SepsisScreening[]. (This was previously typed as an
  // array and would have silently been a non-array object at runtime.)
  getForVisit: (visitId: string) =>
    get<{ content: SepsisScreening[] }>(`/sepsis/visit/${visitId}`).then((p) => p?.content ?? []),
  getActive: (hospitalId: string, zone?: string) =>
    get<SepsisScreening[]>(
      `/sepsis/hospital/${hospitalId}/active${zone ? `?zone=${zone}` : ''}`,
    ),
};
