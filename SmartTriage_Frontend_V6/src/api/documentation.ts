import { get, post, put } from './client';

export interface ClinicalDocument {
  id: string;
  visitId: string;
  documentType: string;
  title: string;
  content: string;
  // Author/signer identity is server-derived from the authenticated user.
  authorUserId: string | null;
  authorName: string;
  authorRole: string;
  authorLicenseNumber: string;
  signedAt: string | null;
  isSigned: boolean;
  coSignedByUserId: string | null;
  coSignedByName: string | null;
  coSignedByRole: string | null;
  coSignedByLicenseNumber: string | null;
  coSignedAt: string | null;
  isAmendment: boolean;
  amendmentReason: string | null;
  originalDocumentId: string | null;
  templateUsed: string | null;
  notes: string | null;
  // Type-specific structured fields (procedure / operative / death)
  procedurePerformed: string | null;
  procedureIndication: string | null;
  procedureFindings: string | null;
  procedureComplications: string | null;
  procedureOutcome: string | null;
  procedurePerformedBy: string | null;
  anaesthesiaType: string | null;
  timeOfDeath: string | null;
  causeOfDeath: string | null;
  antecedentCauses: string | null;
  mannerOfDeath: string | null;
  createdAt: string;
}

export interface CreateDocumentRequest {
  visitId: string;
  documentType: string;
  title: string;
  content: string;
  // No author fields: the author is taken from the authenticated session, server-side.
  // Optional type-specific structured fields:
  procedurePerformed?: string;
  procedureIndication?: string;
  procedureFindings?: string;
  procedureComplications?: string;
  procedureOutcome?: string;
  procedurePerformedBy?: string;
  anaesthesiaType?: string;
  timeOfDeath?: string;
  causeOfDeath?: string;
  antecedentCauses?: string;
  mannerOfDeath?: string;
}

export const documentationApi = {
  create: (data: CreateDocumentRequest) => post<ClinicalDocument>('/documents/create', data),
  // Sign / co-sign carry NO body — the signer is the authenticated user, server-derived.
  sign: (id: string) => put<ClinicalDocument>(`/documents/${id}/sign`),
  coSign: (id: string) => put<ClinicalDocument>(`/documents/${id}/co-sign`),
  amend: (id: string, data: { content: string; amendmentReason: string; notes?: string }) => post<ClinicalDocument>(`/documents/${id}/amend`, data),
  getForVisit: (visitId: string, page = 0) => get<{ content: ClinicalDocument[]; totalElements: number }>(`/documents/visit/${visitId}?page=${page}&size=20`),
  get: (id: string) => get<ClinicalDocument>(`/documents/${id}`),
  generateDischargeSummary: (visitId: string) => post<ClinicalDocument>(`/documents/visit/${visitId}/discharge-summary`),
  generateHandover: (visitId: string) => post<ClinicalDocument>(`/documents/visit/${visitId}/handover`),
};
