import { get, post, put } from './client';

export type LabOrderStatus =
  | 'ORDERED'
  | 'SPECIMEN_COLLECTED'
  | 'RECEIVED_BY_LAB'
  | 'PROCESSING'
  | 'RESULTED'
  | 'REJECTED'
  | 'CANCELLED';

export type LabPriority = 'STAT' | 'URGENT' | 'ROUTINE';

export type SpecimenRejectionReason =
  | 'HAEMOLYSED' | 'CLOTTED' | 'INSUFFICIENT_VOLUME'
  | 'MISLABELLED' | 'WRONG_CONTAINER' | 'EXPIRED' | 'OTHER';

export type CriticalContactMethod = 'PHONE' | 'IN_PERSON' | 'IN_APP';

export interface LabOrder {
  id: string;
  visitId: string;
  orderNumber: string;
  testName: string;
  testCode: string | null;
  priority: LabPriority;
  orderedAt: string;
  orderedByName: string;
  clinicalIndication: string | null;
  specimenType: string | null;
  specimenCollectedAt: string | null;
  specimenCollectedByName: string | null;
  receivedByLabAt: string | null;
  accessionNumber: string | null;
  processingStartedAt: string | null;
  resultedAt: string | null;
  enteredByName: string | null;
  verifiedAt: string | null;
  verifiedByName: string | null;
  resultValue: string | null;
  resultUnit: string | null;
  resultNumeric: number | null;
  referenceRangeMin: number | null;
  referenceRangeMax: number | null;
  isAbnormal: boolean;
  isCritical: boolean;
  criticalValueType: string | null;
  criticalValueNotifiedAt: string | null;
  criticalValueNotifiedTo: string | null;
  criticalValueAcknowledgedAt: string | null;
  criticalReadbackText: string | null;
  criticalContactMethod: CriticalContactMethod | null;
  turnaroundMinutes: number | null;
  status: LabOrderStatus;
  notes: string | null;
  cancelledAt: string | null;
  cancelledByName: string | null;
  cancelReason: string | null;
  rejectedAt: string | null;
  rejectedByName: string | null;
  rejectionReason: SpecimenRejectionReason | null;
  rejectionNotes: string | null;
  createdAt: string;
}

export interface OrderLabRequest {
  visitId: string;
  testName: string;
  testCode?: string;
  priority: LabPriority;
  specimenType?: string;
  clinicalIndication?: string;
  orderedByName: string;
}

export interface RecordLabResultRequest {
  resultValue: string;
  resultUnit?: string;
  resultNumeric?: number;
  referenceRangeMin?: number;
  referenceRangeMax?: number;
  enteredByName?: string;
  specimenQualityConcern?: boolean;
  notes?: string;
}

export interface ReceiveSpecimenRequest {
  accessionNumber?: string;
  receivedByName?: string;
}

export interface RejectSpecimenRequest {
  reason: SpecimenRejectionReason;
  notes?: string;
  rejectedByName?: string;
}

export interface AcknowledgeCriticalRequest {
  readbackText?: string;
  contactMethod?: CriticalContactMethod;
  acknowledgedByName?: string;
}

export const labApi = {
  order: (data: OrderLabRequest) => post<LabOrder>('/lab/order', data),

  collectSpecimen: (orderId: string, collectedByName?: string) =>
    put<LabOrder>(`/lab/${orderId}/collect-specimen${collectedByName ? `?collectedByName=${encodeURIComponent(collectedByName)}` : ''}`),

  receiveInLab: (orderId: string, body?: ReceiveSpecimenRequest) =>
    put<LabOrder>(`/lab/${orderId}/receive`, body ?? {}),

  rejectSpecimen: (orderId: string, body: RejectSpecimenRequest) =>
    post<LabOrder>(`/lab/${orderId}/reject`, body),

  startProcessing: (orderId: string, startedByName?: string) =>
    post<LabOrder>(`/lab/${orderId}/start-processing${startedByName ? `?startedByName=${encodeURIComponent(startedByName)}` : ''}`),

  recordResult: (orderId: string, data: RecordLabResultRequest) =>
    put<LabOrder>(`/lab/${orderId}/result`, data),

  acknowledgeCritical: (orderId: string, body?: AcknowledgeCriticalRequest) =>
    put<LabOrder>(`/lab/${orderId}/acknowledge-critical`, body ?? {}),

  cancel: (orderId: string, reason: string, cancelledByName?: string) => {
    const params = new URLSearchParams();
    if (reason) params.set('reason', reason);
    if (cancelledByName) params.set('cancelledByName', cancelledByName);
    return put<LabOrder>(`/lab/${orderId}/cancel?${params.toString()}`);
  },

  getForVisit: (visitId: string, page = 0) =>
    get<{ content: LabOrder[]; totalElements: number }>(`/lab/visit/${visitId}?page=${page}&size=20`),

  getInbox: (hospitalId: string) =>
    get<LabOrder[]>(`/lab/hospital/${hospitalId}/inbox`),

  getInProgress: (hospitalId: string) =>
    get<LabOrder[]>(`/lab/hospital/${hospitalId}/in-progress`),

  getPending: (hospitalId: string, page = 0) =>
    get<{ content: LabOrder[]; totalElements: number }>(`/lab/hospital/${hospitalId}/pending?page=${page}&size=20`),

  getCritical: (hospitalId: string) =>
    get<LabOrder[]>(`/lab/hospital/${hospitalId}/critical`),

  getStat: (hospitalId: string) =>
    get<LabOrder[]>(`/lab/hospital/${hospitalId}/stat`),
};
