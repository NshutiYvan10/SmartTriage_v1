import { get, post, put } from './client';

export interface ClinicalDocument {
  id: string;
  visitId: string;
  documentType: string;
  title: string;
  content: string;
  authorName: string;
  authorRole: string;
  authorLicenseNumber: string;
  signedAt: string | null;
  isSigned: boolean;
  coSignedByName: string | null;
  coSignedAt: string | null;
  isAmendment: boolean;
  amendmentReason: string | null;
  originalDocumentId: string | null;
  templateUsed: string | null;
  notes: string | null;
  createdAt: string;
}

export interface CreateDocumentRequest {
  visitId: string;
  documentType: string;
  title: string;
  content: string;
  authorName: string;
  authorRole?: string;
  authorLicenseNumber?: string;
}

export const documentationApi = {
  create: (data: CreateDocumentRequest) => post<ClinicalDocument>('/documents/create', data),
  sign: (id: string, data: { signerName: string; licenseNumber: string }) => put<ClinicalDocument>(`/documents/${id}/sign`, data),
  coSign: (id: string, data: { coSignerName: string }) => put<ClinicalDocument>(`/documents/${id}/co-sign`, data),
  amend: (id: string, data: { content: string; amendmentReason: string; authorName: string }) => post<ClinicalDocument>(`/documents/${id}/amend`, data),
  getForVisit: (visitId: string, page = 0) => get<{ content: ClinicalDocument[]; totalElements: number }>(`/documents/visit/${visitId}?page=${page}&size=20`),
  get: (id: string) => get<ClinicalDocument>(`/documents/${id}`),
  generateDischargeSummary: (visitId: string) => post<ClinicalDocument>(`/documents/visit/${visitId}/discharge-summary`),
  generateHandover: (visitId: string) => post<ClinicalDocument>(`/documents/visit/${visitId}/handover`),
};
