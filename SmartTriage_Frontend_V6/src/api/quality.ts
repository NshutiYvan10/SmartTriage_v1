import { get, post } from './client';

export interface QualityMetricSnapshot {
  id: string;
  hospitalId: string;
  snapshotDate: string;
  snapshotPeriod: string;
  totalPatients: number;
  totalAdmissions: number;
  totalDischarges: number;
  totalTransfers: number;
  totalDeaths: number;
  totalLeftWithoutBeingSeen: number;
  pediatricPatients: number;
  redPatients: number;
  orangePatients: number;
  yellowPatients: number;
  greenPatients: number;
  averageWaitTimeMinutes: number;
  averageDoorToTriageMinutes: number;
  averageDoorToPhysicianMinutes: number;
  averageTotalEdStayMinutes: number;
  percentSeenWithinTarget: number;
  sepsisScreeningRate: number;
  sepsisBundleComplianceRate: number;
  peakEdOccupancy: number;
  icuBedUtilizationPercent: number;
  edMortalityRate: number;
}

export const qualityApi = {
  generate: (hospitalId: string, date: string, period: string) => post<QualityMetricSnapshot>('/quality/generate', { hospitalId, date, period }),
  getForHospital: (hospitalId: string, page = 0) => get<{ content: QualityMetricSnapshot[]; totalElements: number }>(`/quality/hospital/${hospitalId}?page=${page}&size=30`),
  getLatest: (hospitalId: string) => get<QualityMetricSnapshot>(`/quality/hospital/${hospitalId}/latest`),
  getByDateRange: (hospitalId: string, from: string, to: string) => get<QualityMetricSnapshot[]>(`/quality/hospital/${hospitalId}/range?from=${from}&to=${to}`),
};
