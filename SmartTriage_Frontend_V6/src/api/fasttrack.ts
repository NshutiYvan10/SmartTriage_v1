import { get, post, put } from './client';

export type FastTrackType = 'STROKE_SUSPECTED' | 'STEMI_SUSPECTED' | 'NSTEMI_SUSPECTED' | 'TIA_SUSPECTED';

export type FastTrackStatusValue =
  | 'ACTIVATED'
  | 'ECG_ORDERED'
  | 'ECG_COMPLETED'
  | 'CT_ORDERED'
  | 'CT_COMPLETED'
  | 'THROMBOLYSIS_CONSIDERED'
  | 'INTERVENTION_STARTED'
  | 'TRANSFERRED_FOR_PCI'
  | 'COMPLETED'
  | 'CANCELLED';

/** Mirrors the backend FastTrackResponse DTO exactly. */
export interface FastTrackActivation {
  id: string;
  visitId: string;
  visitNumber: string | null;
  patientName: string | null;
  hospitalId: string | null;
  currentZone: string | null;

  fastTrackType: FastTrackType;
  status: FastTrackStatusValue;
  activatedAt: string;
  activatedByName: string | null;

  // Action trail (V75)
  acknowledgedAt: string | null;
  acknowledgedByName: string | null;
  lastUpdatedByName: string | null;
  completedByName: string | null;

  // Stroke-specific
  symptomOnsetTime: string | null;
  beFastScore: string | null;
  nihssScore: number | null;
  ctOrderedAt: string | null;
  ctCompletedAt: string | null;
  ctResult: string | null;
  isHemorrhagic: boolean | null;
  thrombolysisEligible: boolean | null;
  thrombolysisAdvisory: string | null;
  thrombolysisStartedAt: string | null;
  doorToCtMinutes: number | null;

  // MI-specific
  chestPainOnsetTime: string | null;
  ecgOrderedAt: string | null;
  ecgCompletedAt: string | null;
  ecgResult: string | null;
  stElevation: boolean | null;
  troponinOrdered: boolean | null;
  troponinResult: number | null;
  troponinResultedAt: string | null;
  aspirinGiven: boolean | null;
  anticoagulantGiven: boolean | null;
  referredForPci: boolean | null;
  referredForPciAt: string | null;
  doorToEcgMinutes: number | null;
  doorToNeedleMinutes: number | null;

  // Outcome
  completedAt: string | null;
  outcome: string | null;
  notes: string | null;
  createdAt: string;
}

export interface ActivateFastTrackRequest {
  visitId: string;
  fastTrackType: FastTrackType;
  symptomOnsetTime?: string;
  chestPainOnsetTime?: string;
  beFastScore?: string;
  nihssScore?: number;
  notes?: string;
}

/** Non-binding decision-support recommendation from the detection engine. */
export interface FastTrackRecommendation {
  type: FastTrackType;
  confidence: number;
  reasoning: string;
  findings: string[];
}

export const fasttrackApi = {
  activate: (data: ActivateFastTrackRequest) =>
    post<FastTrackActivation>('/fast-track/activate', data),
  recordEcg: (id: string, data: { ecgResult: string; stElevation: boolean }) =>
    put<FastTrackActivation>(`/fast-track/${id}/ecg`, data),
  recordCt: (id: string, data: { ctResult: string; isHemorrhagic: boolean }) =>
    put<FastTrackActivation>(`/fast-track/${id}/ct`, data),
  updateStatus: (id: string, status: FastTrackStatusValue) =>
    put<FastTrackActivation>(`/fast-track/${id}/status?status=${status}`),
  complete: (id: string, data: { outcome?: string }) =>
    put<FastTrackActivation>(`/fast-track/${id}/complete`, data),
  cancel: (id: string, data: { reason?: string }) =>
    put<FastTrackActivation>(`/fast-track/${id}/cancel`, data),
  acknowledge: (id: string) =>
    put<FastTrackActivation>(`/fast-track/${id}/acknowledge`),
  // Backend returns the single most-recent activation (or null) — NOT an array.
  getForVisit: (visitId: string) =>
    get<FastTrackActivation | null>(`/fast-track/visit/${visitId}`),
  getRecommendation: (visitId: string) =>
    get<FastTrackRecommendation | null>(`/fast-track/visit/${visitId}/recommendation`),
  getActive: (hospitalId: string, zone?: string) =>
    get<FastTrackActivation[]>(
      `/fast-track/hospital/${hospitalId}/active${zone ? `?zone=${zone}` : ''}`,
    ),
};
