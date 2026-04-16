import { get, post, put } from './client';

export interface FastTrackActivation {
  id: string;
  visitId: string;
  fastTrackType: string;
  status: string;
  activatedAt: string;
  activatedByName: string;
  symptomOnsetTime: string | null;
  beFastScore: string | null;
  nihssScore: number | null;
  ctOrderedAt: string | null;
  ctCompletedAt: string | null;
  ctResult: string | null;
  isHemorrhagic: boolean | null;
  thrombolysisEligible: boolean | null;
  doorToCtMinutes: number | null;
  chestPainOnsetTime: string | null;
  ecgOrderedAt: string | null;
  ecgCompletedAt: string | null;
  ecgResult: string | null;
  stElevation: boolean | null;
  troponinResult: number | null;
  aspirinGiven: boolean | null;
  doorToEcgMinutes: number | null;
  doorToNeedleMinutes: number | null;
  completedAt: string | null;
  outcome: string | null;
  notes: string | null;
  createdAt: string;
}

export interface ActivateFastTrackRequest {
  visitId: string;
  fastTrackType: string;
  symptomOnsetTime?: string;
}

export const fasttrackApi = {
  activate: (data: ActivateFastTrackRequest) => post<FastTrackActivation>('/fast-track/activate', data),
  recordEcg: (id: string, data: { ecgCompletedAt: string; ecgResult: string; stElevation: boolean }) => put<FastTrackActivation>(`/fast-track/${id}/ecg`, data),
  recordCt: (id: string, data: { ctCompletedAt: string; ctResult: string; isHemorrhagic: boolean }) => put<FastTrackActivation>(`/fast-track/${id}/ct`, data),
  complete: (id: string, data: { outcome: string }) => put<FastTrackActivation>(`/fast-track/${id}/complete`, data),
  getForVisit: (visitId: string) => get<FastTrackActivation[]>(`/fast-track/visit/${visitId}`),
  getActive: (hospitalId: string) => get<FastTrackActivation[]>(`/fast-track/hospital/${hospitalId}/active`),
};
