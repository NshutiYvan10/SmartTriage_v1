import { get, post, put } from './client';

export interface ClinicalPolicy {
  id: string;
  hospitalId: string | null;
  policyType: string;
  policyName: string;
  policyCode: string | null;
  description: string | null;
  policyContent: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  policyVersion: string | null;
  status: string;
  createdByName: string;
  approvedByName: string | null;
  approvedAt: string | null;
  createdAt: string;
}

export interface PolicyAuditLog {
  id: string;
  policyId: string;
  action: string;
  actionAt: string;
  actionByName: string;
  reason: string | null;
}

export const governanceApi = {
  create: (data: Partial<ClinicalPolicy>) => post<ClinicalPolicy>('/governance/policies', data),
  update: (id: string, data: Partial<ClinicalPolicy>) => put<ClinicalPolicy>(`/governance/policies/${id}`, data),
  submitForApproval: (id: string) => put<ClinicalPolicy>(`/governance/policies/${id}/submit`),
  approve: (id: string, data: { approverName: string; notes?: string }) => put<ClinicalPolicy>(`/governance/policies/${id}/approve`, data),
  activate: (id: string) => put<ClinicalPolicy>(`/governance/policies/${id}/activate`),
  suspend: (id: string, reason: string) => put<ClinicalPolicy>(`/governance/policies/${id}/suspend`, { reason }),
  archive: (id: string) => put<ClinicalPolicy>(`/governance/policies/${id}/archive`),
  getActive: (hospitalId: string, type?: string) => get<ClinicalPolicy[]>(`/governance/policies/hospital/${hospitalId}/active${type ? `?type=${type}` : ''}`),
  getAll: (hospitalId: string, page = 0) => get<{ content: ClinicalPolicy[]; totalElements: number }>(`/governance/policies/hospital/${hospitalId}?page=${page}&size=20`),
  get: (id: string) => get<ClinicalPolicy>(`/governance/policies/${id}`),
  getHistory: (id: string) => get<ClinicalPolicy[]>(`/governance/policies/${id}/history`),
  getAuditLog: (id: string, page = 0) => get<{ content: PolicyAuditLog[]; totalElements: number }>(`/governance/policies/${id}/audit?page=${page}&size=20`),
};
