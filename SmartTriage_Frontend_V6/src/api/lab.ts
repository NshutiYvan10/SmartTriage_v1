import { get, post, put } from './client';

export type LabOrderStatus =
  | 'ORDERED'
  | 'SPECIMEN_COLLECTED'
  | 'RECEIVED_BY_LAB'
  | 'PROCESSING'
  | 'AWAITING_VERIFICATION'
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
  // Phase 2 — verification fields
  verificationRequired: boolean;
  verificationTimeoutAt: string | null;
  verificationAutoReleased: boolean;
  verificationOverride: boolean;
  verificationOverrideReason: string | null;
  verificationOverrideByName: string | null;
  verificationOverrideAt: string | null;
  verificationRejectionCount: number;
  verificationRejectionReason: string | null;
  verificationRejectedByName: string | null;
  verificationRejectedAt: string | null;
  createdAt: string;
}

export interface VerifyResultRequest {
  verifiedByName?: string;
  notes?: string;
}

export interface RejectVerificationRequest {
  reason: string;
  rejectedByName?: string;
}

export interface OverrideVerificationRequest {
  reason: string;
  overrideByName?: string;
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

  /**
   * Workflow 2 refinement — lab-tech History view. Paginated search
   * across orders (any status by default; optional `status` filter
   * narrows to a single state). `q` is matched case-insensitively
   * against orderNumber / testName / accessionNumber. Sorted newest
   * first. Used by the dashboard's History tab for audit + re-look-up
   * of previously processed work.
   */
  getHistory: (
    hospitalId: string,
    opts: { status?: string; q?: string; page?: number; size?: number } = {},
  ) => {
    const params = new URLSearchParams();
    if (opts.status) params.set('status', opts.status);
    if (opts.q && opts.q.trim()) params.set('q', opts.q.trim());
    params.set('page', String(opts.page ?? 0));
    params.set('size', String(opts.size ?? 50));
    return get<{ content: LabOrder[]; totalElements: number; totalPages: number; number: number }>(
      `/lab/hospital/${hospitalId}/history?${params.toString()}`,
    );
  },

  getStat: (hospitalId: string) =>
    get<LabOrder[]>(`/lab/hospital/${hospitalId}/stat`),

  // ── Phase 2: verification ──

  getAwaitingVerification: (hospitalId: string) =>
    get<LabOrder[]>(`/lab/hospital/${hospitalId}/awaiting-verification`),

  verifyResult: (orderId: string, body?: VerifyResultRequest) =>
    post<LabOrder>(`/lab/${orderId}/verify`, body ?? {}),

  rejectVerification: (orderId: string, body: RejectVerificationRequest) =>
    post<LabOrder>(`/lab/${orderId}/verify-reject`, body),

  releaseWithoutVerification: (orderId: string, body: OverrideVerificationRequest) =>
    post<LabOrder>(`/lab/${orderId}/release-without-verification`, body),
};
