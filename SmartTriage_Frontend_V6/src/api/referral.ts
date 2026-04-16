import { get, post, put } from './client';

export interface Referral {
  id: string;
  visitId: string;
  referralType: string;
  status: string;
  referringClinician: string;
  receivingHospitalName: string;
  receivingHospitalCode: string | null;
  referralReason: string;
  clinicalSummary: string;
  currentDiagnosis: string | null;
  currentTriageCategory: string | null;
  transportMode: string | null;
  estimatedTransferTimeMinutes: number | null;
  initiatedAt: string;
  completedAt: string | null;
  rhmisCaseNumber: string | null;
  samuRequestNumber: string | null;
  notes: string | null;
  createdAt: string;
}

export interface InitiateReferralRequest {
  visitId: string;
  referralType: string;
  receivingHospitalName: string;
  receivingHospitalCode?: string;
  referralReason: string;
  clinicalSummary: string;
  referringClinician: string;
  referringClinicianPhone?: string;
  currentDiagnosis?: string;
  transportMode?: string;
  estimatedTransferTimeMinutes?: number;
}

export const referralApi = {
  initiate: (data: InitiateReferralRequest) => post<Referral>('/referrals/initiate', data),
  contactReceiving: (id: string, data: { receivingClinician: string; receivingClinicianPhone?: string }) => put<Referral>(`/referrals/${id}/contact-receiving`, data),
  acceptReferral: (id: string) => put<Referral>(`/referrals/${id}/accept`),
  updateStabilization: (id: string, data: Record<string, boolean>) => put<Referral>(`/referrals/${id}/stabilization`, data),
  markDeparted: (id: string) => put<Referral>(`/referrals/${id}/depart`),
  markArrived: (id: string) => put<Referral>(`/referrals/${id}/arrive`),
  complete: (id: string) => put<Referral>(`/referrals/${id}/complete`),
  cancel: (id: string, reason: string) => put<Referral>(`/referrals/${id}/cancel`, { reason }),
  getForVisit: (visitId: string) => get<Referral[]>(`/referrals/visit/${visitId}`),
  getActive: (hospitalId: string, page = 0) => get<{ content: Referral[]; totalElements: number }>(`/referrals/hospital/${hospitalId}/active?page=${page}&size=20`),
};
