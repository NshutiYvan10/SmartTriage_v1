import { get, post } from './client';

export interface SurgePrediction {
  id: string;
  hospitalId: string;
  predictedAt: string;
  predictionHorizonHours: number;
  predictedEdAdmissions: number;
  predictedIcuDemand: number;
  predictedRedPatients: number;
  currentEdOccupancy: number;
  currentIcuOccupancy: number;
  edCapacity: number;
  icuCapacity: number;
  surgeRiskScore: number;
  surgeRiskLevel: string;
  trendDirection: string;
  notes: string | null;
}

export const predictionApi = {
  predict: (hospitalId: string, horizonHours?: number) => post<SurgePrediction>(`/predictions/predict/${hospitalId}`, { horizonHours }),
  getHistory: (hospitalId: string, page = 0) => get<{ content: SurgePrediction[]; totalElements: number }>(`/predictions/hospital/${hospitalId}?page=${page}&size=20`),
  getLatest: (hospitalId: string) => get<SurgePrediction>(`/predictions/hospital/${hospitalId}/latest`),
};
