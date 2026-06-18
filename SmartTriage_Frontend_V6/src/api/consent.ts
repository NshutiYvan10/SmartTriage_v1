import { get, post, put } from './client';

export type ConsentType =
  | 'PROCEDURE' | 'SURGERY' | 'ANAESTHESIA' | 'BLOOD_TRANSFUSION' | 'HIV_TEST'
  | 'SEDATION' | 'IMAGING_CONTRAST' | 'RESEARCH_PARTICIPATION' | 'PHOTOGRAPHY' | 'OTHER';

export type ConsentStatus = 'GIVEN' | 'REFUSED' | 'WITHDRAWN';

export type ConsentGrantor =
  | 'PATIENT' | 'PARENT_OR_GUARDIAN' | 'NEXT_OF_KIN' | 'LEGAL_SURROGATE'
  | 'COURT_ORDER' | 'EMERGENCY_NO_CONSENT_REQUIRED';

export interface ConsentRecord {
  id: string;
  visitId: string;
  visitNumber: string;
  consentType: ConsentType;
  procedureName: string;
  description: string | null;
  risksExplained: string | null;
  benefitsExplained: string | null;
  alternativesExplained: string | null;
  questionsAnswered: boolean;
  interpreterUsed: boolean;
  interpreterName: string | null;
  language: string | null;
  consentGrantor: ConsentGrantor;
  grantorName: string | null;
  grantorRelationship: string | null;
  witnessName: string | null;
  status: ConsentStatus;
  obtainedByUserId: string | null;
  obtainedByName: string;
  obtainedByRole: string | null;
  obtainedByLicenseNumber: string | null;
  obtainedAt: string;
  withdrawnByName: string | null;
  withdrawnAt: string | null;
  withdrawalReason: string | null;
  notes: string | null;
  createdAt: string;
}

export interface RecordConsentRequest {
  visitId: string;
  consentType: ConsentType;
  procedureName: string;
  description?: string;
  risksExplained?: string;
  benefitsExplained?: string;
  alternativesExplained?: string;
  questionsAnswered?: boolean;
  interpreterUsed?: boolean;
  interpreterName?: string;
  language?: string;
  consentGrantor: ConsentGrantor;
  grantorName?: string;
  grantorRelationship?: string;
  witnessName?: string;
  status?: ConsentStatus; // GIVEN or REFUSED
  notes?: string;
}

export const consentApi = {
  record: (visitId: string, data: RecordConsentRequest) =>
    post<ConsentRecord>(`/consents/visit/${visitId}`, data),
  withdraw: (id: string, reason: string) =>
    put<ConsentRecord>(`/consents/${id}/withdraw`, { reason }),
  getForVisit: (visitId: string) => get<ConsentRecord[]>(`/consents/visit/${visitId}`),
  get: (id: string) => get<ConsentRecord>(`/consents/${id}`),
};
