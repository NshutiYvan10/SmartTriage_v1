import { get, post, put } from './client';

export interface LabOrder {
  id: string;
  visitId: string;
  orderNumber: string;
  testName: string;
  testCode: string | null;
  priority: string;
  orderedAt: string;
  orderedByName: string;
  specimenType: string | null;
  specimenCollectedAt: string | null;
  receivedByLabAt: string | null;
  resultedAt: string | null;
  resultValue: string | null;
  resultUnit: string | null;
  resultNumeric: number | null;
  referenceRangeMin: number | null;
  referenceRangeMax: number | null;
  isAbnormal: boolean;
  isCritical: boolean;
  criticalValueType: string | null;
  criticalValueNotifiedAt: string | null;
  criticalValueAcknowledgedAt: string | null;
  turnaroundMinutes: number | null;
  status: string;
  notes: string | null;
  createdAt: string;
}

export interface OrderLabRequest {
  visitId: string;
  testName: string;
  testCode?: string;
  priority: string;
  specimenType?: string;
  orderedByName: string;
}

export interface RecordLabResultRequest {
  resultValue: string;
  resultUnit?: string;
  resultNumeric?: number;
  referenceRangeMin?: number;
  referenceRangeMax?: number;
  isAbnormal?: boolean;
}

export const labApi = {
  order: (data: OrderLabRequest) => post<LabOrder>('/lab/order', data),
  collectSpecimen: (orderId: string) => put<LabOrder>(`/lab/${orderId}/collect-specimen`),
  receiveInLab: (orderId: string) => put<LabOrder>(`/lab/${orderId}/receive`),
  recordResult: (orderId: string, data: RecordLabResultRequest) => put<LabOrder>(`/lab/${orderId}/result`, data),
  acknowledgeCritical: (orderId: string) => put<LabOrder>(`/lab/${orderId}/acknowledge-critical`),
  cancel: (orderId: string, reason: string) => put<LabOrder>(`/lab/${orderId}/cancel`, { reason }),
  getForVisit: (visitId: string, page = 0) => get<{ content: LabOrder[]; totalElements: number }>(`/lab/visit/${visitId}?page=${page}&size=20`),
  getPending: (hospitalId: string, page = 0) => get<{ content: LabOrder[]; totalElements: number }>(`/lab/hospital/${hospitalId}/pending?page=${page}&size=20`),
  getCritical: (hospitalId: string) => get<LabOrder[]>(`/lab/hospital/${hospitalId}/critical`),
  getStat: (hospitalId: string) => get<LabOrder[]>(`/lab/hospital/${hospitalId}/stat`),
};
