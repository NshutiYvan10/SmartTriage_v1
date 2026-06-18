import { get, post, put, downloadBlob, saveBlob } from './client';

export interface MohReport {
  id: string;
  hospitalId: string;
  reportType: string;
  reportPeriodStart: string;
  reportPeriodEnd: string;
  generatedAt: string;
  generatedByName: string;
  status: string;
  submittedAt: string | null;
  totalEdVisits: number;
  totalTriaged: number;
  triageCategoryBreakdown: string;
  averageWaitTimeMinutes: number;
  mortalityCount: number;
  admissionCount: number;
  icuAdmissionCount: number;
  pediatricVisitCount: number;
  malariaPositiveCount: number;
  reportDataJson: string | null;
}

export const mohReportApi = {
  generate: (data: { hospitalId: string; reportType: string; periodStart: string; periodEnd: string }) => post<MohReport>('/moh-reports/generate', data),
  submit: (id: string) => put<MohReport>(`/moh-reports/${id}/submit`),
  accept: (id: string) => put<MohReport>(`/moh-reports/${id}/accept`),
  reject: (id: string, reason: string) => put<MohReport>(`/moh-reports/${id}/reject`, { reason }),
  getForHospital: (hospitalId: string, page = 0) => get<{ content: MohReport[]; totalElements: number }>(`/moh-reports/hospital/${hospitalId}?page=${page}&size=20`),
  get: (id: string) => get<MohReport>(`/moh-reports/${id}`),
  /** Download the statutory MoH / HMIS return as a PDF (de-identified aggregates). */
  downloadPdf: async (id: string) => {
    const { blob, filename } = await downloadBlob(`/moh-reports/${id}/pdf`, `moh-report-${id}.pdf`);
    saveBlob(blob, filename);
  },
};
