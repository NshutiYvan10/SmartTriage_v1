import { get, post, put, downloadBlob } from './client';

export interface MohReport {
  id: string;
  hospitalId: string | null;
  hospitalName?: string | null;
  reportLevel?: string; // HOSPITAL | NATIONAL
  includedHospitalCount?: number | null;
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
  // Module indicators + IDSR section (V111) — populated by MohIndicatorQueries.
  sepsisScreenedCount?: number | null;
  isolationActivatedCount?: number | null;
  topDiagnoses?: string | null;
  topChiefComplaints?: string | null;
  transferCount?: number | null;
  leftWithoutBeingSeenCount?: number | null;
  averageLengthOfStayMinutes?: number | null;
  notifiableDiseaseCount?: number | null;
  notifiableDiseaseBreakdown?: string | null;
  publicHealthNotifiedCount?: number | null;
  reportDataJson: string | null;
}

export const mohReportApi = {
  generate: (data: { hospitalId: string; reportType: string; periodStart: string; periodEnd: string }) => post<MohReport>('/moh-reports/generate', data),
  /** SUPER_ADMIN: generate a national rollup aggregated across all active hospitals. */
  generateNational: (data: { reportType: string; periodStart: string; periodEnd: string }) => post<MohReport>('/moh-reports/national/generate', data),
  /** SUPER_ADMIN: list national rollups. */
  getNational: (page = 0) => get<{ content: MohReport[]; totalElements: number }>(`/moh-reports/national?page=${page}&size=20`),
  submit: (id: string) => put<MohReport>(`/moh-reports/${id}/submit`),
  accept: (id: string) => put<MohReport>(`/moh-reports/${id}/accept`),
  reject: (id: string, reason: string) => put<MohReport>(`/moh-reports/${id}/reject`, { reason }),
  getForHospital: (hospitalId: string, page = 0) => get<{ content: MohReport[]; totalElements: number }>(`/moh-reports/hospital/${hospitalId}?page=${page}&size=20`),
  get: (id: string) => get<MohReport>(`/moh-reports/${id}`),
  /** Download the statutory MoH / HMIS return as a PDF (de-identified aggregates). */
  downloadPdf: (id: string) =>
    downloadBlob(`/moh-reports/${id}/pdf`, `moh-report-${id}.pdf`),
};
