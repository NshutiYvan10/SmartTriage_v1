import { get, post, put } from './client';

export type ReferralType = 'INTERNAL_CONSULT' | 'EXTERNAL_REFERRAL' | 'ICU_ADMISSION_REQUEST';
export type ReferralUrgency = 'ROUTINE' | 'URGENT' | 'EMERGENT';
export type ReferralStatus = 'REQUESTED' | 'ACCEPTED' | 'DECLINED' | 'COMPLETED' | 'CANCELLED';

export interface ReferralRecord {
  id: string;
  visitId: string;
  visitNumber: string;
  referralType: ReferralType;
  specialty: string;
  urgency: ReferralUrgency;
  reasonForReferral: string;
  clinicalQuestion: string | null;
  targetFacility: string | null;
  status: ReferralStatus;
  requestedByUserId: string | null;
  requestedByName: string;
  requestedByRole: string | null;
  requestedAt: string;
  respondedByUserId: string | null;
  respondedByName: string | null;
  respondedByRole: string | null;
  respondedAt: string | null;
  responseNotes: string | null;
  declineReason: string | null;
  notes: string | null;
  createdAt: string;
}

export interface CreateReferralRequest {
  visitId: string;
  referralType: ReferralType;
  specialty: string;
  urgency: ReferralUrgency;
  reasonForReferral: string;
  clinicalQuestion?: string;
  targetFacility?: string;
  notes?: string;
}

export interface RespondReferralRequest {
  outcome: 'ACCEPTED' | 'DECLINED' | 'COMPLETED';
  responseNotes?: string;
  declineReason?: string;
}

export const referralApi = {
  request: (visitId: string, data: CreateReferralRequest) =>
    post<ReferralRecord>(`/referrals/visit/${visitId}`, data),
  respond: (id: string, data: RespondReferralRequest) =>
    put<ReferralRecord>(`/referrals/${id}/respond`, data),
  cancel: (id: string) => put<ReferralRecord>(`/referrals/${id}/cancel`),
  getForVisit: (visitId: string) => get<ReferralRecord[]>(`/referrals/visit/${visitId}`),
  get: (id: string) => get<ReferralRecord>(`/referrals/${id}`),
};
